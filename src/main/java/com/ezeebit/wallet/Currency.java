package com.ezeebit.wallet;

/** Supported currencies and how many decimal places each allows. Stablecoins are one currency regardless of chain. */
public enum Currency {
	ZAR(2, false),
	NGN(2, false),
	KES(2, false),
	USDT(6, true),
	USDC(6, true);

	public final int scale;

	/** Paid out on a chain rather than to a bank account. */
	public final boolean stablecoin;

	Currency(int scale, boolean stablecoin) {
		this.scale = scale;
		this.stablecoin = stablecoin;
	}
}
