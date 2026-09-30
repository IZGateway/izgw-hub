# Spec: `ApiKeyPrincipalProvider`

**Component:** Hub HS256 JWT (API key) authentication path (IGDD-2703 / IGDD-2705)  
**Implemented in:** `gov.cdc.izgateway.hub.security.ApiKeyPrincipalProvider`, `ApiKeyPrincipal`, `JwtConfig`, `AuthenticationEnforcementFilter`, `gov.cdc.izgateway.hub.service.HubPrincipalService`  
**Related specs:** `api-key-credential`  

---

## Purpose

Hub authenticates API-key holders by validating an HS256 Bearer JWT: it extracts the
token from the `Authorization` header, pre-checks the header's `alg`, `iss`, and `kid`,
resolves the signing secret from Secrets Manager by `kid` (with positive and negative
caches), verifies the signature, validates the claims, and then checks the `jti`-keyed
credential cache and `ApiKeyCredential` record, including the server-side
`environments` set. The `upn` claim is the stable sender identity, and once a Bearer
header has been presented any validation failure hard-fails with
`ApiKeyAuthenticationException` rather than falling back to certificate
authentication; `AuthenticationEnforcementFilter` ensures a client must authenticate by
one path or the other when Tomcat runs with `client-auth=want`. Revocations propagate
through `/rest/refresh` and SQS, ALB-forwarded client certificates receive an OCSP
check through the existing `RevocationChecker`, and `jwt.test-secret` provides a
local-development bypass of Secrets Manager. JWT clients are authorized by `upn`
through the same DynamoDB AccessGroup lookup that authorizes mTLS clients by
certificate CN; roles are never taken from JWT claims.

---

## Requirements

### Requirement: Bearer token extraction
Hub SHALL extract the Bearer JWT from the `Authorization` HTTP header before attempting any JWT validation. If the header is absent or does not start with `Bearer `, the provider SHALL return `null` immediately without touching Secrets Manager, DynamoDB, or any cache, allowing fallback to `CertificatePrincipalProvider`.

This is the **only** condition under which the provider returns `null`. Once a Bearer-scheme header has been presented, the caller has identified itself as an API-key holder: every subsequent validation failure SHALL throw `ApiKeyAuthenticationException` (see "Presented API key that fails authentication is not retried as a certificate"), never return `null`.

#### Scenario: No Authorization header
- **WHEN** a request arrives with no `Authorization` header
- **THEN** `ApiKeyPrincipalProvider` returns `null` and cert auth proceeds

#### Scenario: Non-Bearer authorization header
- **WHEN** a request arrives with `Authorization: Basic dXNlcjpwYXNz`
- **THEN** `ApiKeyPrincipalProvider` returns `null` immediately

### Requirement: JWT header parsing and issuer/algorithm pre-check
Hub SHALL parse only the JWT header (without verifying the signature) to extract `kid`, `alg`, and `iss`. If the token cannot be parsed, `alg` is not `HS256`, `iss` does not match the configured `jwt.issuer` value, or the `kid` header is absent or blank, the provider SHALL throw `ApiKeyAuthenticationException` immediately, before any Secrets Manager or DynamoDB call.

#### Scenario: Unparseable token
- **WHEN** the Bearer value is not a well-formed compact JWS
- **THEN** `ApiKeyPrincipalProvider` throws `ApiKeyAuthenticationException` without any SM or DynamoDB call

#### Scenario: Wrong algorithm
- **WHEN** a JWT header contains `"alg": "RS256"`
- **THEN** `ApiKeyPrincipalProvider` throws `ApiKeyAuthenticationException` without any SM or DynamoDB call

#### Scenario: Wrong issuer
- **WHEN** a JWT header contains an `iss` that does not match `jwt.issuer`
- **THEN** `ApiKeyPrincipalProvider` throws `ApiKeyAuthenticationException` without any SM or DynamoDB call

#### Scenario: Missing kid header
- **WHEN** a JWT header has no `kid` (or a blank one)
- **THEN** `ApiKeyPrincipalProvider` throws `ApiKeyAuthenticationException` without any SM or DynamoDB call

