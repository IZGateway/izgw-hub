# Runbook: Source-Attack Lockout and Exceptions (IGDD-2805)

Operations runbook for the source-attack auto-lockout fix and its per-sender exception mechanism.
Satisfies the ticket's requirement that the exception configuration "is documented and does not
require a code deployment to update."

> **Note:** This is a draft artifact kept with the change. Relocate to the team's canonical
> runbook location (Confluence / ops docs) when finalized.

## What this does

When Hub detects a source attack on an **inbound** request (a `SecurityFault.sourceAttack`, fault
code `61`, thrown by `SoapMessageReader` when it sees `script`/`javascript` in the SOAP body — e.g., a
patient name containing "javascript"), it now adds the sender to the DynamoDB deny list, **provided**:

1. `hub.source-attack-lockout.enabled` is `true` (env var `HUB_SOURCE_ATTACK_LOCKOUT_ENABLED`,
   **default `false`**), and
2. the sender has no configured source-attack exception.

The triggering request is always rejected with the `SecurityFault` regardless of the flag or an
exception — this only controls whether the **sender** gets deny-listed as a result.

Outbound source-attack detection (Hub scanning a destination IIS's *response*) is deliberately
excluded from this lockout — it protects against a different actor (a compromised destination, not a
misbehaving sender) and is out of scope for this change.

## Enabling the lockout

Set `HUB_SOURCE_ATTACK_LOCKOUT_ENABLED=true` for the target environment (or
`hub.source-attack-lockout.enabled=true` directly in `application.yml`). No code deployment is
required — this is a plain Spring `@Value`-backed property.

**Before enabling in an environment with known prior false positives** (e.g., dev/onboarding, which
saw the VHA "javascript" incident), pre-create exception records for the affected senders first (see
below), or the next source-attack detection for that sender will deny-list it immediately.

## Managing exceptions

Admin-only (`ADMIN` role) REST API at `/rest/sourceAttackExceptions`:

| Method | Path | Body / params | Effect |
|---|---|---|---|
| `GET` | `/rest/sourceAttackExceptions` | — | List all configured exceptions for the current environment |
| `POST` | `/rest/sourceAttackExceptions` | `{ "sender": "...", "reason": "..." }` | Create (or replace) an exception for that sender |
| `DELETE` | `/rest/sourceAttackExceptions/{sender}` | — | Remove the sender's exception, if any |

**Known limitation — sender-only, no per-receiver scoping.** An exception exempts a sender from
lockout *regardless of destination*. The ticket asked for "per-sender and/or per-receiver" exceptions,
but the destination a sender was submitting to is not reliably known at the point Hub detects a source
attack (the SOAP body failed to parse, so the destination field inside it may never have been reached)
— without a change to the shared `izgw-core` library, a destination-scoped exception could not
actually be enforced, and storing one anyway would risk an operator believing a sender is protected for
a specific destination when it is not. See `design.md` D5/D6. If per-receiver scoping becomes a real
need, that requires a coordinated `izgw-core` change to capture the destination before the parse
failure is thrown.

**Multi-instance propagation lag.** Hub runs multiple instances behind the ALB, each with its own
in-memory exception cache. A newly created or removed exception takes effect on the acting instance
immediately, but other instances only see it on their next refresh cycle (`refreshPeriod`, default
300 seconds). The same lag applies to the deny-list add itself. This is the same convergence trade-off
already accepted elsewhere in this service (e.g. API-key grace-period revocation, IGDD-2711) and is
acceptable for a "blocked until cleared by support" style lockout.

## Manual remediation — a sender was wrongfully deny-listed

**There is currently no admin API to remove an arbitrary sender from the deny list** (as opposed to
the source-attack exception, which only prevents *future* deny-list adds). `removeUserFromDenyList`
exists on `AccessControlService` but its only caller today is `LogController`'s test-reset endpoint,
which unblocks the *calling* identity only — it cannot be used to unblock a different sender.

Until a follow-up adds a general-purpose deny-list admin endpoint:

1. Confirm the sender is deny-listed (a `SourceAttackExceptionRecord`-style admin read doesn't exist
   for the deny list itself; check application logs for the
   `"{sender} added to deny list after a source attack"` ERROR-level message, or query the
   `DenyListRecord` entity type directly in DynamoDB).
2. Create a source-attack exception for the sender via the API above, so it is not re-added on a
   repeat detection.
3. Remove the `DenyListRecord` item directly in DynamoDB (partition key `DenyListRecord`, sort key
   `{environment}#{sender}`), or engage engineering to do so.
4. **Recommended follow-up**: add `GET`/`DELETE` admin endpoints for the deny list itself, mirroring
   the ones this change adds for exceptions — today neither exists.

## Log signals

| Message (INFO/WARN/ERROR) | Meaning |
|---|---|
| `Source attack lockout is disabled; {sender} was not added to the deny list` | Detection fired but the master flag is off |
| `{sender} has a configured source-attack exception; not adding to deny list` | Detection fired but the sender is exempted |
| `{sender} added to deny list after a source attack: {reason}` (ERROR) | The sender was actually locked out — the real trigger for on-call follow-up |
