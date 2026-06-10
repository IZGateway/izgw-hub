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
- Changes to `izgw-core` or `izgw-transform` (Hub-isolated change)
- `ApiKeyDomain` entity — Config Console's concern; Hub has no read/write to domain authorization records
- JWT issuance — Hub validates only; Config Console issues
- Egress IP binding (deferred, OQ2 in ADR)

## Decisions

### D1 — Standalone `ApiKeyPrincipalProvider`, not extending `AbstractJwtPrincipalProvider`
`AbstractJwtPrincipalProvider.createDecoder()` is invoked once at startup and returns a `NimbusJwtDecoder` with a fixed key. The ADR requires per-`kid` Secrets Manager lookup on every cache miss — the decoder must select the key based on the JWT header, not a configuration property. Rather than forcing that into the Spring-decoder model, Hub implements `ApiKeyPrincipalProvider` directly using `com.nimbusds:nimbus-jose-jwt` (already on classpath via Spring Security) to parse and verify JWTs at the claim level.

`JwtTokenExtractor` from core is still reused for Bearer token extraction.

### D2 — Two separate Caffeine caches, not one
- `secretCache`: keyed by `kid` (Secrets Manager version ID), value = `SecretString`, TTL = configurable (`jwt.secret-cache-ttl`, default 1 hour). Eliminates repeated SM calls for the same key version.
- `credentialCache`: keyed by `jti`, value = `ApiKeyPrincipal` OR `Boolean.TRUE` (REVOKED sentinel), TTL = 5 min for active / max token lifetime for revoked. Single cache with two value types would require sentinel objects or optionals; two caches with clear semantics are simpler.

### D3 — `ApiKeyPrincipal` carries `dns`, `jti`, and `jurisdictionId` from JWT claims
`jurisdictionId` and `roles` come from JWT claims (`sub` and `roles`) — not from DynamoDB. DynamoDB is checked only for `status` (active/revoked/absent). `dns` is carried for audit logging. `jti` is carried so the principal can be targeted for revocation without re-parsing the token.

`ApiKeyPrincipal` extends `IzgPrincipal` directly (as `CertificatePrincipal` does), not via `JWTPrincipal`, because it needs the extra `dns` and `jti` fields and its claim-extraction logic differs.

### D4 — `AuthenticationEnforcementFilter` for `client-auth=want`
When Hub is configured with `server.ssl.client-auth=want` (required to accept JWT-only callers who present no TLS cert), an anonymous TLS connection reaches the app and `HubPrincipalService` returns `UnauthenticatedPrincipal`. `AccessControlValve` in `want` mode would log but not block the request. `AuthenticationEnforcementFilter` (order `HIGHEST_PRECEDENCE`) intercepts before business logic and returns 401 immediately for any `UnauthenticatedPrincipal`. In production with `client-auth=need`, the filter is present but harmless — anonymous connections are blocked at TLS.

### D5 — Revocation propagates `jti` via existing SQS refresh mechanism
`DbController`'s `/rest/refresh?all=true` endpoint and `RefreshQueueService` already propagate refresh events to all Hub instances via SQS. Config Console calls this endpoint after marking a `jti` revoked. Hub's `ApiKeyPrincipalProvider` subscribes to the refresh event, evicts the `jti` from `credentialCache`, and inserts a REVOKED sentinel with TTL = max token lifetime — ensuring no subsequent cache miss can re-validate the revoked credential. No new SQS topic or SNS resource is needed.

### D6 — `jwt.test-secret` property bypasses Secrets Manager for local dev
When `jwt.test-secret` is set, `ApiKeyPrincipalProvider` uses that secret for all `kid` values instead of calling Secrets Manager. This allows local testing without AWS credentials. The property must not be set in non-local profiles.

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
