package com.ezeebit.wallet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.ezeebit.wallet.ledger.LedgerService;
import com.ezeebit.wallet.ledger.LedgerService.Balance;
import com.ezeebit.wallet.withdrawal.FakePayoutRail;
import com.ezeebit.wallet.withdrawal.FakePayoutRail.Outcome;
import com.ezeebit.wallet.withdrawal.WithdrawalService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WithdrawalApiTests {

	static final String BANK = """
			{"type": "BANK", "accountNumber": "62000000001", "bankCode": "250655"}""";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	LedgerService ledger;

	@Autowired
	WithdrawalService withdrawals;

	@Autowired
	FakePayoutRail rail;

	final String merchant = UUID.randomUUID().toString();

	@BeforeEach
	void fund() throws Exception {
		mvc.perform(post("/merchants/{id}/deposits", merchant).contentType(MediaType.APPLICATION_JSON).content("""
				{"currency": "ZAR", "amount": 100, "reference": "funding"}"""))
			.andExpect(status().isCreated());
	}

	@Test
	void racingWithdrawalsNeverOverspend() throws Exception {
		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			calls.add(() -> withdraw(key(), "10", BANK).andReturn().getResponse().getStatus());
		}
		List<Integer> statuses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
			for (Future<Integer> status : pool.invokeAll(calls)) {
				statuses.add(status.get());
			}
		}

		assertThat(statuses).filteredOn(s -> s == 202).hasSize(10);
		assertThat(statuses).filteredOn(s -> s == 422).hasSize(40);
		assertBalance("0", "100");
		assertThat(jdbc.sql("SELECT state FROM withdrawal WHERE merchant_id = ?").param(merchant).query(String.class).list())
			.hasSize(10)
			.containsOnly("SUBMITTED");
	}

	@Test
	void successfulPayoutSettlesOnceHoweverOftenTheRailSaysSo() throws Exception {
		String id = id(withdraw(key(), "40", BANK).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.state").value("SUBMITTED")));
		assertBalance("60", "40");

		withdrawals.onPayoutResult(id, true);
		withdrawals.onPayoutResult(id, true);
		withdrawals.onPayoutResult(id, false);

		assertState(id, "SUCCEEDED");
		assertBalance("60", "0");
		assertThat(entries(id)).containsExactlyInAnyOrder("HOLD", "PAYOUT");
	}

	@Test
	void failedPayoutReleasesTheHoldOnce() throws Exception {
		String id = id(withdraw(key(), "40", BANK));

		withdrawals.onPayoutResult(id, false);
		withdrawals.onPayoutResult(id, false);
		withdrawals.onPayoutResult(id, true);

		assertState(id, "FAILED");
		assertBalance("100", "0");
		assertThat(entries(id)).containsExactlyInAnyOrder("HOLD", "RELEASE");
	}

	@Test
	void railRejectionReleasesTheHoldStraightAway() throws Exception {
		rail.script(Outcome.REJECT);

		withdraw(key(), "40", BANK).andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("FAILED"));

		assertBalance("100", "0");
	}

	@Test
	void railTimeoutKeepsFundsHeldUntilTheResultArrives() throws Exception {
		rail.script(Outcome.TIMEOUT);

		String id = id(withdraw(key(), "40", BANK).andExpect(jsonPath("$.state").value("UNKNOWN")));
		assertBalance("60", "40");

		withdrawals.onPayoutResult(id, true);
		assertState(id, "SUCCEEDED");
		assertBalance("60", "0");
	}

	@Test
	void replayReturnsTheSameWithdrawalAndSubmitsOnce() throws Exception {
		String key = key();
		String id = id(withdraw(key, "40", BANK));

		assertThat(id(withdraw(key, "40.00", BANK).andExpect(status().isAccepted()))).isEqualTo(id);
		withdraw(key, "41", BANK).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
		assertThat(rail.submitted()).containsOnlyOnce(id);
		assertBalance("60", "40");
	}

	@Test
	void shortBalanceIsRejectedAndTheKeyCanBeRetried() throws Exception {
		String key = key();
		withdraw(key, "100.01", BANK).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
		assertBalance("100", "0");

		withdraw(key, "100", BANK).andExpect(status().isAccepted());
		assertBalance("0", "100");
	}

	@Test
	void badRequestsMoveNothing() throws Exception {
		withdraw(key(), "10", """
				{"type": "CHAIN", "network": "TRON", "address": "TXyz"}""").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_DESTINATION"));
		withdraw(key(), "10", """
				{"type": "BANK", "accountNumber": "62000000001"}""").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_DESTINATION"));
		withdraw(key(), "10.001", BANK).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
		mvc.perform(post("/merchants/{id}/withdrawals", merchant).contentType(MediaType.APPLICATION_JSON)
			.content(body("10", BANK))).andExpect(status().isBadRequest());
		assertBalance("100", "0");

		String id = id(withdraw(key(), "10", BANK));
		// The API only ever shows the random public id, never the database's sequential one.
		assertThat(UUID.fromString(id)).isNotNull();
		assertThat(entries(id)).containsExactly("HOLD");
		mvc.perform(get("/merchants/{id}/withdrawals/{wid}", merchant, id)).andExpect(status().isOk());
		mvc.perform(get("/merchants/{id}/withdrawals/{wid}", merchant, "1")).andExpect(status().isNotFound());
		mvc.perform(get("/merchants/{id}/withdrawals/{wid}", "someone-else", id)).andExpect(status().isNotFound());
	}

	private ResultActions withdraw(String key, String amount, String destination) throws Exception {
		return mvc.perform(post("/merchants/{id}/withdrawals", merchant).header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(amount, destination)));
	}

	private static String body(String amount, String destination) {
		return """
				{"currency": "ZAR", "amount": %s, "destination": %s}""".formatted(amount, destination);
	}

	private static String id(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private void assertState(String id, String state) throws Exception {
		mvc.perform(get("/merchants/{id}/withdrawals/{wid}", merchant, id)).andExpect(jsonPath("$.state").value(state));
	}

	private void assertBalance(String available, String held) {
		Balance zar = ledger.balances(merchant).getFirst();
		assertThat(zar.available()).isEqualByComparingTo(new BigDecimal(available));
		assertThat(zar.held()).isEqualByComparingTo(new BigDecimal(held));
	}

	private List<String> entries(String id) {
		return jdbc.sql("SELECT type FROM journal_entry WHERE reference = ?").param(id).query(String.class).list();
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

}
