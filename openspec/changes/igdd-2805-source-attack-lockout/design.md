## Context

`SoapMessageReader` (izgw-core) throws `SecurityFault.sourceAttack(detail, endpoint)` (fault code `"61"`)
when inbound SOAP XML contains `script`/`javascript` in an element name, text value, or attribute value.
For an inbound request this happens inside Spring's `HttpMessageConverter` machinery
(`SoapMessageConverter.read(Class, HttpInputMessage)` → the 1-arg overload, which always calls the
2-arg `read(message, endpoint)` with `endpoint = null`), **before** `HubWSDLController`/
`CDCWSDLController`'s controller method body ever runs. The resulting `SoapConversionException`
(cause = the `SecurityFault`) is caught by `SoapControllerBase.handleBadXML` (`@ExceptionHandler`,
izgw-core), unwrapped, and passed to `handleFault(Fault)`, which today only logs and returns the fault
to the caller.

`AccessControlService.addUserToDenyList(user, reason)` already exists, is wired to a DynamoDB-backed
deny list, and is already enforced correctly (`AccessControlValve` → `checkAccess` →
`currentModelHelper.isUserDenied`) — it just has no caller. Fixing the bug means calling it from the
right place with the right identity, gated by a default-off flag, and only when no exception applies.

**A real constraint that shapes this design:** at the point `handleFault` runs, the inbound SOAP body
failed to fully parse, so the `DestinationId` the sender was trying to reach is often **not
reliably known** in izgw-hub. `SoapMessageReader` does track it internally as it streams the document
(it sets `req.getHubHeader().setDestinationId(...)` when it reaches that element), but that
partially-built object is local to the reader/converter call inside izgw-core and is discarded when the
exception propagates — it is never published to `RequestContext` or attached to the `SecurityFault`.
Since we've ruled out changing izgw-core for this ticket, per-receiver exceptions **cannot be reliably
enforced** at this hook. Confirmed with stakeholder (2026-08-27): this change scopes exceptions to
**sender only** — no `destId` field at all — rather than storing an unenforceable knob an operator could
misread as protective. Per-receiver scoping is an explicit, documented follow-up that requires an
izgw-core change (see D5).

## Goals / Non-Goals

**Goals:**
- Make a source-attack detection on an inbound request actually add the sender to the existing
  DynamoDB deny list, gated by a new default-off flag.
- Give operators a way to register a sender as exempt from that auto-lockout, updatable without a code
  deployment.
- Keep the entire change inside izgw-hub; no changes to izgw-core.
- Make the exemption match logic a pure, unit-testable function, independent of DynamoDB, following
  the precedent set by `AccessControlService.useTypeViolation` (IGDD-3257).

**Non-Goals:**
- Per-receiver (sender+destination) exception *enforcement*. The ticket says "per-sender and/or
  per-receiver," but destId isn't reliably available at the auto-lockout hook (see Context) without an
  izgw-core change, which is out of scope. This change ships sender-only exceptions; per-receiver
  scoping is a documented follow-up (D5).
- Letting the flagged message itself through — the sender's message is still rejected with the
  `SecurityFault` today; an exception only prevents the sender-wide deny-list add. (Confirmed with
  stakeholder, 2026-08-26.)
- Changing `SoapMessageReader`'s detection patterns or adding a discriminator to `SecurityFault` in
  izgw-core.
- Auto-lockout for the **outbound** source-attack scan (`MessageSender` scanning an IIS's *response*
  for attack patterns, `SecurityFault.sourceAttack` called with a real, non-null `RequestContext
  .getDestinationInfo()` endpoint). This *does* reach the same `handleFault` override (verified — see
  D2b), so it must be, and is, explicitly excluded via the `endpoint == null` guard rather than assumed
  unreachable. It protects against a different actor (a compromised destination IIS returning malicious
  content, not a misbehaving sender), and the ticket's scenario (VHA, a sender) doesn't call for
  deny-listing destinations. Auto-lockout of a misbehaving *destination* is a candidate follow-up, not
  built here.
- A Config Console UI for managing exceptions. This change delivers the DynamoDB model, repository,
  and an admin REST API; a UI is a separate, later effort (izg-configuration-console repo).
- Any change to `security.blacklist.disabled` / `security.enable-blacklist` semantics.
- Old-model (`OldModelHelper`, pre-migration) support for exceptions — this is new functionality built
  against the DynamoDB-native model only; `OldModelHelper.isExemptFromSourceAttackLockout` returns
  `false` unconditionally, consistent with how it already treats other new-model-only features (see
  `canAccessDestination`).

## Decisions

