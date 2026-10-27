package com.ezeebit.wallet;

import java.util.List;

import com.ezeebit.wallet.deposit.DepositService;
import com.ezeebit.wallet.deposit.DepositService.Deposit;
import com.ezeebit.wallet.deposit.DepositService.DepositRequest;
import com.ezeebit.wallet.ledger.LedgerService;
import com.ezeebit.wallet.ledger.LedgerService.Balance;
import com.ezeebit.wallet.ledger.LedgerService.Entry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/merchants/{merchantId}")
class MerchantController {

	// Fits account.owner, and can never be the platform's owner name.
	static final String MERCHANT_ID = "(?!" + LedgerService.PLATFORM + "$)[A-Za-z0-9-]{1,64}";

	private final DepositService deposits;

	private final LedgerService ledger;

	MerchantController(DepositService deposits, LedgerService ledger) {
		this.deposits = deposits;
		this.ledger = ledger;
	}

	@PostMapping("/deposits")
	@ResponseStatus(HttpStatus.CREATED)
	Deposit deposit(@PathVariable @Pattern(regexp = MERCHANT_ID) String merchantId,
			@Valid @RequestBody DepositRequest request) {
		return deposits.deposit(merchantId, request);
	}

	@GetMapping("/balances")
	List<Balance> balances(@PathVariable @Pattern(regexp = MERCHANT_ID) String merchantId) {
		return ledger.balances(merchantId);
	}

	@GetMapping("/accounts/{currency}/entries")
	List<Entry> entries(@PathVariable @Pattern(regexp = MERCHANT_ID) String merchantId, @PathVariable Currency currency) {
		return ledger.entries(merchantId, currency);
	}

}