#### Scenario: HS256 with matching issuer
- **WHEN** a JWT header contains `"alg": "HS256"` and `iss` matches `jwt.issuer`
- **THEN** validation proceeds to the secret-cache lookup step

### Requirement: Secret cache lookup by `kid`
Hub SHALL maintain an in-memory secret cache keyed by `kid` (the Secrets Manager version ID). On a cache hit, the cached secret SHALL be used immediately without calling Secrets Manager. On a cache miss, Hub SHALL call `GetSecretValue` with `VersionId=kid` on the configured Secrets Manager secret name, store the result in cache with a configurable TTL (`jwt.secret-cache-ttl`, default 1 hour), and proceed.

Hub SHALL also maintain a negative secret cache keyed by `kid` with a fixed 60-second TTL. When Secrets Manager reports no version for `kid`, the `kid` SHALL be recorded in the negative cache so that repeated tokens with the same unknown `kid` do not each trigger a Secrets Manager call; the lookup is retried at most once per minute. If the secret cannot be resolved for any reason (unknown `kid`, negative-cache hit, Secrets Manager error, or no client configured without `jwt.test-secret`), the provider SHALL throw `ApiKeyAuthenticationException`.

#### Scenario: Secret cache hit
- **WHEN** the `kid` from the JWT header is already in the secret cache
- **THEN** no Secrets Manager call is made and the cached secret is used for signature verification

#### Scenario: Secret cache miss
- **WHEN** the `kid` is not in the secret cache
- **THEN** Hub calls `GetSecretValue(SecretId=jwt.secrets-manager-secret-name, VersionId=kid)`, stores the returned secret in cache, and uses it for verification

#### Scenario: Unknown kid (SM returns not-found)
- **WHEN** Secrets Manager has no version matching `kid`
- **THEN** the `kid` is recorded in the negative secret cache and `ApiKeyPrincipalProvider` throws `ApiKeyAuthenticationException`

#### Scenario: Unknown kid presented again within 60 seconds
- **WHEN** a second token with the same unknown `kid` arrives while it is in the negative secret cache
- **THEN** no Secrets Manager call is made and `ApiKeyPrincipalProvider` throws `ApiKeyAuthenticationException`

### Requirement: HS256 signature verification
Hub SHALL verify the JWT's HMAC-SHA256 signature against the secret retrieved for the token's `kid`. A signature verification failure SHALL cause the provider to throw `ApiKeyAuthenticationException`.

#### Scenario: Valid signature
- **WHEN** the JWT signature matches `HMAC-SHA256(header.payload, secret)`
- **THEN** claim validation proceeds

#### Scenario: Invalid signature
- **WHEN** the JWT signature does not match
- **THEN** `ApiKeyPrincipalProvider` throws `ApiKeyAuthenticationException`

### Requirement: Claims validation
After successful signature verification, Hub SHALL validate the following claims in order. Any failure SHALL cause the provider to throw `ApiKeyAuthenticationException`:
1. `exp` is present and has not passed (with a fixed clock-skew tolerance of 30 seconds)
2. `nbf`, if present, is not in the future (beyond the same 30-second tolerance)
3. `iss` matches the configured `jwt.issuer` (re-validated against the decoded payload)
4. `jti` is present

The token does NOT carry an `env` claim. Environment authorization is a server-side property of the credential and is enforced later against the credential's `environments` list (see "DynamoDB credential status check").

#### Scenario: Expired token
- **WHEN** the current time exceeds the JWT `exp` claim (beyond the clock-skew tolerance)
- **THEN** `ApiKeyPrincipalProvider` throws `ApiKeyAuthenticationException`

#### Scenario: Token not yet valid
- **WHEN** the JWT carries an `nbf` claim more than 30 seconds in the future
- **THEN** `ApiKeyPrincipalProvider` throws `ApiKeyAuthenticationException`

#### Scenario: Missing jti claim
- **WHEN** the JWT payload has no `jti` claim
- **THEN** `ApiKeyPrincipalProvider` throws `ApiKeyAuthenticationException` without any DynamoDB call

#### Scenario: Valid claims
- **WHEN** `exp` is in the future and `iss` matches
- **THEN** credential cache lookup proceeds

