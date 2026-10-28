package com.ezeebit.wallet;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
class DepositApiTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	final String merchant = UUID.randomUUID().toString();

	final String reference = UUID.randomUUID().toString().substring(0, 20);

	@Test
	void replayedDepositIsBookedOnce() throws Exception {
		String first = deposit("ZAR", "100.5").andExpect(status().isCreated())
			.andExpect(jsonPath("$.amount").value(100.50))
			.andExpect(jsonPath("$.entryId").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		String replay = deposit("ZAR", "100.50").andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();

		assertThat(replay).isEqualTo(first);
		assertThat(depositEntries()).isEqualTo(1);
		mvc.perform(get("/merchants/{id}/balances", merchant))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].currency").value("ZAR"))
			.andExpect(jsonPath("$[0].available").value(100.50))
			.andExpect(jsonPath("$[0].held").value(0));
		mvc.perform(get("/merchants/{id}/accounts/ZAR/entries", merchant))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].type").value("DEPOSIT"))
			.andExpect(jsonPath("$[0].reference").value(merchant + "/" + reference))
			.andExpect(jsonPath("$[0].entryId").doesNotExist())
			.andExpect(jsonPath("$[0].account").value("AVAILABLE"))
			.andExpect(jsonPath("$[0].amount").value(100.50));
	}

	@Test
	void sameReferenceWithDifferentAmountIsAConflict() throws Exception {
		deposit("ZAR", "100").andExpect(status().isCreated());

		deposit("ZAR", "200").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
		deposit("KES", "100").andExpect(status().isConflict());
		assertThat(depositEntries()).isEqualTo(1);
		mvc.perform(get("/merchants/{id}/balances", merchant)).andExpect(jsonPath("$[0].available").value(100));
	}

	@Test
	void referencesThatDifferOnlyInCaseAreDifferentDeposits() throws Exception {
		for (String ref : List.of(reference + "-a", reference + "-A")) {
			mvc.perform(post("/merchants/{id}/deposits", merchant).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"currency": "ZAR", "amount": 10, "reference": "%s"}""".formatted(ref)))
				.andExpect(status().isCreated());
		}
		mvc.perform(get("/merchants/{id}/balances", merchant)).andExpect(jsonPath("$[0].available").value(20));
	}

	@Test
	void concurrentReplaysAreBookedOnce() throws Exception {
		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			calls.add(() -> deposit("USDT", "25").andReturn().getResponse().getStatus());
		}
		try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
			for (Future<Integer> status : pool.invokeAll(calls)) {
				assertThat(status.get()).isEqualTo(201);
			}
		}
		assertThat(depositEntries()).isEqualTo(1);
		mvc.perform(get("/merchants/{id}/balances", merchant)).andExpect(jsonPath("$[0].available").value(25));
	}

	@Test
	void badInputIsRejectedBeforeAnythingIsBooked() throws Exception {
		deposit("ZAR", "1.005").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
		deposit("ZAR", "1" + "0".repeat(20)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
		deposit("ZAR", "-5").andExpect(status().isBadRequest());
		deposit("XYZ", "5").andExpect(status().isBadRequest());
		mvc.perform(post("/merchants/platform/deposits").contentType(MediaType.APPLICATION_JSON)
			.content(body("ZAR", "5"))).andExpect(status().isBadRequest());
		mvc.perform(get("/merchants/{id}/accounts/XYZ/entries", merchant)).andExpect(status().isBadRequest());
		assertThat(depositEntries()).isZero();
	}

	private ResultActions deposit(String currency, String amount) throws Exception {
		return mvc.perform(post("/merchants/{id}/deposits", merchant).contentType(MediaType.APPLICATION_JSON)
			.content(body(currency, amount)));
	}

	private String body(String currency, String amount) {
		return """
				{"currency": "%s", "amount": %s, "reference": "%s"}""".formatted(currency, amount, reference);
	}

	private long depositEntries() {
		return jdbc.sql("SELECT COUNT(*) FROM journal_entry WHERE type = 'DEPOSIT' AND reference = ?")
			.param(merchant + "/" + reference)
			.query(Long.class)
			.single();
	}

}
