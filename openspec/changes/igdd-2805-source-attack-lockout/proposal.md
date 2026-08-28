## Why

[IGDD-2805](https://support.izgateway.org/browse/IGDD-2805) — During L3 on-call (2026-04-30), multiple
"Source Attack Exception" emails fired for a VHA submission. Investigation showed the trigger was a
legitimate patient whose legal name contains the word "javascript" — not an injection attack.
`gov.cdc.izgateway.soap.net.SoapMessageReader` (in the shared `izgw-core` library) correctly detected
the pattern and threw `SecurityFault.sourceAttack(...)` (fault code 61), which is why the alert fired.

But the sender was never actually shut out, which is a second, independent bug: nothing in izgw-hub
calls `AccessControlService.addUserToDenyList(user, reason)` in response to a source-attack fault.
That method is fully wired to the DynamoDB-backed deny list and enforcement already works correctly
once a sender *is* on the list (`AccessControlValve` → `checkAccess` → `isUserDenied`) — it simply has
zero callers today. `removeUserFromDenyList` has exactly one caller (`LogController`'s test-reset
endpoint), confirming the deny-list plumbing exists and is exercised, just never triggered by a source
attack.

This is a fortunate bug — VHA was not locked out — but it means the "Source Attack Exception" mechanism
provides alerting without enforcement. A real compromised sender would also not be shut out today.
Fixing the lockout without also adding an exception mechanism would make the VHA false positive (and
similar sender/receiver-specific keyword collisions) a recurring on-call disruption, so both parts of
this ticket ship together: fix the lockout, and give operators a no-deploy way to exempt known
legitimate keyword matches per sender/receiver.

## What Changes

- **Added**: `BaseGatewayController` overrides `SoapControllerBase.handleFault(Fault)` to detect a
  source-attack `SecurityFault` (fault code `"61"` specifically — `userBlacklisted` is code `62` and
  also carries a non-null `endpoint`, so code is the correct discriminator) and add the sender to the
  DynamoDB deny list via the existing `AccessControlService.addUserToDenyList(user, reason)`.
- **Added**: sender identity for this check comes from `RequestContext.getSourceInfo().getCommonName()`
  — the same identity `LogController` already uses for `removeUserFromDenyList` — not from
  `SecurityFault.getEndpoint()`, which is `null` for inbound requests in the current code path (only
  populated for outbound destination-response scanning in `MessageSender`).
- **Added**: a new master switch, `hub.source-attack-lockout.enabled` (default `false`), gating the new
  auto-lockout-on-detection behavior. This is distinct from the existing `security.blacklist.disabled`
  / `security.enable-blacklist` flags, which gate deny-list *enforcement* in general, not this specific
  new behavior. Default-off means shipping this change does not alter production behavior until an
  operator explicitly opts in.
- **Added**: a new DynamoDB-backed, no-deploy-required exception mechanism (capability:
  `source-attack-exception-config`) letting operators register a **sender** as exempt from
  auto-lockout. When a match is found, `addUserToDenyList` is skipped and the exemption use is logged;
  the individual triggering message is still rejected with the `SecurityFault` as today (this change
  does not alter the pattern-scanning/detection logic in `izgw-core`, and does not let a flagged
  message through). Per-**receiver** scoping (the "and/or per-receiver" part of the ticket) is not
  enforceable at the point this fix hooks in — the destination isn't reliably known there without an
  izgw-core change — so it's an explicit, documented follow-up rather than a half-working knob; see
  `design.md` D5/D6 for why storing an unenforced `destId` was rejected as a security footgun.
- **Added**: an admin-only REST surface to create/list/remove exception entries, since no such CRUD
  exists today for either the deny list or (necessarily) its exceptions.
- **Documented**: the new flag, the exception data model, and the admin API in the operator runbook.

## Capabilities

### New Capabilities

- `source-attack-exception-config`: per-sender exemption from source-attack auto-lockout, administered
  via REST without a code deployment. Per-receiver scoping is a documented follow-up, not built here
  (see `design.md` D5/D6).

### Modified Capabilities

- `access-control`: `AccessControlService`/`BaseGatewayController` gain an enforcement path that adds a
  sender to the existing deny list when a source-attack `SecurityFault` is raised and the sender has no
  matching exception, gated by a new default-off flag.

## Impact

- **`BaseGatewayController`** (`gov.cdc.izgateway.hub`) — new `handleFault` override; no change to
  `checkAccess`/`checkCredentials`/message routing.
- **`AccessControlService`** (`gov.cdc.izgateway.hub.service.accesscontrol`) — new method(s) to look up
  and administer source-attack exceptions; existing `addUserToDenyList`/`removeUserFromDenyList` gain
  their first real caller.
- **`RepositoryFactory` / `dynamodb.model` / `dynamodb.repository`** — new table/model/repository pair
  for exception records, mirroring the existing `DenyListRecord` pattern (DynamoDB for prod, JPA for CI
  per `SPRING_DATABASE`).
- **New admin controller (or extension of an existing one)** — REST CRUD for exception records,
  `@RolesAllowed` admin-gated.
- **`izgw-core`** — **not modified.** `SoapMessageReader`'s detection logic and `SecurityFault` are
  unchanged; this proposal deliberately keeps the entire fix inside izgw-hub (see Alternatives).
- **FIPS impact** — none; no cryptographic or TLS code touched.
- **PHI exposure risk** — none introduced. The sender identity used for deny-listing is the mTLS
  common name (already logged today for every request); the flagged message content (which may contain
  the patient name that triggered the false positive) is not persisted by this change beyond the fault
  detail message it already produces today.
- **Backward compatibility** — none by default (`hub.source-attack-lockout.enabled=false`). Once
  enabled, any sender that previously triggered source-attack faults without consequence will now be
  deny-listed unless an exception is configured first; this is a deliberate behavior change and should
  be called out in rollout communication.
- **Performance impact** — negligible. The new logic only runs on the already-rare source-attack fault
  path (one DynamoDB read for the exception check, one write for the deny-list add), not on the
  high-throughput message-routing path.

## Alternatives Considered

- **Let the exempted message through instead of just suppressing the lockout.** Rejected: this would
  require changing the pattern-scanning logic in `SoapMessageReader` (izgw-core), a separately
  versioned/published shared library, turning a hub-local fix into a coordinated cross-repo change for
  marginal benefit — the sender can simply resend without the flagged content once exempted. Confirmed
  with stakeholder (2026-08-26): exceptions should only prevent the sender-wide shutout.
- **Reuse the legacy `AccessControl` (category/name/member) DynamoDB model for exceptions.** Rejected:
  that model is old-model-only (`OldModelHelper` territory, pre-migration) and new features should
  target the DynamoDB-native `NewModelHelper`-era pattern instead (see `DenyListRecord`).
- **Gate the new behavior with the existing `security.enable-blacklist` flag.** Rejected: that flag
  controls deny-list enforcement broadly (including manually-added entries); conflating it with
  auto-lockout-on-detection would surprise any environment already relying on manual blacklisting.
