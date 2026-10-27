package com.ezeebit.wallet;

/** A business rule said no. Carries the code the API returns to the merchant. */
public class WalletException extends RuntimeException {

	private final ErrorCode code;

	public WalletException(ErrorCode code, String message) {
		super(message);
		this.code = code;
	}

	public ErrorCode code() {
		return code;
	}

}
