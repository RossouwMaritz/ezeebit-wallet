package com.ezeebit.wallet;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
	INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_CONTENT),
	CURRENCY_MISMATCH(HttpStatus.BAD_REQUEST),
	INVALID_AMOUNT(HttpStatus.BAD_REQUEST),
	IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT);

	public final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}
}
