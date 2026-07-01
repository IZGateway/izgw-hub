## Context

Hub currently authenticates callers exclusively via mTLS client certificates (mTLS → `CertificatePrincipalProvider` → `UnauthenticatedPrincipal`). IGDD-2703 selected HS256 JWT as the alternative credential type. Config Console issues the JWTs; Hub validates them. The ADR's sequence diagrams and JWT structure specification are the authoritative design reference.

`izgw-core` already contains `JwtSharedSecretPrincipalProvider` (used by `izgw-transform`) but that provider decodes against a single static Base64 secret. Hub's design requires per-`kid` secret resolution — a different lookup model — so Hub implements its own `ApiKeyPrincipalProvider` rather than extending core's `AbstractJwtPrincipalProvider`.

## Goals / Non-Goals

**Goals:**
- Validate HS256 JWTs issued by Config Console with `kid`-based Secrets Manager lookup
- Cache secrets by `kid` and credentials by `jti` to minimize Secrets Manager and DynamoDB calls on the hot path
- Integrate revocation into Hub's existing SQS inter-instance refresh mechanism
- Preserve full backward compatibility with mTLS cert callers
- Support JWT-only clients (no TLS cert) via `client-auth=want` + `AuthenticationEnforcementFilter`

**Non-Goals:**
- Changes to `izgw-transform` (Hub-isolated change)
- `ApiKeyDomain` entity — Config Console's concern; Hub has no read/write to domain authorization records
- JWT issuance — Hub validates only; Config Console issues
- Egress IP binding (deferred, OQ2 in ADR)

> **Note:** Changes to `izgw-core` were initially out of scope. Task 12 adds OCSP revocation for the ALB header cert path, which required changes to `TrustManagerProvider` and `CertificatePrincipalProviderImpl` in `izgw-core`.

## Decisions

### D1 — Standalone `ApiKeyPrincipalProvider`, not extending `AbstractJwtPrincipalProvider`
`AbstractJwtPrincipalProvider.createDecoder()` is invoked once at startup and returns a `NimbusJwtDecoder` with a fixed key. The ADR requires per-`kid` Secrets Manager lookup on every cache miss — the decoder must select the key based on the JWT header, not a configuration property. Rather than forcing that into the Spring-decoder model, Hub implements `ApiKeyPrincipalProvider` directly using `com.nimbusds:nimbus-jose-jwt` (already on classpath via Spring Security) to parse and verify JWTs at the claim level.

`JwtTokenExtractor` from core is still reused for Bearer token extraction.

### D2 — Four separate Caffeine caches
- `secretCache`: keyed by `kid` (Secrets Manager version ID), value = `SecretString`, TTL = configurable (`jwt.secret-cache-ttl`, default 1 hour). Eliminates repeated SM calls for the same key version.
- `negativeSecretCache`: keyed by `kid`, value = `Boolean.TRUE`, TTL = 60 seconds. Prevents a flood of JWTs with invalid `kid` values from each triggering a Secrets Manager call; an unknown `kid` is retried at most once per minute.
- `credentialCache`: keyed by `jti`, value = `ApiKeyPrincipal`, TTL = configurable (`jwt.credential-cache-ttl`, default 5 minutes). Active credentials only.
- `revokedCache`: keyed by `jti`, value = `Boolean.TRUE`, TTL = 366 days (max possible token lifetime). Used for credentials explicitly revoked or found inactive in DynamoDB. Long TTL ensures a revoked token cannot slip through even after a re-authentication attempt.
- `absentCache`: keyed by `jti`, value = `Boolean.TRUE`, TTL = `credentialCacheTtl` (5 minutes). Used when DynamoDB has no record for the `jti`. Shorter TTL than `revokedCache` to allow a credential record to be created after a cold-cache miss without permanently locking out the caller.

The original design described a single `credentialCache` with a `Boolean.TRUE` REVOKED sentinel for all non-active cases. During implementation this was split into four caches to give absent and revoked cases different TTLs and to keep negative SM results separate from credential state.

