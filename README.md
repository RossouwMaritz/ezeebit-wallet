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

<!-- TODO: curl examples once endpoints exist -->
