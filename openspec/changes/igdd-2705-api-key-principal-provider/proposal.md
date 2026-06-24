## Why

IZ Gateway Hub currently authenticates callers exclusively via mTLS client certificates issued by DigiCert. This creates recurring operational friction (lost private keys, 2-day DNS validation delays, 24-hour CRL propagation). A JWT-based credential type issued by Config Console would eliminate this overhead while meeting the same security controls, as documented and approved in the IGDD-2703 ADR.

## What Changes

- **New**: `ApiKeyCredential` DynamoDB entity — credential registry entry keyed by `env#jti`; stores `status` (active/revoked), `jurisdictionId`, `issuedAt`, `expiresAt`
- **New**: `ApiKeyCredentialRepository` — DynamoDB repository for `ApiKeyCredential` lookup by `env` and `jti`
- **New**: `ApiKeyPrincipal` — extends `IzgPrincipal`; carries `jurisdictionId` (from `sub`), `roles`, `dns`, and `jti` from a validated HS256 JWT
- **New**: `ApiKeyPrincipalProvider` — validates HS256 JWTs; uses a secret cache (`kid` → Secrets Manager version) and a credential cache (`jti` → `ApiKeyPrincipal` or REVOKED sentinel); falls back gracefully to cert auth
- **New**: `JwtConfig` — `@ConfigurationProperties(prefix="jwt")` binding for `jwt.issuer`, `jwt.secrets-manager-secret-name`, secret-cache TTL, and credential-cache TTL
- **New**: `AuthenticationEnforcementFilter` — `OncePerRequestFilter` that returns 401 for `UnauthenticatedPrincipal` when `client-auth=want` (required for JWT-only clients with no TLS cert)
- **Modified**: `HubPrincipalService` — updated auth chain: try `ApiKeyPrincipalProvider` → fallback to `CertificatePrincipalProvider` → fallback to `UnauthenticatedPrincipal`
- **Modified**: `DbController` — `/rest/refresh` accepts optional `jti` parameter to trigger per-key revocation eviction
- **Modified**: `RefreshQueueService` / `RefreshRequest` — propagate `jti` for targeted cache eviction across SQS inter-instance mechanism

## Capabilities

### New Capabilities

- `api-key-principal-provider`: HS256 JWT validation flow — header parsing, `kid`-based Secrets Manager secret lookup with in-memory cache, signature verification, claims validation (`exp`, `env`, `iss`), `jti` credential registry check against DynamoDB with in-memory cache, REVOKED sentinel logic, and `ApiKeyPrincipal` construction
- `api-key-credential`: DynamoDB entity representing a registered API key credential — lifecycle (active/revoked/expired), sort-key structure, and repository contract for Hub's credential validation path

### Modified Capabilities

## Impact

- **`HubPrincipalService`** — auth chain updated; JWT-only clients now supported with `client-auth=want`
- **`DbController` / `RefreshQueueService`** — revocation propagation extended to carry `jti`
- **DynamoDB shared table** — two new entity types: `ApiKeyCredential` (sort key `env#jti`), used by Hub; `ApiKeyDomain` (sort key `env#domain`) is Config Console's concern and not touched here
- **AWS Secrets Manager** — Hub IAM task role needs `secretsmanager:GetSecretValue` on `/izg/{env}/jwt/signing-secret`
- **`izgw-core`** — no changes; `JwtTokenExtractor` and `IzgPrincipal` from core are reused as-is
- **Dependencies** — `com.nimbusds:nimbus-jose-jwt` (already on classpath via Spring Security), `com.github.ben-manes.caffeine:caffeine` (already on classpath)