### D3 — `ApiKeyPrincipal` carries `upn`, `jti`, and `jurisdictionId` from JWT claims
`jurisdictionId` (from `sub`) and `roles` come from JWT claims — not from DynamoDB. DynamoDB is checked only for `status` (active/revoked/absent). `upn` (User Principal Name — the DNS domain validated at issuance) is the **stable sender identity**: it is set as `IzgPrincipal.name` and flows into `SourceInfo.commonName`, making it directly equivalent to the CN extracted from an mTLS certificate. `jti` is carried so the principal can be targeted for revocation without re-parsing the token and is exposed via `getSerialNumberHex()` only.

The `sub` claim carries the jurisdictionId as a **string representation of an integer** (e.g., `"42"`), consistent with the legacy IZG jurisdiction identifier scheme. It is stored as a `String` throughout — no numeric parsing is performed — and is used for informational/audit purposes only. Authorization decisions use `upn`, not `sub`.

`ApiKeyPrincipal` extends `IzgPrincipal` directly (as `CertificatePrincipal` does), not via `JWTPrincipal`, because it needs the extra `upn` and `jti` fields and its claim-extraction logic differs.

### D8 — `upn` as the authorization identity, not `jti`
`IzgPrincipal.getName()` is the identity used by `AccessControlService.checkAccessToDestination()`, the `AllowedUser` per-destination lookup, the deny list, and the positive access cache. For mTLS callers this is the certificate CN (a stable DNS hostname). For JWT callers it must be an equivalent stable identifier — `upn` — not `jti`, which is an ephemeral per-token UUID that changes on every issuance and can never match a static `AllowedUser` pattern.

`ApiKeyPrincipal` therefore sets `name = upn` and retains `jti` only on `getSerialNumberHex()` for revocation targeting. `organization` is set to `sub` (a numeric string jurisdiction ID, e.g., `"42"`). This alignment means `AllowedUser` entries written for mTLS clients (CN patterns like `immunize.example.gov` or `*.example.gov`) will match JWT clients presenting the same DNS domain in their `upn` claim without any changes to the access control table.

### D4 — `AuthenticationEnforcementFilter` for `client-auth=want`
When Hub is configured with `server.ssl.client-auth=want` (required to accept JWT-only callers who present no TLS cert), an anonymous TLS connection reaches the app and `HubPrincipalService` returns `UnauthenticatedPrincipal`. `AccessControlValve` in `want` mode would log but not block the request. `AuthenticationEnforcementFilter` (order `HIGHEST_PRECEDENCE`) intercepts before business logic and returns 401 immediately for any `UnauthenticatedPrincipal`. In production with `client-auth=need`, the filter is present but harmless — anonymous connections are blocked at TLS.

### D5 — Revocation propagates `jti` via existing SQS refresh mechanism
`DbController`'s `/rest/refresh?all=true` endpoint and `RefreshQueueService` already propagate refresh events to all Hub instances via SQS. Config Console calls this endpoint after marking a `jti` revoked. Hub's `ApiKeyPrincipalProvider` subscribes to the refresh event, evicts the `jti` from `credentialCache`, and inserts a REVOKED sentinel with TTL = max token lifetime — ensuring no subsequent cache miss can re-validate the revoked credential. No new SQS topic or SNS resource is needed.

### D7 — `env` claim is a numeric integer, not a string
The JWT `env` claim carries the environment as a numeric ID matching `SystemUtils.getDestType()` (1=Production, 2=Testing, 3=Onboarding, 4=Staging, 5=Development) rather than the human-readable string name (e.g., `"Development"`). Hub parses the claim as a `Number` and compares it to `SystemUtils.getDestType()` directly. A missing or non-numeric `env` claim is rejected.

The numeric form is required for referential integrity with other IZG database tables that key on the numeric environment identifier. The string-name form used in the original ADR examples was an implicit choice that was not evaluated against the data model; this decision supersedes it. Config Console must emit the numeric ID when signing JWTs.

