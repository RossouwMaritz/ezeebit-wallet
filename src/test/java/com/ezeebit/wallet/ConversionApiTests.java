package com.ezeebit.wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.ezeebit.wallet.conversion.FakeExchangeRateClient;
import com.ezeebit.wallet.conversion.FakeExchangeRateClient.Outcome;
import com.ezeebit.wallet.ledger.LedgerService;
import com.ezeebit.wallet.ledger.LedgerService.Balance;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ConversionApiTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	LedgerService ledger;

	@Autowired
	FakeExchangeRateClient feed;

	final String merchant = UUID.randomUUID().toString();

	@BeforeEach
	void fund() throws Exception {
		deposit("1000", "funding");
	}

	@Test
	void conversionMovesBothLegsOnceAndReplays() throws Exception {
		BigDecimal houseZar = house("ZAR");
		BigDecimal houseUsdt = house("USDT");

		// Feed 0.055 USD per ZAR, less the 50 bps spread: 0.054725.
		String quoteId = quote("1000").andExpect(status().isCreated())
			.andExpect(jsonPath("$.buyAmount").value(54.725))
			.andExpect(jsonPath("$.rate").value(0.054725))
			.andReturn().getResponse().getContentAsString();
		Instant expiresAt = Instant.parse(JsonPath.read(quoteId, "$.expiresAt"));
		assertThat(expiresAt).isBetween(Instant.now().plusSeconds(25), Instant.now().plusSeconds(35));
		quoteId = JsonPath.read(quoteId, "$.id");

		String conversion = convert(quoteId).andExpect(status().isCreated())
			.andExpect(jsonPath("$.entryId").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		assertBalance("ZAR", "0");
		assertBalance("USDT", "54.725");
		assertThat(house("ZAR")).isEqualByComparingTo(houseZar.add(new BigDecimal("1000")));
		assertThat(house("USDT")).isEqualByComparingTo(houseUsdt.subtract(new BigDecimal("54.725")));

		// A replay, even after expiry, returns the same conversion and moves nothing.
		expire(quoteId);
		assertThat(convert(quoteId).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
			.isEqualTo(conversion);
		assertThat(conversionEntries(quoteId)).isEqualTo(1);
		assertBalance("ZAR", "0");
		assertBalance("USDT", "54.725");
	}

	@Test
	void racingExecutionsOfOneQuoteBookOnce() throws Exception {
		String quoteId = quoteId("1000");
		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			calls.add(() -> convert(quoteId).andReturn().getResponse().getStatus());
		}
		try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
			for (Future<Integer> status : pool.invokeAll(calls)) {
				assertThat(status.get()).isEqualTo(201);
			}
		}
		assertThat(conversionEntries(quoteId)).isEqualTo(1);
		assertBalance("ZAR", "0");
	}

	@Test
	void expiredQuoteIsRejected() throws Exception {
		String quoteId = quoteId("1000");
		expire(quoteId);

		convert(quoteId).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
		assertBalance("ZAR", "1000");
	}

	@Test
	void untrustedRateGivesNoQuote() throws Exception {
		quote("10").andExpect(status().isCreated());

		for (Outcome bad : List.of(Outcome.STALE, Outcome.OUTLIER, Outcome.ERROR)) {
			feed.script(bad);
			quote("10").andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("RATE_UNAVAILABLE"));
		}
		// The outlier was not adopted as the new reference.
		quote("10").andExpect(status().isCreated());
	}

	@Test
	void overConversionIsRejectedAndTheQuoteStaysUsable() throws Exception {
		String quoteId = quoteId("1000.01");

		convert(quoteId).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
		assertBalance("ZAR", "1000");
		assertThat(conversionEntries(quoteId)).isZero();

		deposit("0.01", "top-up");
		convert(quoteId).andExpect(status().isCreated());
		assertBalance("ZAR", "0");
	}

	@Test
	void houseShortIsInsufficientLiquidityAndNothingMoves() throws Exception {
		deposit("10000000", "big");
		BigDecimal houseUsdt = house("USDT");
		// 10,001,000 ZAR buys about 547,000 USDT; the house holds 100,000.
		String quoteId = quoteId("10001000");

		convert(quoteId).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_LIQUIDITY"));
		assertBalance("ZAR", "10001000");
		assertThat(ledger.balances(merchant)).extracting(Balance::currency).containsExactly(Currency.ZAR);
		assertThat(house("USDT")).isEqualByComparingTo(houseUsdt);
		assertThat(conversionEntries(quoteId)).isZero();
	}

	@Test
	void badRequestsAreRejected() throws Exception {
		mvc.perform(post("/merchants/{id}/quotes", merchant).contentType(MediaType.APPLICATION_JSON).content("""
				{"sellCurrency": "ZAR", "buyCurrency": "ZAR", "sellAmount": 10}"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
		quote("10.001").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));

		String quoteId = quoteId("10");
		mvc.perform(post("/merchants/{id}/conversions", "someone-else").contentType(MediaType.APPLICATION_JSON)
			.content(conversionBody(quoteId))).andExpect(status().isNotFound());
		assertBalance("ZAR", "1000");
	}

	private void deposit(String amount, String reference) throws Exception {
		mvc.perform(post("/merchants/{id}/deposits", merchant).contentType(MediaType.APPLICATION_JSON).content("""
				{"currency": "ZAR", "amount": %s, "reference": "%s"}""".formatted(amount, reference)))
			.andExpect(status().isCreated());
	}

	private ResultActions quote(String sellZar) throws Exception {
		return mvc.perform(post("/merchants/{id}/quotes", merchant).contentType(MediaType.APPLICATION_JSON).content("""
				{"sellCurrency": "ZAR", "buyCurrency": "USDT", "sellAmount": %s}""".formatted(sellZar)));
	}

	private String quoteId(String sellZar) throws Exception {
		return JsonPath.read(quote(sellZar).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
				"$.id");
	}

	private ResultActions convert(String quoteId) throws Exception {
		return mvc.perform(post("/merchants/{id}/conversions", merchant).contentType(MediaType.APPLICATION_JSON)
			.content(conversionBody(quoteId)));
	}

	private static String conversionBody(String quoteId) {
		return """
				{"quoteId": "%s"}""".formatted(quoteId);
	}

	private void expire(String quoteId) {
		jdbc.sql("UPDATE quote SET expires_at = NOW(6) - INTERVAL 1 SECOND WHERE id = ?").param(quoteId).update();
	}

	private void assertBalance(String currency, String available) {
		assertThat(ledger.balances(merchant))
			.filteredOn(b -> b.currency().name().equals(currency))
			.singleElement()
			.satisfies(b -> assertThat(b.available()).isEqualByComparingTo(new BigDecimal(available)));
	}

	private BigDecimal house(String currency) {
		return jdbc.sql("""
				SELECT b.balance FROM account a JOIN account_balance b ON b.account_id = a.id
				WHERE a.owner = 'platform' AND a.type = 'FX' AND a.currency = ?""")
			.param(currency)
			.query(BigDecimal.class)
			.single();
	}

	private long conversionEntries(String quoteId) {
		return jdbc.sql("SELECT COUNT(*) FROM journal_entry WHERE type = 'CONVERSION' AND reference = ?")
			.param(quoteId)
			.query(Long.class)
			.single();
	}

}