### D1 — Hook point: override `SoapControllerBase.handleFault(Fault)` in `BaseGatewayController`

`handleFault` is a plain `protected` method, not itself a Spring `@ExceptionHandler` — it's called
*from* the `@ExceptionHandler` method (`handleBadXML`) via ordinary virtual dispatch on `this`. Because
`HubWSDLController`/`CDCWSDLController extends BaseGatewayController extends SoapControllerBase`,
overriding it in `BaseGatewayController` is picked up by normal Java polymorphism for both controllers,
with no dependency on Spring's `@ExceptionHandler`-resolution-across-a-class-hierarchy semantics and no
change to izgw-core:

```java
// BaseGatewayController
@Override
protected ResponseEntity<FaultMessage> handleFault(Fault fault) {
    if (fault instanceof SecurityFault sf
            && SecurityFault.SOURCE_ATTACK_CODE.equals(sf.getCode())
            && sf.getEndpoint() == null) {          // see D2b — excludes the outbound/destination-response case
        accessControlService.handleSourceAttack(
            RequestContext.getSourceInfo().getCommonName(),
            sf.getDiagnostics()   // human-readable detail from SoapMessageReader, used as the deny-list reason
        );
    }
    return super.handleFault(fault);
}
```

(`SecurityFault.SOURCE_ATTACK_CODE` doesn't exist yet — izgw-core's `SecurityFault` hardcodes `"61"`
as a literal inside its `MESSAGE_TEMPLATES` array with no public constant. Since we're not touching
izgw-core, this change instead defines its own private constant in `BaseGatewayController`, documented
with a comment pointing at `SecurityFault`'s `MESSAGE_TEMPLATES[1]` as the source of truth, and a unit
test that asserts a real `SecurityFault.sourceAttack(...)` instance's `getCode()` equals it — so a
future izgw-core change to that literal fails our test instead of silently breaking detection.)

