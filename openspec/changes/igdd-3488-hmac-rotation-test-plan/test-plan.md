# Test Plan: HMAC JWT Signing Secret Rotation (IGDD-3488)

**Type:** Test plan only. Validates the rotation procedure and decision already
documented in spike IGDD-3294 (`openspec/changes/igdd-3294-hmac-secret-rotation/design.md`,
`runbook.md`) — this ticket does not re-derive that decision or introduce any code change.
Per IGDD-3294's conclusion, no Hub or Console code change is required for rotation, so
this plan is entirely operational/manual verification against a real dev Secrets Manager
secret, not new automated test code.

**Epic:** IGDD-2702 (API Key use)

## Why this plan is needed

Every existing automated test that exercises JWT validation
(`ApiKeyPrincipalProviderTests`) sets `JwtConfig.testSecret`. `ApiKeyPrincipalProvider
.resolveSecret(kid)` checks that field first and, if set, returns it directly
(`ApiKeyPrincipalProvider.java:250-251`) — **before** ever calling Secrets Manager. This
bypass exists so unit tests don't need live AWS access, but it means the actual
kid-based, multi-version Secrets Manager lookup that IGDD-2705 shipped and that IGDD-3294
depends on has **never been exercised by any test, automated or manual**, in this
codebase. This plan targets exactly the code path that bypass hides.

## Scope

In scope: manual/operational verification, against a real dev Secrets Manager secret and
real Console-issued tokens, that:
- a token signed under an older secret version keeps authenticating after one rotation,
- it keeps authenticating after a **second** rotation (the scenario IGDD-3294's design.md
  flags as "never observed... the secret has, as far as I can tell, never been rotated in
  dev"),
- the negative-cache/rejection paths behave as documented,
- rollback works.

Out of scope:
- Any code change (none required — IGDD-3294 decision).
- Writing new automated tests. A real coverage gap exists (see "Follow-up" below) but
  closing it is separate work, not this ticket.
- Multi-region verification — blocked on IGDD-3489 confirming whether/how the secret is
  replicated.
- Alerting verification — blocked on IGDD-3490 (no alerting exists yet to verify).

## Environment / preconditions

- Dev Hub instance authenticating against the real dev Secrets Manager secret (i.e. NOT
  running with `testSecret` configured — confirm dev's `application.yml`/env does not set
  `jwt.test-secret` before starting).
- AWS CLI access to the dev secret with `secretsmanager:GetSecretValue`,
  `PutSecretValue`, `UpdateSecretVersionStage`, and `ListSecretVersionIds`.
- Ability to mint API-key JWTs via Config Console's `/token` endpoint in dev.
- Note the two cache TTLs before designing timing around any case: Hub's per-`kid` secret
  cache is 1h (`JwtConfig.secretCacheTtl` default — `JwtConfig.java:24`); the negative
  cache for an unresolvable `kid` is 60s (`ApiKeyPrincipalProvider
  .NEGATIVE_SECRET_CACHE_TTL` — line 41). A case that expects Hub to notice a change
  immediately, or expects a rejection to still be cached, must account for these.

## Test cases

### TC1 — Baseline: valid token authenticates pre-rotation
Mint a token via Console `/token` (kid = current `VersionId`, call it v1). Submit to
Hub. **Expected:** authenticates successfully. Establishes the starting state before any
rotation in this cycle.

### TC2 — First rotation: v1 token still authenticates after rotating to v2
Follow the runbook: note v1's `VersionId`, label it, then `PutSecretValue` to create v2
(`AWSCURRENT`). Mint a new token (should carry kid=v2) and confirm it authenticates.
Re-submit the original v1 token from TC1.
**Expected:** v2 token authenticates; v1 token *also* still authenticates, with no Hub
restart or deploy.
**Fail signal:** v1 token rejected with `"JWT rejected: unable to resolve signing secret
for kid=<v1>"` (`ApiKeyPrincipalProvider.java:132`) — this is precisely the failure mode
IGDD-3294 was opened to prevent.

### TC3 — Second rotation: v1, v2, and v3 tokens all still authenticate
This is the case IGDD-3294's design.md explicitly flags as never having been observed.
Repeat the runbook procedure: label v2 (now `AWSPREVIOUS`) *before* writing v3. Mint a v3
token. Re-submit the v1 and v2 tokens from TC1/TC2.
**Expected:** all three tokens (v1, v2, v3) authenticate.
**Fail signal:** v1 or v2 rejected with `"Secrets Manager: no version found for
kid=<...>, caching negative result"` (line 276, `ResourceNotFoundException`) — would mean
a labeled version was deleted or the label step was skipped.

### TC4 — Unknown/garbage kid is rejected and negatively cached
Submit a token with a `kid` that is not a real `VersionId` (e.g. a random UUID), twice
within 60s.
**Expected:** both rejected. First attempt logs `"Secrets Manager: no version found for
kid=<bogus>, caching negative result"`. Confirm (via log volume, or Secrets Manager
call-count if visible in dev) that the second attempt within the 60s window does not
repeat the live Secrets Manager call — served from the negative cache.

### TC5 — Missing `kid` header is rejected without calling Secrets Manager
Submit a token signed with no `kid` in its header at all.
**Expected:** rejected immediately with `"JWT rejected: missing kid header"`
(`ApiKeyPrincipalProvider.java:126-129`). This path returns before `resolveSecret` is
ever invoked — confirm no Secrets Manager call happens for this case, distinguishing it
from TC4.

### TC6 — Rollback restores the prior version
Simulate an operator mistake: rotate to a deliberately wrong new secret value. Before any
client picks it up, run the runbook's rollback step to move `AWSCURRENT` back to the
previous (already-labeled, from TC2/TC3) `VersionId`. Mint a token and confirm it
authenticates.
**Expected:** rollback succeeds immediately; no risk of the target version having been
deleted in the interim, because it was already labeled.

### TC7 — Releasing a retained label doesn't disturb other still-valid kids
Best-effort / partially unverifiable in a dev timeframe — call this out explicitly rather
than treating it as a clean pass/fail. Remove a retained version's `RETAIN-<date>` label
per the runbook's release step. Confirm all *other* currently labeled/current versions
keep authenticating normally. Do not expect immediate deletion of the now-unlabeled
version — Secrets Manager's actual cleanup is count-based (>100 versions) and time-gated
(never <24h old), not immediate, so this case can only confirm "no side effect on other
versions," not "the released version was purged."

## Follow-up (not built here)

- **Automated coverage gap:** add unit tests for `ApiKeyPrincipalProvider.resolveSecret`
  against a mocked `SecretsManagerClient` (valid kid, unknown kid + negative cache, cache
  hit avoiding a repeat call) so this behavior stops depending entirely on manual runs of
  this plan. Recommended as separate follow-up work, not part of this ticket's scope.

## Evidence index

| Claim | Source |
|---|---|
| `testSecret` bypasses `resolveSecret`/Secrets Manager entirely | `ApiKeyPrincipalProvider.java:250-251` |
| All current unit tests use `testSecret` | `ApiKeyPrincipalProviderTests.java:54` |
| Secret cache TTL (1h default) | `JwtConfig.java:24` |
| Negative cache TTL (60s) | `ApiKeyPrincipalProvider.java:41` |
| Rejection log lines (unresolvable kid, missing kid header) | `ApiKeyPrincipalProvider.java:126-133, 276` |
| Rotation/rollback procedure this plan validates | `openspec/changes/igdd-3294-hmac-secret-rotation/runbook.md` |
