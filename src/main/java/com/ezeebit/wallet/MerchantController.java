package com.ezeebit.wallet;

import java.util.List;

import com.ezeebit.wallet.conversion.ConversionService;
import com.ezeebit.wallet.conversion.ConversionService.Conversion;
import com.ezeebit.wallet.conversion.ConversionService.ConversionRequest;
import com.ezeebit.wallet.conversion.ConversionService.Quote;
import com.ezeebit.wallet.conversion.ConversionService.QuoteRequest;
import com.ezeebit.wallet.deposit.DepositService;
import com.ezeebit.wallet.deposit.DepositService.Deposit;
import com.ezeebit.wallet.deposit.DepositService.DepositRequest;
import com.ezeebit.wallet.ledger.LedgerService;
import com.ezeebit.wallet.ledger.LedgerService.Balance;
import com.ezeebit.wallet.ledger.LedgerService.Entry;
import com.ezeebit.wallet.withdrawal.WithdrawalService;
import com.ezeebit.wallet.withdrawal.WithdrawalService.Withdrawal;
import com.ezeebit.wallet.withdrawal.WithdrawalService.WithdrawalRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/merchants/{merchantId}")
class MerchantController {

	// Fits account.owner, and can never be the platform's owner name.
	static final String MERCHANT_ID = "(?!" + LedgerService.PLATFORM + "$)[A-Za-z0-9-]{1,64}";

	private final DepositService deposits;

	private final LedgerService ledger;

	private final WithdrawalService withdrawals;

	private final ConversionService conversions;

	MerchantController(DepositService deposits, LedgerService ledger, WithdrawalService withdrawals,
			ConversionService conversions) {
		this.deposits = deposits;
		this.ledger = ledger;
		this.withdrawals = withdrawals;
		this.conversions = conversions;
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

	/** 202: the money is held, but the payout itself finishes later. Poll the GET for the outcome. */
	@PostMapping("/withdrawals")
	@ResponseStatus(HttpStatus.ACCEPTED)
	Withdrawal withdraw(@PathVariable @Pattern(regexp = MERCHANT_ID) String merchantId,
			@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
			@Valid @RequestBody WithdrawalRequest request) {
		return withdrawals.withdraw(merchantId, idempotencyKey, request);
	}

	@GetMapping("/withdrawals/{withdrawalId}")
	Withdrawal withdrawal(@PathVariable @Pattern(regexp = MERCHANT_ID) String merchantId, @PathVariable long withdrawalId) {
		return withdrawals.find(merchantId, withdrawalId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@PostMapping("/quotes")
	@ResponseStatus(HttpStatus.CREATED)
	Quote quote(@PathVariable @Pattern(regexp = MERCHANT_ID) String merchantId, @Valid @RequestBody QuoteRequest request) {
		return conversions.quote(merchantId, request);
	}

	@PostMapping("/conversions")
	@ResponseStatus(HttpStatus.CREATED)
	Conversion convert(@PathVariable @Pattern(regexp = MERCHANT_ID) String merchantId,
			@Valid @RequestBody ConversionRequest request) {
		return conversions.convert(merchantId, request);
	}

}