**Alternative considered:** Override the `@ExceptionHandler`-annotated `handleBadXML` directly. Rejected
— it requires relying on Spring's method-override annotation-inheritance behavior for
`@ExceptionHandler` resolution across a controller's class hierarchy, which is less obviously correct
than plain virtual dispatch on a non-framework method, for no benefit (we don't need anything
`handleBadXML` has that `handleFault` doesn't).

**Testing note:** this hook only works if `SoapControllerBase.handleBadXML` (controller-local
`@ExceptionHandler(SoapConversionException.class)`) actually wins over the global
`ExceptionHandling`/`@ControllerAdvice` and then virtually dispatches into the overridden
`handleFault`. That chain should be proven with a Spring MVC integration test (a submit with an
attack-pattern payload, asserting the sender ends up on the deny list), not just reasoned about.
Equally important is the **negative** test for D2b: simulate (or directly unit-test
`handleFault`/`handleSourceAttack` with) a `SecurityFault.sourceAttack` carrying a non-null endpoint
(the outbound shape) and assert the original sender is **not** added to the deny list. Both are in
tasks.md.

### D2 — Discriminate on fault **code**, not on `endpoint != null`, to pick out source-attack specifically

`SecurityFault.userBlacklisted(...)` (code `"62"`) also passes a non-null `endpoint`, so `endpoint !=
null` alone would incorrectly match both cases. `getCode()` (`"61"` vs `"62"`) is the correct
discriminator for *which kind* of security fault this is, and is already public API on `Fault`.

### D2b — Additionally require `endpoint == null`, to exclude the outbound/destination-response case

Verified (not just assumed) that a source-attack fault can occur on the **outbound** path too, and that
it reaches this same `handleFault` override: `MessageSender.sendSubmitSingleMessage` (izgw-core) calls
`converter.read(m, endPoint)` with `endPoint = RequestContext.getDestinationInfo()` to scan an IIS's
*response* for attack patterns. That call sits inside `BaseGatewayController.submitSingleMessage()`'s
execution (not before it), so if it throws, the resulting `SoapConversionException` propagates out of
the controller method body the same way any other exception would, and is caught by the same
`@ExceptionHandler(SoapConversionException.class)` (`handleBadXML`) → `handleFault` chain as the inbound
case. At that point `RequestContext.getSourceInfo().getCommonName()` is still the **original sender**
who submitted the request to Hub — not the destination whose response actually contained the attack
pattern. Without a guard, this override would wrongfully deny-list an innocent sender because a
*destination* IIS sent back suspicious content — the exact wrongful-lockout failure mode this ticket
exists to eliminate, just pointed at the wrong party.

The fix is the same discriminator already established for identity (D3): for an inbound request,
`sf.getEndpoint()` is `null` (Spring's standard `read(Class, HttpInputMessage)` always calls the 2-arg
overload with `null`); for the outbound scan, `sf.getEndpoint()` is the non-null destination endpoint.
Requiring `sf.getEndpoint() == null` in addition to the code check restricts auto-lockout to the inbound
case only. This is enforced in code (the guard), not just documented as a non-goal, and a negative test
proves it (see Testing note under D1 and `specs/access-control/spec.md`).

### D3 — Sender identity: `RequestContext.getSourceInfo().getCommonName()`, not `SecurityFault.getEndpoint()`

For inbound requests, `SecurityFault.getEndpoint()` is `null` today (see Context) — using it would make
the fix silently do nothing. `RequestContext.getSourceInfo().getCommonName()` is the mTLS-cert-derived
identity already populated for every request before body parsing begins (it's how
`AccessControlValve`/`AccessControlService.isUserDenied` key deny-list entries), and it's the same
accessor `LogController` already uses when calling `removeUserFromDenyList`. Because of D2b, this
accessor is only ever consulted for the inbound (`endpoint == null`) case, so it always resolves to the
sender who actually submitted the request being processed — never to a destination.

### D4 — New method `AccessControlService.handleSourceAttack(String sender, String reason)`

Centralizes the policy decision (flag check → exception check → deny-list add → structured log) in
`AccessControlService`, next to `addUserToDenyList`/`checkAccessToDestination`/
`checkUseTypeAccessToDestination`, rather than spreading security policy into
`BaseGatewayController`. Sketch:

```java
@Value("${hub.source-attack-lockout.enabled:false}")
private boolean sourceAttackLockoutEnabled;

public void handleSourceAttack(String sender, String reason) {
    if (!sourceAttackLockoutEnabled) {
        log.warn(Markers2.append("sender", sender), "Source attack lockout is disabled; {} was not added to the deny list", sender);
        return;
    }
    if (currentModelHelper.isExemptFromSourceAttackLockout(sender)) {
        log.info(Markers2.append("sender", sender), "{} has a configured source-attack exception; not adding to deny list", sender);
        return;
    }
    addUserToDenyList(sender, reason);
    log.error(Markers2.append("sender", sender), "{} added to deny list after a source attack: {}", sender, reason);
}
```

### D5 — Exception record shape: sender only (confirmed with stakeholder, 2026-08-27)

New DynamoDB entity `SourceAttackExceptionRecord` (mirrors `DenyListRecord`'s
`DynamoDbAudit`/`DynamoDbEntity` pattern):

| Field | Type | Notes |
|---|---|---|
| `sender` | `String` | mTLS common name, required |
| `reason` | `String` | free-text operator justification |
| `environment` | `int` | `SystemUtils.getDestType()`, same per-environment scoping as `DenyListRecord` |
| (inherited) `createdBy`, `createdOn` | — | audit fields from `DynamoDbAudit` |

`getPrimaryId()`: `"{environment}#{sender}"` — identical shape to `DenyListRecord`'s own
`getPrimaryId()`. Partition key is the fixed entity type name (`SourceAttackExceptionRecord`), per
`DynamoDbRepository`'s single-table convention.

No `destId` field. `destId` is not reliably available at the `handleFault` call site (see Context), and
storing a field the auto-lockout check can't honor would be a security footgun: an operator could
create a destination-scoped exception, believe a sender is protected against lockout for that
destination, and be wrong — the exact false-confidence failure mode this ticket exists to fix.

### D6 — Per-receiver scoping is an explicit, documented follow-up (not built here)

`isExemptFromSourceAttackLockout(sender)` takes only `sender`. The ticket's "per-sender and/or
per-receiver" language is satisfied for the sender case now; per-receiver requires izgw-core to capture
the partially-parsed `DestinationId` (from `req.getHubHeader()`, tracked inside `SoapMessageReader`
today but discarded on throw) and attach it to `SecurityFault.sourceAttack(...)` or to `RequestContext`
before throwing. That's a coordinated cross-repo change (izgw-core version bump/publish +
izgw-hub consumption) and is out of scope for this change. Recorded here so it isn't lost, and so a
future ticket can pick it up without re-deriving the constraint.

**Alternative considered:** Store `destId` now as informational/audit-only metadata, clearly documented
as unenforced. Rejected (stakeholder decision, 2026-08-27) — the risk of an operator misreading an
unenforced field as protective outweighs the audit-trail benefit; simpler to add the field later
alongside real enforcement than to carry a field that means nothing today.

### D7 — Caching: mirror `NewModelHelper.denyListRecordCache`