### Requirement: Credential cache lookup by `jti`
Hub SHALL maintain an in-memory credential cache keyed by `jti`. On a cache hit, the cached value (an `ApiKeyPrincipal` or REVOKED sentinel) SHALL be returned immediately without a DynamoDB call. On a cache miss, Hub SHALL query DynamoDB for the `ApiKeyCredential` record with sort key `{jti}`.

#### Scenario: Credential cache hit — active
- **WHEN** the `jti` is in the credential cache as an `ApiKeyPrincipal`
- **THEN** that principal is returned immediately with no DynamoDB call

#### Scenario: Credential cache hit — revoked
- **WHEN** the `jti` is in the credential cache as a REVOKED sentinel
- **THEN** `ApiKeyPrincipalProvider` throws `ApiKeyAuthenticationException` immediately with no DynamoDB call

#### Scenario: Credential cache miss
- **WHEN** the `jti` is not in the credential cache
- **THEN** Hub reads the `ApiKeyCredential` record from DynamoDB by sort key `{jti}`

### Requirement: `upn` claim validation
After signature and standard claims verification, Hub SHALL extract the `upn` claim from the verified JWT payload. If `upn` is absent or blank, Hub SHALL log a warning and throw `ApiKeyAuthenticationException`, rejecting the token. A non-blank `upn` is required for the principal to be usable in `AllowedUser` authorization lookups.

#### Scenario: Missing upn claim
- **WHEN** a JWT payload contains no `upn` claim
- **THEN** `ApiKeyPrincipalProvider` logs a warning and throws `ApiKeyAuthenticationException`

#### Scenario: Blank upn claim
- **WHEN** a JWT payload contains `"upn": ""`
- **THEN** `ApiKeyPrincipalProvider` logs a warning and throws `ApiKeyAuthenticationException`

#### Scenario: Valid upn claim
- **WHEN** a JWT payload contains `"upn": "immunize.example.gov"`
- **THEN** validation proceeds to the credential cache lookup step

### Requirement: DynamoDB credential status check
When the credential cache misses, Hub SHALL read the `ApiKeyCredential` record and act on its `status`:
- **active** or **grace_period**: validate that the request's target environment (`SystemUtils.getDestType()`) is contained in the credential's server-side `environments` list; if it is not, record the `jti` in the absent cache (so an `environments` edit takes effect within the credential-cache TTL) and throw `ApiKeyAuthenticationException`. Otherwise construct an `ApiKeyPrincipal` from JWT claims (`upn` → `name` (IzgPrincipal.getName()), `sub` → `organization` (a numeric string jurisdiction ID, e.g., `"42"`), `jti` → jti), store in credential cache with `jwt.credential-cache-ttl` (default 5 minutes), and return the principal. The `roles` field on the returned principal SHALL be empty; JWT claims SHALL NOT be used to populate roles.
- **revoked** (any non-usable status): store a REVOKED sentinel in the revoked cache with TTL equal to the maximum possible token lifetime (366 days), and throw `ApiKeyAuthenticationException`.
- **record absent**: record the `jti` in the absent cache with TTL equal to `jwt.credential-cache-ttl` (default 5 minutes), and throw `ApiKeyAuthenticationException`. The shorter TTL lets a credential record created after a cold-cache miss take effect without a permanent lockout.

#### Scenario: Active credential
- **WHEN** DynamoDB returns `status = active` for the `jti` and the request's target environment is in the credential's `environments` list
- **THEN** an `ApiKeyPrincipal` is constructed from JWT claims with `name = upn`, empty `roles`, and cached with 5-minute TTL, then returned

#### Scenario: Credential not valid for the target environment
- **WHEN** DynamoDB returns `status = active` for the `jti` but the request's target environment (`SystemUtils.getDestType()`) is NOT in the credential's `environments` list
- **THEN** the `jti` is recorded in the absent cache and `ApiKeyPrincipalProvider` throws `ApiKeyAuthenticationException`, resulting in 401

#### Scenario: Revoked credential
- **WHEN** DynamoDB returns `status = revoked` for the `jti`
- **THEN** a REVOKED sentinel is cached with max-token-lifetime TTL and `ApiKeyAuthenticationException` is thrown, resulting in 401

