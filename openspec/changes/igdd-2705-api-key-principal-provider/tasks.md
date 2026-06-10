## 1. DynamoDB Entity and Repository

- [x] 1.1 Create `ApiKeyCredential.java` in `gov.cdc.izgateway.dynamodb.model` — `@DynamoDbBean` extending `DynamoDbAudit`; fields: `jti`, `env`, `status`, `jurisdictionId`, `issuedAt` (`Instant`), `expiresAt` (`Instant`), `revokedAt` (`Instant`, nullable), `revokedBy` (String, nullable); sort key `ApiKeyCredential#{env}#{jti}`
- [x] 1.2 Create `ApiKeyCredentialRepository.java` in `gov.cdc.izgateway.dynamodb.repository` — extends `DynamoDbRepository<ApiKeyCredential>`; implement `findByEnvAndJti(String env, String jti)` returning `Optional<ApiKeyCredential>` via `DynamoDbEnhancedClient` GetItem
- [x] 1.3 Register `ApiKeyCredential` in `DynamoDbRepositoryFactory` so it is included in table scanning and repository wiring

## 2. ApiKeyPrincipal

- [x] 2.1 Create `ApiKeyPrincipal.java` in `gov.cdc.izgateway.hub.security` — extends `IzgPrincipal`; fields: `jti` (String), `dns` (String); constructor takes JWT claims (`sub` → `organization`, `jti` → `name`, `roles` → roles, `dns` → dns); implement `getSerialNumberHex()` to return `jti`

## 3. JWT Configuration

- [x] 3.1 Create `JwtConfig.java` in `gov.cdc.izgateway.hub.security` — `@Configuration` + `@ConfigurationProperties(prefix="jwt")`; fields: `issuer` (String), `secretsManagerSecretName` (String), `secretCacheTtl` (Duration, default 1h), `credentialCacheTtl` (Duration, default 5m), `testSecret` (String, nullable for dev bypass); provide `SecretsManagerClient` bean (skip if `testSecret` is set)
- [x] 3.2 Add `jwt.*` config stubs to `application.yml` (issuer, secretsManagerSecretName, cache TTLs) with placeholder values; mark `jwt.test-secret` as local-only in comments

## 4. ApiKeyPrincipalProvider

- [x] 4.1 Create `ApiKeyPrincipalProvider.java` in `gov.cdc.izgateway.hub.security` — `@Component`; inject `JwtConfig`, `ApiKeyCredentialRepository`, `JwtTokenExtractor` (from core); initialize two Caffeine caches: `secretCache` (by `kid`, TTL = `jwt.secret-cache-ttl`) and `credentialCache` (by `jti`, value = `ApiKeyPrincipal` or `Boolean.TRUE` sentinel, variable TTL)
- [x] 4.2 Implement `getProvider(HttpServletRequest)` — step 1: extract Bearer token via `JwtTokenExtractor`; return `null` on absent/non-Bearer header; step 2: parse JWT header only (use `com.nimbusds.jwt.SignedJWT.parse()`) to extract `kid`, `alg`, `iss`; return `null` if `alg != HS256` or `iss` doesn't match `jwt.issuer`
- [x] 4.3 Implement secret resolution — check `secretCache` by `kid`; on miss, call `SecretsManagerClient.getSecretValue(req -> req.secretId(config.secretsManagerSecretName()).versionId(kid))`; if `jwt.test-secret` is set, skip SM and use that value for all kids; cache the secret; return `null` if SM throws `ResourceNotFoundException`
- [x] 4.4 Implement HS256 signature verification — create `MACVerifier(secretBytes)` from `com.nimbusds.jose`; call `signedJwt.verify(verifier)`; return `null` on failure
- [x] 4.5 Implement claims validation — after successful verification, extract payload claims; validate: (a) `exp` not passed (allow 30s clock skew), (b) `env` matches Hub environment, (c) `iss` matches `jwt.issuer`; return `null` on any failure
- [x] 4.6 Implement credential cache lookup — check `credentialCache` by `jti`; on hit, return cached `ApiKeyPrincipal` (or `null` if value is `Boolean.TRUE` REVOKED sentinel); on miss, call `apiKeyCredentialRepository.findByEnvAndJti(env, jti)`
- [x] 4.7 Implement DynamoDB status handling — if `status == active`: construct `ApiKeyPrincipal` from JWT claims (`sub`, `roles`, `dns`, `jti`), store in `credentialCache` with `jwt.credential-cache-ttl`, return principal; if `status == revoked`/`expired` or absent: store `Boolean.TRUE` sentinel in `credentialCache` with TTL = 1 year, return `null`
- [x] 4.8 Implement revocation eviction method `evictCredential(String jti)` — evict `jti` from `credentialCache`, then immediately insert `Boolean.TRUE` sentinel with 1-year TTL

