## ADDED Requirements

### Requirement: Bearer token extraction
Hub SHALL extract the Bearer JWT from the `Authorization` HTTP header before attempting any JWT validation. If the header is absent or does not start with `Bearer `, the provider SHALL return `null` immediately without touching Secrets Manager, DynamoDB, or any cache, allowing fallback to `CertificatePrincipalProvider`.

#### Scenario: No Authorization header
- **WHEN** a request arrives with no `Authorization` header
- **THEN** `ApiKeyPrincipalProvider` returns `null` and cert auth proceeds

#### Scenario: Non-Bearer authorization header
- **WHEN** a request arrives with `Authorization: Basic dXNlcjpwYXNz`
- **THEN** `ApiKeyPrincipalProvider` returns `null` immediately

### Requirement: JWT header parsing and issuer/algorithm pre-check
Hub SHALL parse only the JWT header (without verifying the signature) to extract `kid`, `alg`, and `iss`. If `alg` is not `HS256` or `iss` does not match the configured `jwt.issuer` value, the provider SHALL return `null` immediately, allowing fallback to `CertificatePrincipalProvider`.

#### Scenario: Wrong algorithm
- **WHEN** a JWT header contains `"alg": "RS256"`
- **THEN** `ApiKeyPrincipalProvider` returns `null` without any SM or DynamoDB call

#### Scenario: Wrong issuer
- **WHEN** a JWT header contains an `iss` that does not match `jwt.issuer`
- **THEN** `ApiKeyPrincipalProvider` returns `null` without any SM or DynamoDB call

#### Scenario: HS256 with matching issuer
- **WHEN** a JWT header contains `"alg": "HS256"` and `iss` matches `jwt.issuer`
- **THEN** validation proceeds to the secret-cache lookup step

### Requirement: Secret cache lookup by `kid`
Hub SHALL maintain an in-memory secret cache keyed by `kid` (the Secrets Manager version ID). On a cache hit, the cached secret SHALL be used immediately without calling Secrets Manager. On a cache miss, Hub SHALL call `GetSecretValue` with `VersionId=kid` on the configured Secrets Manager secret name, store the result in cache with a configurable TTL (`jwt.secret-cache-ttl`, default 1 hour), and proceed.

#### Scenario: Secret cache hit
- **WHEN** the `kid` from the JWT header is already in the secret cache
- **THEN** no Secrets Manager call is made and the cached secret is used for signature verification

#### Scenario: Secret cache miss
- **WHEN** the `kid` is not in the secret cache
- **THEN** Hub calls `GetSecretValue(SecretId=jwt.secrets-manager-secret-name, VersionId=kid)`, stores the returned secret in cache, and uses it for verification

#### Scenario: Unknown kid (SM returns not-found)
- **WHEN** Secrets Manager has no version matching `kid`
- **THEN** `ApiKeyPrincipalProvider` returns `null`

### Requirement: HS256 signature verification
Hub SHALL verify the JWT's HMAC-SHA256 signature against the secret retrieved for the token's `kid`. A signature verification failure SHALL cause the provider to return `null`.

#### Scenario: Valid signature
- **WHEN** the JWT signature matches `HMAC-SHA256(header.payload, secret)`
- **THEN** claim validation proceeds

#### Scenario: Invalid signature
- **WHEN** the JWT signature does not match
- **THEN** `ApiKeyPrincipalProvider` returns `null`

### Requirement: Claims validation
After successful signature verification, Hub SHALL validate the following claims in order. Any failure SHALL cause the provider to return `null`:
1. `exp` has not passed (with a configurable clock skew tolerance, default 30 seconds)
2. `env` matches Hub's own environment name
3. `iss` matches the configured `jwt.issuer` (re-validated against the decoded payload)

#### Scenario: Expired token
- **WHEN** the current time exceeds the JWT `exp` claim (beyond the clock-skew tolerance)
- **THEN** `ApiKeyPrincipalProvider` returns `null`

#### Scenario: Wrong environment
- **WHEN** the JWT `env` claim does not match Hub's configured environment name
- **THEN** `ApiKeyPrincipalProvider` returns `null`

#### Scenario: Valid claims
- **WHEN** `exp` is in the future, `env` matches, and `iss` matches
- **THEN** credential cache lookup proceeds

### Requirement: Credential cache lookup by `jti`
Hub SHALL maintain an in-memory credential cache keyed by `jti`. On a cache hit, the cached value (an `ApiKeyPrincipal` or REVOKED sentinel) SHALL be returned immediately without a DynamoDB call. On a cache miss, Hub SHALL query DynamoDB for the `ApiKeyCredential` record with sort key `env#jti`.

#### Scenario: Credential cache hit — active
- **WHEN** the `jti` is in the credential cache as an `ApiKeyPrincipal`
- **THEN** that principal is returned immediately with no DynamoDB call

#### Scenario: Credential cache hit — revoked
- **WHEN** the `jti` is in the credential cache as a REVOKED sentinel
- **THEN** `ApiKeyPrincipalProvider` returns `null` immediately with no DynamoDB call

#### Scenario: Credential cache miss
- **WHEN** the `jti` is not in the credential cache
- **THEN** Hub reads the `ApiKeyCredential` record from DynamoDB by sort key `env#jti`

### Requirement: DynamoDB credential status check
When the credential cache misses, Hub SHALL read the `ApiKeyCredential` record and act on its `status`:
- **active**: construct an `ApiKeyPrincipal` from JWT claims (`sub` → `jurisdictionId`, `roles` → roles, `dns` → dns, `jti` → jti), store in credential cache with `jwt.credential-cache-ttl` (default 5 minutes), and return the principal.
- **revoked**, **expired**, or record absent: store a REVOKED sentinel in credential cache with TTL equal to the maximum possible token lifetime (1 year), and return `null`.

#### Scenario: Active credential
- **WHEN** DynamoDB returns `status = active` for the `jti`
- **THEN** an `ApiKeyPrincipal` is constructed from JWT claims and cached with 5-minute TTL, then returned

#### Scenario: Revoked credential
- **WHEN** DynamoDB returns `status = revoked` for the `jti`
- **THEN** a REVOKED sentinel is cached with max-token-lifetime TTL and `null` is returned, resulting in 401

#### Scenario: Absent credential record
- **WHEN** DynamoDB has no `ApiKeyCredential` record for `env#jti`
- **THEN** a REVOKED sentinel is cached and `null` is returned

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
- **THEN** the credential cache returns the REVOKED sentinel and Hub returns 401 without a DynamoDB call

### Requirement: Fallback to CertificatePrincipalProvider
When `ApiKeyPrincipalProvider` returns `null` (non-API-key request, wrong issuer, invalid token, revoked credential), Hub SHALL fall back to `CertificatePrincipalProvider`. mTLS certificate callers MUST continue to work unchanged.

#### Scenario: mTLS client with valid cert and no Bearer token
- **WHEN** a request arrives with a valid mTLS certificate and no `Authorization` header
- **THEN** `ApiKeyPrincipalProvider` returns `null`, `CertificatePrincipalProvider` succeeds, and the request proceeds normally

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