`NewModelHelper` gains `Map<String, SourceAttackExceptionRecord> sourceAttackExceptionCache`, keyed by
`sender` (one record per sender, no list needed now that `destId` is gone), refreshed alongside the
other caches in `refresh()` via the same `refreshCache(repo, SourceAttackExceptionRecord::getSender)`
helper already used for `accessGroupCache`/`fileTypeCache`. Expected cardinality is tiny (a handful of
known false-positive senders). `isExemptFromSourceAttackLockout(sender)` is a single
`cache.containsKey(sender)` check.

### D8 — Admin API surface

There is currently **no** REST CRUD for deny-list or exception-style records anywhere in izgw-hub — the
deny list today is populated only by one-time CSV migration (`AccessControlMigrator`) or (after this
change) automatically on source attack; `LogController`'s single `removeUserFromDenyList` call is
test-reset-only. Since the acceptance criteria require configuring exceptions **without a code
deployment**, and directly editing the DynamoDB table via AWS console/CLI is possible but leaves no
audit trail, isn't role-gated the same way as everything else in the app, and is more error-prone
during an on-call incident, this change adds a small `@RolesAllowed({ Roles.ADMIN })` REST surface
(new controller or an addition to an existing admin-facing one — finalized in tasks.md) for
create/list/delete of exception records, calling straight through to `AccessControlService`. This
mirrors how every other admin-managed record in the DynamoDB-native model is intended to be
administered (role-gated REST, not raw table edits), even though today's `AccessGroup`/`AllowedUser`
predate any such surface.

## DynamoDB Access Patterns

Single-table design (per `DynamoDbRepository`): partition key = entity type name, sort key =
`getPrimaryId()`.

| Access pattern | Key condition | Used by |
|---|---|---|
| Load all exceptions for the current environment (cache refresh) | `PK = "SourceAttackExceptionRecord"`, `findAllForEnvironment()` → filters `environment#` prefix | `NewModelHelper.refresh()` |
| Check sender's exception | in-memory cache lookup by `sender` key | `AccessControlService.handleSourceAttack` (via `isExemptFromSourceAttackLockout`) |
| Create/replace an exception | `PutItem`, key `{environment}#{sender}` | admin REST `POST` |
| Delete an exception | `DeleteItem`, same key | admin REST `DELETE` |
| List exceptions (admin visibility) | cache values (`findAllForEnvironment()`) | admin REST `GET` |

No new GSI needed at expected cardinality (mirrors `DenyListRecord`'s no-GSI, full-cache-scan-on-refresh
approach).

## Error Handling Mapping

| Exception | SOAP Fault | HTTP Status | Retry Strategy | New behavior |
|---|---|---|---|---|
| `SecurityFault.sourceAttack` (code 61) | `SecurityFault` | 500 (via `RetryStrategy.CONTACT_SUPPORT.getStatus()`, unchanged) | `CONTACT_SUPPORT` | **New:** triggers `handleSourceAttack` side effect before the (unchanged) fault response is returned |
| `SecurityFault.userBlacklisted` (code 62) | `SecurityFault` | 500 | `CONTACT_SUPPORT` | Unchanged — explicitly excluded from the new code-61 check |
| Any other `Fault` | unchanged | unchanged | unchanged | Unchanged — `handleFault` override no-ops and delegates to `super.handleFault` |

## Access Control

**Verified (not assumed): the shutout actually shuts senders out.** `AccessControlService.checkAccess`
treats a denied user as still-allowed on any endpoint whose *registered* roles include
`IAccessControlRegistry.BLACKLIST_ROLE` (`return roles.contains(BLACKLIST_ROLE);` when `isUserDenied`)
— that's the mechanism `LogController`'s test-reset endpoint relies on to stay reachable for a
blacklisted test user. Checked `HubWSDLController`/`CDCWSDLController`: both are annotated
`@RolesAllowed({ Roles.SOAP, Roles.ADMIN })` at the class level, which `AccessControlRegistry.register`
uses to populate `getAllowedRoles("/IISHubService"|"/izgw", ...)`. `BLACKLIST_ROLE` ("`blacklist`") is
not among them, so `checkAccess` returns `false` (not `true`) for a deny-listed sender hitting either
SOAP endpoint, and `AccessControlValve` rejects with 401. The fix in this change therefore does produce
a real shutout, not just a DynamoDB row — proven by the second-request assertion in tasks.md 4.4, not
just inferred from this inspection.

