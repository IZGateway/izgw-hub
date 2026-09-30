## Why

JWT client authorization currently uses roles embedded in the JWT claim, creating a separate authorization path from mTLS cert clients who are authorized via DynamoDB AccessGroup lookup. The requirement has changed: JWT clients must be authorized the same way as mTLS cert clients — using the UPN (which maps to the same DNS identity as the cert CN) as the lookup key in the DynamoDB AccessGroup table.

## What Changes

- **Removed**: `ApiKeyPrincipal` instanceof fallback in `AccessControlService.isUserInRole()` — JWT principals now use the same DynamoDB AccessGroup path as cert principals
- **Removed**: `Collection<String> jwtRoles` constructor parameter from `ApiKeyPrincipal` — the principal no longer carries JWT-sourced roles
- **Removed**: `roles` claim extraction from `ApiKeyPrincipalProvider.lookupAndCacheCredential()` — the `roles` claim is no longer read from the JWT
- **Operational**: JWT client UPNs must be present in the DynamoDB AccessGroup table for authorization to succeed (same requirement as mTLS cert CNs)

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `api-key-principal-provider`: Authorization model changes — `ApiKeyPrincipal` no longer carries JWT-claim roles; `AccessControlService.isUserInRole()` no longer has a special fallback for JWT principals; UPN-based DynamoDB AccessGroup lookup is the sole authorization path for JWT clients.

## Impact

- **`AccessControlService`** — `isUserInRole()` loses the `ApiKeyPrincipal` fallback branch; JWT and cert principals now follow an identical code path
- **`ApiKeyPrincipal`** — constructor simplified; `roles` field will always be empty (inherited from `IzgPrincipal` default)
- **`ApiKeyPrincipalProvider`** — `lookupAndCacheCredential()` no longer extracts or passes `roles` from JWT claims
- **Config Console** — `roles` claim in issued JWTs is now ignored by Hub; CC may continue emitting it without harm but no longer needs to
- **DynamoDB AccessGroup table** — becomes the single source of truth for both auth types; JWT client UPNs must be provisioned into AccessGroups
- **Tests** — any test asserting JWT-claim-sourced role resolution must be updated to use DynamoDB AccessGroup lookup instead
