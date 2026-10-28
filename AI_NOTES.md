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
- **Changed: no `currency` table and no `allow_negative` column.** The AI planned both. I had
  currencies hardcoded as a Java enum (they only change with a deploy anyway) and had "may go
  negative" derived from the account type, so the rule lives in one place.
- **Changed: no generic idempotency table.** The AI planned and built an `idempotency_key` table
  that stored each response. I questioned whether it was needed; we kept the standard
  `Idempotency-Key` header for withdrawals but store it on the withdrawal row, and deposits and
  conversions dedupe on their natural keys (external reference, quote id). Replays return the
  current state instead of a stored copy.
- **Changed: Java version.** I asked for the latest release (27); the AI pushed back to 25 LTS
  because of tooling lag and reviewer setup. Accepted.

## Building

I had the AI work one plan step at a time, run the full test suite on real MySQL after each step, and
tell me where the code differed from the plan before I committed it.

- **Accepted: early payout results.** The AI noticed that an asynchronous rail can report a result
  before we mark the withdrawal as submitted, and allowed `REQUESTED → SUCCEEDED/FAILED` with a
  conditional "mark submitted" update. This was not in the plan.
- **Accepted: replay instead of `QUOTE_USED`.** The plan said both "conversions dedupe on the quote
  id" and "a reused quote is an error". The AI pointed out the contradiction and chose replay, which
  matches deposits and withdrawals and helps a client that lost the response.
- **Accepted: a timezone fix.** While testing quote expiry, the AI found that every timestamp read
  from MySQL was 2 hours off when the JVM is not on UTC, including the existing entries endpoint. It
  pinned the JDBC session to UTC and showed the test failing without the fix.
- **Reviewed: the FX margin.** I asked whether we add a margin on the rate. It is a single 50 bps
  spread plus rounding down; I kept it as is and had the limits (one spread for every pair, no
  separate revenue account) written up in SOLUTION.md instead of built.
- **Checked against the running app:** I had the AI run the README's curl examples against the real
  app, not just the tests, before the docs went in.
