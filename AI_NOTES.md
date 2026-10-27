# AI usage notes

I used Claude Code to plan the design, then to write code and docs. These are the places where I
accepted, changed or rejected what it produced.

## Planning

- **Rejected a recovery sweeper in code.** The AI proposed a scheduled job to resubmit stuck
  withdrawals. Without it, money can get stuck in `held` but is never lost or paid twice, so
  I kept it as a design note instead of code.
- **Rejected a webhook endpoint.** The AI proposed an HTTP callback endpoint for the payout rail.
  Tests call the service method directly; the endpoint adds no proof.
- **Changed: the house FX position must not go negative.** The AI let the platform FX account go
  negative as the "position to hedge". I required it to stay non-negative, so conversions can
  fail on liquidity and the house stock is seeded.
- **Accepted:** double-entry ledger, idempotency key written in the same transaction as the work,
  conditional `UPDATE … WHERE available >= ?` for debits, real MySQL in tests over H2.
- **Changed: Java version.** I asked for the latest release (27); the AI pushed back to 25 LTS
  because of tooling lag and reviewer setup. Accepted.
