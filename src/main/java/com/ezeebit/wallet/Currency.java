package com.ezeebit.wallet;

/** Supported currencies and how many decimal places each allows. Stablecoins are one currency regardless of chain. */
public enum Currency {
	ZAR(2),
	NGN(2),
	KES(2),
	USDT(6),
	USDC(6);

	public final int scale;

	Currency(int scale) {
		this.scale = scale;
	}
}