#### Scenario: Absent credential record
- **WHEN** DynamoDB has no `ApiKeyCredential` record for `{jti}`
- **THEN** the `jti` is cached in the absent cache (5-minute TTL) and `ApiKeyAuthenticationException` is thrown, resulting in 401

### Requirement: Revocation propagation via refresh endpoint
When Hub's `/rest/refresh` endpoint is called (by Config Console after revoking a `jti`), Hub SHALL:
1. Evict the `jti` from the credential cache.
2. Insert a REVOKED sentinel into the credential cache with TTL equal to max token lifetime.

The refresh event SHALL propagate to all Hub instances via the existing SQS inter-instance mechanism so that every instance evicts the credential independently.

#### Scenario: Refresh called after revocation
- **WHEN** Config Console calls `/rest/refresh?all=true` (or with a specific `jti` parameter) after revoking a credential
- **THEN** every Hub instance evicts the `jti` from its credential cache and inserts a REVOKED sentinel within seconds

#### Scenario: Subsequent request after revocation propagation
- **WHEN** a request arrives with a revoked JWT after the refresh has propagated
- **THEN** the revoked cache hits, `ApiKeyPrincipalProvider` throws `ApiKeyAuthenticationException`, and Hub returns 401 without a DynamoDB call

### Requirement: OCSP revocation check for ALB-forwarded client certificates
When a client certificate is received via the ALB header (not from a direct Tomcat TLS handshake), Hub SHALL perform an OCSP revocation check after the existing validity-period and chain-of-trust checks. The OCSP check SHALL use the existing `RevocationChecker` infrastructure, which caches results in DynamoDB with a 24-hour TTL and reads the OCSP responder URL from the certificate's AIA extension.

The issuer certificate required by `RevocationChecker` SHALL be resolved from the server trust store by matching the leaf certificate's issuer DN against trust store entry subject DNs. If the issuer certificate is not found in the trust store, the OCSP check SHALL be skipped and a warning logged; the certificate SHALL be accepted (fail-open for uncheckable issuers).

The attribute-based certificate path (direct Tomcat TLS with `server.ssl.client-auth=need`) is unaffected — that path returns early before OCSP is invoked.

#### Scenario: Valid, non-revoked certificate via ALB header
- **WHEN** a request arrives with an `x-amzn-mtls-clientcert-leaf` header containing a valid, non-revoked certificate whose issuer is in the trust store
- **THEN** OCSP check passes (or returns from DynamoDB cache), `CertificatePrincipalProvider` returns the principal, and the request proceeds normally

#### Scenario: Revoked certificate via ALB header
- **WHEN** a request arrives with a certificate that OCSP confirms as revoked
- **THEN** `CertificatePrincipalProvider` returns null, the request resolves to `UnauthenticatedPrincipal`, and the caller receives 401 (when `AuthenticationEnforcementFilter` is active)

#### Scenario: Issuer cert not in trust store
- **WHEN** a certificate is received whose issuing CA is not present in the server trust store
- **THEN** OCSP check is skipped, a warning is logged, and the certificate is accepted (chain-of-trust check already rejected it if the CA is truly untrusted)

#### Scenario: OCSP responder unreachable
- **WHEN** the OCSP responder URL in the certificate is unreachable
- **THEN** `RevocationChecker` returns UNKNOWN status, no exception is thrown, and the certificate is accepted

#### Scenario: OCSP result is cached in DynamoDB
- **WHEN** a certificate has been OCSP-checked within the last 24 hours and the result is cached as GOOD in DynamoDB
- **THEN** no outbound OCSP request is made; the cached result is used and the certificate is accepted immediately

### Requirement: Fallback to CertificatePrincipalProvider
When `ApiKeyPrincipalProvider` returns `null` — which happens only when no Bearer-scheme `Authorization` header was presented — Hub SHALL fall back to `CertificatePrincipalProvider`. mTLS certificate callers MUST continue to work unchanged.

#### Scenario: mTLS client with valid cert and no Bearer token
- **WHEN** a request arrives with a valid mTLS certificate and no `Authorization` header
- **THEN** `ApiKeyPrincipalProvider` returns `null`, `CertificatePrincipalProvider` succeeds, and the request proceeds normally

