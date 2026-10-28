package com.ezeebit.wallet;

import org.springframework.boot.SpringApplication;

public class TestWalletApplication {

	public static void main(String[] args) {
		// The local profile makes the fake payout rail report success, so a withdrawal can be seen settling.
		SpringApplication.from(WalletApplication::main)
			.with(TestcontainersConfiguration.class)
			.withAdditionalProfiles("local")
			.run(args);
	}

}
