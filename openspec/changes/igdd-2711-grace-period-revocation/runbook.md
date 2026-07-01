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
cycle it finds credentials that are `active` with a `graceExpiresAt` in the past, sets them to
`revoked` in DynamoDB, emits an `API_KEY_REVOKED` audit event, and evicts the key from the local
credential cache. A single instance runs per cycle (host-ordering election). Revocation is idempotent.

## Log signals

Structured JSON events on the Hub log stream, keyed by `eventType`. The trio lets operations see
that a run **started** and whether it **succeeded** or **failed**:

| Event | When | Level | Key fields |
|---|---|---|---|
| `GRACE_REVOCATION_STARTED` | at the start of a cycle (on the instance that runs it) | INFO | `environment` |
| `GRACE_REVOCATION_RUN` | cycle completed successfully (even if 0 revoked) | INFO | `environment`, `evaluated`, `revoked` |
| `GRACE_REVOCATION_FAILED` | a cycle threw an unhandled exception | ERROR | exception detail |

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
   (status `active` with `graceExpiresAt` in the past), call:
   ```
   DELETE /api/apikeys/{jti}
   ```
   Config Console sets the credential to `revoked` and propagates cache eviction to all Hub instances
   via `/rest/refresh`. This is the same end state the job would have produced.
3. **Identify affected keys** — query the `ApiKeyCredential` records for the environment where
   `status = active` and `graceExpiresAt < now` (e.g. via the Config Console key list or a DynamoDB
   query on `entityType = ApiKeyCredential`, sort key prefix `{env}#`).
4. **Verify** — confirm the keys now show `revoked` and that an `API_KEY_REVOKED` audit event was
   recorded for each.

## Notes

- A revoked key may still authenticate from a warm cache on non-acting instances for up to
  `jwt.credential-cache-ttl` (default 5 min) before they re-validate against DynamoDB. This is
  expected for grace revocation (non-urgent). For urgent revocation, use the Config Console manual
  revoke path, which evicts all instances immediately.
- The job only *reads* `graceExpiresAt` / `supersededBy`; those are written by Config Console at
  renewal (IGDD-2707).
