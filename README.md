# Ezeebit wallet service

A take-home slice of the service that holds, converts and moves merchants' money.
Design and trade-offs are in [SOLUTION.md](SOLUTION.md).

## Prerequisites

- JDK 25 or newer
- Docker (tests and local runs start MySQL 8.4 with Testcontainers)

## Run the tests

```bash
./mvnw test
```

## Run the app locally

```bash
./mvnw spring-boot:test-run
```

This starts a throwaway MySQL container and the app on `http://localhost:8080`.
The exchange-rate feed and payout rail are in-process fakes.

## Try it

With the app running:

```bash
# Money arrives. Sending the same reference again returns the same deposit.
curl -s -X POST localhost:8080/merchants/m-1/deposits -H 'Content-Type: application/json' \
  -d '{"currency": "ZAR", "amount": 1000, "reference": "bank-tx-1"}'

curl -s localhost:8080/merchants/m-1/balances

# Quote selling 500 ZAR for USDT (valid 30 seconds), then execute it.
QUOTE=$(curl -s -X POST localhost:8080/merchants/m-1/quotes -H 'Content-Type: application/json' \
  -d '{"sellCurrency": "ZAR", "buyCurrency": "USDT", "sellAmount": 500}')
echo "$QUOTE"
curl -s -X POST localhost:8080/merchants/m-1/conversions -H 'Content-Type: application/json' \
  -d "{\"quoteId\": \"$(echo "$QUOTE" | sed 's/.*"id":"\([^"]*\)".*/\1/')\"}"

# Withdraw 100 ZAR to a bank account. Sending the same Idempotency-Key again returns the same withdrawal.
curl -s -X POST localhost:8080/merchants/m-1/withdrawals -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: payout-1' \
  -d '{"currency": "ZAR", "amount": 100,
       "destination": {"type": "BANK", "accountNumber": "62000000001", "bankCode": "250655"}}'

curl -s localhost:8080/merchants/m-1/withdrawals/1

# Every posting on the merchant's ZAR accounts, newest first.
curl -s localhost:8080/merchants/m-1/accounts/ZAR/entries
```

Locally the fake payout rail accepts every withdrawal but never reports a result (there is no webhook
endpoint), so withdrawals stay `SUBMITTED` with the money held. The tests drive results directly.
