## 1. DynamoDB Entity and Repository

- [ ] 1.1 Create `ApiKeyCredential.java` in `gov.cdc.izgateway.dynamodb.model` — `@DynamoDbBean` extending `DynamoDbAudit`; fields: `jti`, `environments` (List of numeric env IDs), `status`, `jurisdictionId`, `issuedAt` (`Instant`), `expiresAt` (`Instant`), `revokedAt` (`Instant`, nullable), `revokedBy` (String, nullable); sort key `{jti}`
- [ ] 1.2 Create `ApiKeyCredentialRepository.java` in `gov.cdc.izgateway.dynamodb.repository` — extends `DynamoDbRepository<ApiKeyCredential>`; implement `findByJti(String jti)` returning `Optional<ApiKeyCredential>` via `DynamoDbEnhancedClient` GetItem on sort key `{jti}`
- [x] 1.3 Register `ApiKeyCredential` in `DynamoDbRepositoryFactory` so it is included in table scanning and repository wiring

## 2. ApiKeyPrincipal

- [x] 2.1 Create `ApiKeyPrincipal.java` in `gov.cdc.izgateway.hub.security` — extends `IzgPrincipal`; fields: `jti` (String), `upn` (String); constructor takes JWT claims (`upn` → `name` (IzgPrincipal.getName()), `sub` → `organization`, `jti` → jti, `roles` → roles); implement `getSerialNumberHex()` to return `jti`

## 3. JWT Configuration

- [x] 3.1 Create `JwtConfig.java` in `gov.cdc.izgateway.hub.security` — `@Configuration` + `@ConfigurationProperties(prefix="jwt")`; fields: `issuer` (String), `secretsManagerSecretName` (String), `secretCacheTtl` (Duration, default 1h), `credentialCacheTtl` (Duration, default 5m), `testSecret` (String, nullable for dev bypass); provide `SecretsManagerClient` bean (skip if `testSecret` is set)
- [x] 3.2 Add `jwt.*` config stubs to `application.yml` (issuer, secretsManagerSecretName, cache TTLs) with placeholder values; mark `jwt.test-secret` as local-only in comments

## 4. ApiKeyPrincipalProvider