New admin endpoints are `@RolesAllowed({ Roles.ADMIN })` only (not `OPERATIONS`/`BLACKLIST`, unlike
`LogController`'s reset endpoint) — creating a bypass for source-attack lockout is a more sensitive
action than resetting a test user's blacklist flag, so it gets the narrower role. `Application
.isAdministrator()` (localhost) callers bypass as usual, consistent with existing admin-endpoint
behavior.

## Risks / Trade-offs

- **Outbound source-attack faults could wrongfully deny-list the original sender if the `endpoint ==
  null` guard (D2b) is ever removed or bypassed** — this is the single highest-severity risk in this
  change: getting it wrong means Hub actively locks out an innocent sender because a *destination* IIS
  sent back suspicious content. Mitigated by making the guard an explicit, tested condition (not an
  assumption) — see the negative test in D1's Testing note.
- **Per-receiver exceptions are not supported at all (D5/D6)** — an operator who wants to exempt a
  sender only for a specific destination cannot express that; they can only exempt the sender entirely.
  Mitigated by treating this as a known, documented gap (runbook) rather than a partially-working
  feature; a future izgw-core change is the tracked path to closing it.
- **Enabling `hub.source-attack-lockout.enabled` for the first time will retroactively start
  deny-listing sender/keyword collisions that were previously silent** — mitigated by requiring
  operators to review known false positives (like VHA's) and pre-populate exception records for those
  senders before flipping the flag on in an environment that has seen prior false-positive alerts.
- **`SecurityFault`'s fault-code-61 literal is not a public constant in izgw-core** — mitigated by a
  unit test asserting the literal against a real `SecurityFault.sourceAttack(...)` instance (D1), so a
  future izgw-core change to that value fails loudly here instead of silently disabling the fix.
- **New DynamoDB table requires infrastructure provisioning** (or a new item-type within the existing
  single table, per the single-table design already in use — no new *table*, just new items under the
  existing table with a new partition-key value) — low risk, same mechanism as every other entity in
  `dynamodb.model`.
- **Multi-instance cache propagation lag.** Hub runs multiple instances behind the ALB, each with its
  own `NewModelHelper` in-memory caches. `addUserToDenyList` (and a new exception create/delete) update
  the acting instance's cache immediately via `denyListRecordCache.put(...)` /
  `sourceAttackExceptionCache` update, but other instances only see the change on their next `refresh()`
  (`refreshPeriod`, default 300s) or refresh-queue trigger. For a "blocked until cleared by support"
  lockout this lag is acceptable — the same convergence-over-broadcast trade-off already accepted for
  API-key grace-period revocation (IGDD-2711) — but it means a sender can still reach a *different*
  instance for up to that window after being deny-listed, and a freshly-created exception can take up to
  that same window to suppress lockout fleet-wide. Worth a one-line callout in the runbook.

### PHI verification (confirmed, not just asserted)

Checked all three `SecurityFault.sourceAttack(...)` call sites in `SoapMessageReader`:
- Text-value match (`Strings.CI.contains(elementText, TEXT_VALUE_PATTERN)`): detail is
  `"Illegal text value in {type} inside: <{lastElement}> element"` — includes the **element name**,
  never `elementText` itself.
- Element-name match (`Strings.CI.contains(localName, TAG_NAME_PATTERN)`): detail includes
  `localName` (the tag name) — not patient/message data.
- Attribute-value match (`Strings.CI.contains(text, TEXT_VALUE_PATTERN)`): detail is
  `"Illegal attribute value in {type} at: <{lastElement} {attrName}="` — includes the **attribute
  name**, never the matched attribute value `text` itself.

None of the three include the actual flagged value (e.g., the patient name that triggered the VHA false
positive) in the fault detail. `sf.getDiagnostics()` is therefore safe to persist as the deny-list/audit
`reason` and to log, consistent with prod-mode PHI masking expectations.

## Migration Plan

1. **Pre-deployment**: none required — `hub.source-attack-lockout.enabled` defaults to `false`, so
   deploying this change alone is a no-op in production.
2. **Enablement (per environment, operator-driven)**:
   a. Review recent "Source Attack Exception" alert history for known false-positive senders (e.g.
      VHA).
   b. Create an exception record for each via the new admin API.
   c. Set `hub.source-attack-lockout.enabled=true` for that environment.
3. **Rollback**: set the flag back to `false` (no redeploy needed if externalized as an env var) or
   redeploy without it. No destructive DynamoDB schema change means rollback is clean; exception records
   left behind are inert while the flag is off.

## Open Questions

- **Exact admin controller placement** — new dedicated controller vs. an addition to an existing one.
  Deferred to tasks.md; leaning toward a new small controller (`SourceAttackExceptionController`) given
  there's no existing access-control CRUD controller to extend.
- **Should `reason` be required on create?** Leaning yes (mirrors `addUserToDenyList`'s required
  `reason` parameter) — confirm during task breakdown.
