# Runbook: Grace-Period Revocation Job (IGDD-2711)

Operations runbook for the scheduled job that revokes superseded API keys after their grace
period expires. Satisfies IGDD-2711 AC #3 (failure detection + manual remediation).

> **Note:** This is a draft artifact kept with the change. Relocate to the team's canonical
> runbook location (Confluence / ops docs) when finalized.
>
> **Monitoring decision (2026-07-01, Paul):** for now the job emits **log messages only** — a
> started/succeeded/failed trio (below). Automated alarms are deferred; APHL manages the AWS
> environment (including CloudWatch), so if/when alarms are added they'd be handed to APHL against
> these same log events. The "Alert conditions" section below is retained as the spec for that future
> work. (CloudWatch was the chosen platform over Elastic.)

## What the job does

`GracePeriodRevocationScheduler` runs inside Hub on a fixed interval
(`apikey.grace-revocation.interval`, default 1h; gated by `apikey.grace-revocation.enabled`). Each
cycle it finds credentials that are `grace_period` with a `graceExpiresAt` in the past, terminates each
in DynamoDB, emits the matching audit event, and evicts the key from the local credential cache.

The terminal status depends on which limit came first (IGDD-3167):

| Condition | Status written | Fields written | Audit event |
|---|---|---|---|
| the key's own `expiresAt` is on or before `graceExpiresAt` | `expired` | `expiredAt`, `expiredBy = system:grace-expiration` | `API_KEY_EXPIRED` |
| otherwise (including a missing `expiresAt`) | `revoked` | `revokedAt`, `revokedBy = system:grace-revocation` | `API_KEY_REVOKED` |

Every enabled instance runs each cycle; a conditional DynamoDB write (`terminateIfGracePeriod`, which
writes only while `status = grace_period`) ensures each key is terminated and audited exactly once
across the fleet. Termination is idempotent.

**The sweep is not scoped to one environment.** The credential sort key is `{jti}` with no environment
prefix, so there is no prefix left to scope the query by, and the DynamoDB table is shared across
environments (dev and test both use `izgateway-dev-test`). Any enabled Hub may therefore terminate a
credential belonging to another environment. This is harmless — grace expiry is environment-independent
and the conditional write keeps it exactly-once — but it means you must read `environment` and
`serverName` on the events below to know which instance acted.

## Log signals

Structured JSON events on the Hub log stream, keyed by `eventType`. The trio lets operations see
that a run **started** and whether it **succeeded** or **failed**:

| Event | When | Level | Key fields |
|---|---|---|---|
| `GRACE_REVOCATION_STARTED` | at the start of a cycle (on the instance that runs it) | INFO | `environment`, `serverName` |
| `GRACE_REVOCATION_RUN` | cycle completed successfully (even if 0 terminated) | INFO | `environment`, `serverName`, `evaluated`, `expired`, `revoked` |
| `GRACE_REVOCATION_FAILED` | a cycle threw an unhandled exception | ERROR | exception detail |

The `API_KEY_REVOKED` / `API_KEY_EXPIRED` audit events carry `environment` and `serverName` too, so a
termination can be attributed to the Hub instance that performed it. `environment` identifies the
**acting instance**, not the scope of the sweep.

## Alert conditions (deferred — spec for future CloudWatch alarms)

1. **Job failure** — one or more `GRACE_REVOCATION_FAILED` events in a period.
   - CloudWatch: Logs metric filter `{ $.eventType = "GRACE_REVOCATION_FAILED" }` → alarm `>= 1`.
   - Elastic: watcher/alert on `eventType: GRACE_REVOCATION_FAILED`.
2. **Missed run (job didn't execute)** — no `GRACE_REVOCATION_RUN` within the run interval plus a
   buffer (e.g. ~70 min for the hourly default). This is the "Lambda timeout / ECS task failure"
   case from the AC.
   - CloudWatch: metric filter `{ $.eventType = "GRACE_REVOCATION_RUN" }` → alarm `< 1` over the
     window, **treat missing data as breaching**.
   - Elastic: watcher that alerts when the `GRACE_REVOCATION_RUN` count over the window is 0.

Route alert actions to the same notification channel used by the existing `izgateway-*` alarms.

## Manual remediation

If the job is not running (failure or missed-run alert) and superseded keys may have passed their
grace period:

1. **Confirm Hub health** — check Hub instances are up and the job is enabled
   (`apikey.grace-revocation.enabled=true`). A deploy, scale-in, or the job being disabled can all
   cause a missed run. Restarting/restoring Hub lets the next scheduled cycle catch up automatically
   (the job is idempotent and processes all expired-grace keys each run).
2. **If Hub cannot be restored promptly, revoke manually via Config Console** — for each affected key
   (status `grace_period` with `graceExpiresAt` in the past), call:
   ```
   DELETE /api/apikeys/{jti}
   ```
   Config Console sets the credential to `revoked` and propagates cache eviction to all Hub instances
   via `/rest/refresh`. This is the same end state the job would have produced.
3. **Identify affected keys** — query the `ApiKeyCredential` records where
   `status = grace_period` and `graceExpiresAt < now` (e.g. via the Config Console key list or a DynamoDB
   query on `entityType = ApiKeyCredential`).
4. **Verify** — confirm the keys now show `revoked` and that an `API_KEY_REVOKED` audit event was
   recorded for each.

## Notes

- A revoked key may still authenticate from a warm cache on non-acting instances for up to
  `jwt.credential-cache-ttl` (default 5 min) before they re-validate against DynamoDB. This is
  expected for grace revocation (non-urgent). For urgent revocation, use the Config Console manual
  revoke path, which evicts all instances immediately.
- The job only *reads* `graceExpiresAt` / `supersededBy`; those are written by Config Console at
  renewal (IGDD-2707).