The `env` string passed internally to `ApiKeyCredentialRepository.findByEnvAndJti()` is `String.valueOf(envInt)` (e.g., `"5"`), so DynamoDB sort keys take the form `5#<jti>` rather than `Development#<jti>`.

### D6 — `jwt.test-secret` property bypasses Secrets Manager for local dev
When `jwt.test-secret` is set, `ApiKeyPrincipalProvider` uses that secret for all `kid` values instead of calling Secrets Manager. This allows local testing without AWS credentials. The property must not be set in non-local profiles.

### D9 — OCSP revocation for the ALB header cert path

When the ALB terminates mTLS and forwards the client certificate via the `x-amzn-mtls-clientcert-leaf` header, Tomcat never performs a TLS handshake with the client. The existing `RevocationTrustManager` + `RevocationChecker` pipeline is wired into Tomcat's SSL connector and therefore only runs for direct TLS connections, not for the header cert path. Without an explicit OCSP call after parsing the header cert, a revoked cert passes all application-level checks.

The fix extends `CertificatePrincipalProviderImpl` (izgw-core) to call `RevocationChecker.check()` after the existing validity and chain-of-trust checks. The issuer cert required by `RevocationChecker` is resolved from the trust store via a new `TrustManagerProvider.findIssuerCert()` lookup. The existing DynamoDB-cached OCSP infrastructure is reused unchanged.

**Fail-open cases (cert is accepted, warning logged):**
- `RevocationChecker.getInstance()` returns null (no bean configured — test environments)
- Issuer cert not found in trust store (cannot perform OCSP without issuer)
- OCSP responder unreachable (already handled by `RevocationChecker` — returns UNKNOWN)
- DynamoDB unavailable (already handled by `RevocationChecker` — returns without blocking)

**Fail-closed case:** OCSP responder returns `REVOKED` → `CertPathValidatorException` → `CertificateException` → `getCertificate()` returns null → `UnauthenticatedPrincipal` → 401 (when `AuthenticationEnforcementFilter` is active) or access-control check.

The attribute-based cert path (direct Tomcat TLS, no ALB) is unchanged — `getCertificateFromAttribute()` returns early before `checkRevocation()` is called.

## Risks / Trade-offs

- **Clock skew on `exp` validation** → Use a small configurable tolerance (default 30s). Spring's `JwtTimestampValidator` does this; reproduce the same logic in manual claim validation.
- **Secrets Manager latency on secret-cache cold start** → First request per `kid` after a deployment or rotation incurs SM latency. The secret cache TTL (1 hour default) amortizes this across subsequent requests.
- **Shared signing secret (HS256)**: Both CC and Hub task roles read the same secret. Enforced by IAM policy convention, not algorithm. Accepted per IGDD-2703 decision.
- **`client-auth=want` widens the TLS surface** → Any caller can initiate a connection without a cert. Mitigated by `AuthenticationEnforcementFilter` (JWT required) and Hub's existing `AccessControlValve` (role-based access enforcement). Not deployed in production until CC integration is validated in onboarding environment first.

## Migration Plan

1. **DynamoDB**: No table changes required; `ApiKeyCredential` uses the existing single-table design (new entity type, same `partitionKey`/`sortKey` structure).
2. **IAM**: Add `secretsmanager:GetSecretValue` on `/izg/{env}/jwt/signing-secret` to Hub task role.
3. **Config**: Add `jwt.*` properties to Hub's application configuration. `jwt.issuer` is environment-specific (CC URL per env). `jwt.test-secret` is local-only.
4. **`client-auth`**: Leave at `need` until CC integration is validated; flip to `want` per environment as part of CC rollout.
5. **Rollback**: Remove `jwt.*` config properties and restore `client-auth=need`. The certificate auth path is unchanged and will continue to work.

## Open Questions

None — all ADR open questions resolved (OQ1–OQ6 decided in IGDD-2703).