- [x] 4.1 Create `ApiKeyPrincipalProvider.java` in `gov.cdc.izgateway.hub.security` — `@Component`; inject `JwtConfig`, `ApiKeyCredentialRepository`, `JwtTokenExtractor` (from core); initialize four Caffeine caches: `secretCache` (by `kid`), `negativeSecretCache` (failed SM lookups, 60s), `credentialCache` (active principals, 5m), `revokedCache` (revoked/inactive, 366d), `absentCache` (not found in DynamoDB, 5m)
- [x] 4.2 Implement `getPrincipal(HttpServletRequest)` — step 1: extract Bearer token via `JwtTokenExtractor`; return `null` on absent/non-Bearer header; step 2: parse JWT header only (use `com.nimbusds.jwt.SignedJWT.parse()`) to extract `kid`, `alg`, `iss`; return `null` if `alg != HS256`, `kid` is blank, or `iss` doesn't match `jwt.issuer`
- [x] 4.3 Implement secret resolution — check `secretCache` by `kid`; on miss, call `SecretsManagerClient.getSecretValue(req -> req.secretId(config.secretsManagerSecretName()).versionId(kid))`; if `jwt.test-secret` is set, skip SM and use that value for all kids; cache the secret; return `null` if SM throws `ResourceNotFoundException`
- [x] 4.4 Implement HS256 signature verification — create `MACVerifier(secretBytes)` from `com.nimbusds.jose`; call `signedJwt.verify(verifier)`; return `null` on failure
- [ ] 4.5 Implement claims validation — after successful verification, extract payload claims; validate: (a) `exp` not passed (allow 30s clock skew), (b) `iss` matches `jwt.issuer`; return `null` on any failure. (Environment is NOT a claim — it is checked against the credential's server-side `environments` list after lookup, task 4.7.)
- [ ] 4.6 Implement credential cache lookup — check `credentialCache` by `jti`; on hit, return cached `ApiKeyPrincipal` (or `null` if value is `Boolean.TRUE` REVOKED sentinel); on miss, call `apiKeyCredentialRepository.findByJti(jti)`
- [ ] 4.7 Implement DynamoDB status handling — if `status == active` AND the request's target environment (`SystemUtils.getDestType()`) is in the credential's `environments` list: construct `ApiKeyPrincipal`, store in `credentialCache` (5m TTL), return principal; if the record is absent, or the credential is `active` but the target environment is NOT in `environments`: store in `absentCache` (5m TTL — short TTL so an `environments` update takes effect promptly), return `null`; if `status != active` (revoked): store in `revokedCache` (366d TTL), return `null`
- [x] 4.8 Implement revocation eviction method `evictCredential(String jti)` — evict `jti` from `credentialCache` and `absentCache`, insert into `revokedCache` with 366d TTL

## 5. Authentication Enforcement Filter

- [x] 5.1 Create `AuthenticationEnforcementFilter.java` in `gov.cdc.izgateway.hub.security` — `@Component` + `@ConditionalOnProperty(name="server.ssl.client-auth", havingValue="want")`; extends `OncePerRequestFilter`; order `Ordered.HIGHEST_PRECEDENCE`; inject `HubPrincipalService`; in `doFilterInternal`: resolve principal; if `UnauthenticatedPrincipal`, write HTTP 401 and return without calling `filterChain.doFilter()`

## 6. HubPrincipalService Integration

- [x] 6.1 Inject `ApiKeyPrincipalProvider` into `HubPrincipalService`; update `getPrincipal(HttpServletRequest)` to try `apiKeyPrincipalProvider.getPrincipal(request)` first; only fall back to `CertificatePrincipalProvider` if that returns `null`; return `UnauthenticatedPrincipal` if both return `null`

## 7. Revocation Propagation

- [x] 7.1 Add optional `jti` field to `RefreshRequest` record in `RefreshQueueService` — update `fromMessage()` and the SQS message body to carry `jti` when present (backward-compatible: absent = full cache refresh, present = targeted eviction)
- [x] 7.2 Update `handleRefreshRequest` in `RefreshQueueService` — if `request.jti()` is non-null, call `apiKeyPrincipalProvider.evictCredential(jti)` instead of (or in addition to) the general refresh
- [x] 7.3 Update `DbController.refresh()` — accept optional `@RequestParam(required=false) String jti`; pass it into `RefreshRequest` when constructing the SQS message; wire `ApiKeyPrincipalProvider` for local eviction in the non-SQS code path

## 8. Local Dev Configuration

- [x] 8.1 Create `application-local-jwt.yml` — sets `server.ssl.client-auth=want`, `jwt.issuer=http://localhost:3000`, `jwt.test-secret=izg-test-secret-igdd-2705-do-not-use-in-production`, `jwt.secrets-manager-secret-name=` (empty, unused when test-secret set)
- [x] 8.2 Create `test-tokens.md` in the change directory — document the test JWT (kid, secret, jti, full token string, expiry) and the `curl` command to exercise the local endpoint; note the token is for local dev only

## 10. Bug Fix — AccessControlService Role Check

> **SUPERSEDED — do not implement.** The `jwt-upn-authorization` change reversed this task:
> the JWT-claim roles fallback was removed from `AccessControlService.isUserInRole()`, and
> `ApiKeyPrincipal` no longer carries roles at all. Role assignments come solely from the
> DynamoDB AccessGroup table, keyed on `principal.getName()` (the `upn` for JWT clients, the
> CN for cert clients) — the same lookup used for mTLS callers. The 401 problem described
> below is instead resolved by provisioning the credential's UPN into an AccessGroup.
> Retained for history; see `openspec/changes/jwt-upn-authorization/`.

- [x] ~~10.1 Fix `AccessControlService.isUserInRole()` to fall back to checking `ApiKeyPrincipal.getRoles()` when the DynamoDB access control table has no entry for the principal. Without this fix, API key callers always received 401 on protected endpoints because `AccessControlValve` identifies users by `principal.getName()` (the `upn` value), which has no entry in the cert-based access control table.~~

## 12. OCSP Revocation for Header Certificate Path (izgw-core)

- [x] 12.1 Update `TrustManagerProvider` (izgw-core) — promote `trustStore` from local variable to `@Getter` field; add `findIssuerCert(X509Certificate leaf)` method that walks trust store aliases matching leaf cert's issuer DN against candidate subject DNs; returns null if no match found
- [x] 12.2 Update `CertificatePrincipalProviderImpl` (izgw-core) — store `TrustManagerProvider` reference in constructor; add `checkRevocation(X509Certificate)` that resolves issuer via `findIssuerCert`, calls `RevocationChecker.getInstance().check()`, and throws `CertificateException` on `REVOKED`; restructure header cert path in `getCertificate()` to call `checkRevocation` after `validator.isValid()`; attribute-based cert path (direct Tomcat TLS) is unchanged

## 9. Tests

- [x] 9.1 Unit test `ApiKeyPrincipalProvider` — happy path: valid HS256 JWT, active credential in cache → returns `ApiKeyPrincipal` with correct jurisdictionId, roles, upn, jti
- [x] 9.2 Unit test — wrong algorithm (RS256 JWT) → returns `null`, no SM call
- [x] 9.3 Unit test — wrong issuer → returns `null`, no SM call
- [x] 9.4 Unit test — expired `exp` claim → returns `null`
- [ ] 9.5 Unit test — request target environment not in credential's `environments` list → returns `null`
- [x] 9.6 Unit test — REVOKED sentinel in credential cache → returns `null`, no DynamoDB call
- [x] 9.7 Unit test — DynamoDB returns revoked status → returns `null`, inserts REVOKED sentinel
- [x] 9.8 Unit test — `evictCredential` inserts REVOKED sentinel and subsequent lookup returns `null`
- [ ] 9.9 Unit test `ApiKeyCredentialRepository` — `findByJti` constructs sort key `{jti}` and returns `Optional.empty()` on miss
- [x] 9.10 Verify `AuthenticationEnforcementFilter` returns 401 for `UnauthenticatedPrincipal` and passes through for authenticated principal

## 11. UPN Claim — Authorization Identity Fix

- [x] 11.1 Update `ApiKeyPrincipal.java` — rename field `dns` → `upn`; change constructor to accept `upnValue` parameter; set `name = upnValue` (replaces `name = jtiValue`); `jti` remains on `getSerialNumberHex()` only; update Lombok-generated accessors accordingly
- [x] 11.2 Update `ApiKeyPrincipalProvider.lookupAndCacheCredential()` — extract `upn` claim (`claims.getClaim("upn")`) instead of `dns`; if `upn` is null or blank, log a warning and return `null`; pass `upn` as the name argument to the `ApiKeyPrincipal` constructor
- [x] 11.3 Update unit tests in `ApiKeyPrincipalProviderTest` — replace `dns` with `upn` in all JWT builder helpers and assertion checks; add test: missing `upn` claim → returns `null`; add test: blank `upn` claim → returns `null`; update happy-path assertion to verify `principal.getName()` equals the `upn` value (not the `jti`)
- [x] 11.4 Update unit tests in `ApiKeyPrincipalTest` — replace `dns` constructor argument with `upn`; assert `getName()` returns the `upn` value
- [x] 11.5 Update `test-tokens.md` — replace `dns` claim with `upn` in the example JWT payload; regenerate the example token string signed with the test secret
