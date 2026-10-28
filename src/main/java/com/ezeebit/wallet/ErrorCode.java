package com.ezeebit.wallet;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
	INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_CONTENT),
	INSUFFICIENT_LIQUIDITY(HttpStatus.UNPROCESSABLE_CONTENT),
	QUOTE_EXPIRED(HttpStatus.UNPROCESSABLE_CONTENT),
	QUOTE_NOT_FOUND(HttpStatus.NOT_FOUND),
	RATE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
	CURRENCY_MISMATCH(HttpStatus.BAD_REQUEST),
	INVALID_AMOUNT(HttpStatus.BAD_REQUEST),
	INVALID_DESTINATION(HttpStatus.BAD_REQUEST),
	IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT);

	public final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}
}