#### Scenario: mTLS client with valid cert and a non-Bearer Authorization header
- **WHEN** a request arrives with a valid mTLS certificate and `Authorization: Basic …`
- **THEN** `ApiKeyPrincipalProvider` returns `null` and `CertificatePrincipalProvider` authenticates the caller

### Requirement: Presented API key that fails authentication is not retried as a certificate
When a Bearer-scheme `Authorization` header is present and `ApiKeyPrincipalProvider` rejects it for any reason (unparseable token, wrong `alg`/`iss`, missing `kid`/`jti`/`upn`, unresolvable secret, bad signature, expired or not-yet-valid claims, revoked/absent credential, environment mismatch), the provider SHALL throw `ApiKeyAuthenticationException`. `HubPrincipalService` SHALL catch that exception, log a warning, and resolve the request to `UnauthenticatedPrincipal` **without** attempting `CertificatePrincipalProvider`. A caller that identifies itself as an API-key holder and fails MUST NOT be silently re-authenticated by a client certificate it also happens to present.

#### Scenario: Invalid API key with a valid mTLS certificate
- **WHEN** a request arrives with a valid mTLS certificate and a Bearer JWT that fails signature verification
- **THEN** `HubPrincipalService` resolves the request to `UnauthenticatedPrincipal` and the certificate is never evaluated

#### Scenario: Revoked API key with no certificate
- **WHEN** a request arrives with no TLS certificate and a Bearer JWT whose `jti` is revoked
- **THEN** `HubPrincipalService` resolves the request to `UnauthenticatedPrincipal` and, with `AuthenticationEnforcementFilter` active, the caller receives 401

### Requirement: Authentication enforcement filter for JWT-only clients
When Hub is configured with `server.ssl.client-auth=want`, Hub SHALL run `AuthenticationEnforcementFilter` (order `HIGHEST_PRECEDENCE`) to return HTTP 401 for any request whose resolved principal is `UnauthenticatedPrincipal`. This prevents anonymous callers from reaching business logic when cert-only enforcement is relaxed to accept JWT-only clients.

#### Scenario: Anonymous caller with client-auth=want
- **WHEN** a request arrives with no TLS certificate and no valid Bearer token
- **THEN** `AuthenticationEnforcementFilter` intercepts and returns 401 before any business logic executes

### Requirement: Local dev bypass via `jwt.test-secret`
When the `jwt.test-secret` configuration property is set, Hub SHALL use that value as the HS256 signing secret for all `kid` values, bypassing Secrets Manager. This property MUST NOT be set in non-local Spring profiles.

#### Scenario: Local dev with test-secret set
- **WHEN** `jwt.test-secret` is configured and a valid HS256 JWT signed with that secret is presented
- **THEN** validation succeeds without any Secrets Manager call

### Requirement: JWT client authorization via DynamoDB AccessGroup lookup
Hub SHALL authorize JWT clients using the `upn` value as the identity key in DynamoDB AccessGroup lookups, identical to how mTLS cert clients are authorized using the certificate CN. `AccessControlService.isUserInRole()` SHALL NOT contain a special fallback for `ApiKeyPrincipal` instances that reads roles from the JWT token. The DynamoDB AccessGroup table is the sole source of role assignments for both JWT and cert principals.

#### Scenario: JWT client UPN present in AccessGroup
- **WHEN** a JWT client presents a valid token with `upn = immunize.example.gov` AND that UPN is a member of an AccessGroup with role `soap`
- **THEN** the JWT client is authorized to call SOAP endpoints, identical to a cert client with CN `immunize.example.gov` in the same group

#### Scenario: JWT client UPN absent from all AccessGroups
- **WHEN** a JWT client presents a valid token with a `upn` that is not in any AccessGroup
- **THEN** access is denied with 401, identical to a cert client whose CN is not in any group

#### Scenario: JWT client UPN in admin AccessGroup
- **WHEN** a JWT client presents a valid token with a `upn` that is in an AccessGroup with role `admin`
- **THEN** `AccessControlValve.updateRoles()` adds `ADMIN` to `RequestContext.getRoles()`, granting admin privileges identical to an mTLS admin cert client