## 5. Authentication Enforcement Filter

- [x] 5.1 Create `AuthenticationEnforcementFilter.java` in `gov.cdc.izgateway.hub.security` — `@Component` + `@ConditionalOnProperty(name="server.ssl.client-auth", havingValue="want")`; extends `OncePerRequestFilter`; order `Ordered.HIGHEST_PRECEDENCE`; inject `HubPrincipalService`; in `doFilterInternal`: resolve principal; if `UnauthenticatedPrincipal`, write HTTP 401 and return without calling `filterChain.doFilter()`

## 6. HubPrincipalService Integration

- [x] 6.1 Inject `ApiKeyPrincipalProvider` into `HubPrincipalService`; update `getPrincipal(HttpServletRequest)` to try `apiKeyPrincipalProvider.getProvider(request)` first; only fall back to `CertificatePrincipalProvider` if that returns `null`; return `UnauthenticatedPrincipal` if both return `null`

## 7. Revocation Propagation

- [x] 7.1 Add optional `jti` field to `RefreshRequest` record in `RefreshQueueService` — update `fromMessage()` and the SQS message body to carry `jti` when present (backward-compatible: absent = full cache refresh, present = targeted eviction)
- [x] 7.2 Update `handleRefreshRequest` in `RefreshQueueService` — if `request.jti()` is non-null, call `apiKeyPrincipalProvider.evictCredential(jti)` instead of (or in addition to) the general refresh
- [x] 7.3 Update `DbController.refresh()` — accept optional `@RequestParam(required=false) String jti`; pass it into `RefreshRequest` when constructing the SQS message; wire `ApiKeyPrincipalProvider` for local eviction in the non-SQS code path

## 8. Local Dev Configuration

- [x] 8.1 Create `application-local-jwt.yml` — sets `server.ssl.client-auth=want`, `jwt.issuer=http://localhost:3000`, `jwt.test-secret=izg-test-secret-igdd-2705-do-not-use-in-production`, `jwt.secrets-manager-secret-name=` (empty, unused when test-secret set)
- [x] 8.2 Create `test-tokens.md` in the change directory — document the test JWT (kid, secret, jti, full token string, expiry) and the `curl` command to exercise the local endpoint; note the token is for local dev only

## 9. Tests

- [x] 9.1 Unit test `ApiKeyPrincipalProvider` — happy path: valid HS256 JWT, active credential in cache → returns `ApiKeyPrincipal` with correct jurisdictionId, roles, dns, jti
- [x] 9.2 Unit test — wrong algorithm (RS256 JWT) → returns `null`, no SM call
- [x] 9.3 Unit test — wrong issuer → returns `null`, no SM call
- [x] 9.4 Unit test — expired `exp` claim → returns `null`
- [x] 9.5 Unit test — wrong `env` claim → returns `null`
- [x] 9.6 Unit test — REVOKED sentinel in credential cache → returns `null`, no DynamoDB call
- [x] 9.7 Unit test — DynamoDB returns revoked status → returns `null`, inserts REVOKED sentinel
- [x] 9.8 Unit test — `evictCredential` inserts REVOKED sentinel and subsequent lookup returns `null`
- [x] 9.9 Unit test `ApiKeyCredentialRepository` — `findByEnvAndJti` constructs correct sort key and returns `Optional.empty()` on miss
- [x] 9.10 Verify `AuthenticationEnforcementFilter` returns 401 for `UnauthenticatedPrincipal` and passes through for authenticated principal
