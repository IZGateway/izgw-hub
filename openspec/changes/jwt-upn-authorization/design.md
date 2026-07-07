## Context

`igdd-2705-api-key-principal-provider` introduced `ApiKeyPrincipal` with a `roles` field populated from the JWT `roles` claim, and added a corresponding fallback in `AccessControlService.isUserInRole()` that reads those roles when the DynamoDB AccessGroup lookup finds no match. This created two authorization paths:

- **mTLS cert clients**: identity = cert CN → DynamoDB AccessGroup lookup (only path)
- **JWT clients**: identity = UPN → DynamoDB AccessGroup lookup, then fall back to JWT-claim roles

The requirement has changed: both auth types must use the same single authorization path. UPN is already set as `ApiKeyPrincipal.name`, so JWT principals already flow through the DynamoDB AccessGroup lookup — the fallback is the only thing bypassing it.

## Goals / Non-Goals

**Goals:**
- Remove the JWT-claim roles fallback from `AccessControlService.isUserInRole()`
- Simplify `ApiKeyPrincipal` constructor to no longer accept or store JWT-sourced roles
- Ensure the resulting authorization model is identical for cert and JWT clients

**Non-Goals:**
- Any change to the DynamoDB AccessGroup table schema or repository
- Any change to JWT validation logic (signature, claims, credential cache)
- Any change to Config Console JWT issuance — the `roles` claim in issued tokens is silently ignored; CC does not need to be updated
- Adding JWT-specific authorization features (scopes, per-token role overrides, etc.)

## Decisions

### D1 — Remove the `ApiKeyPrincipal` instanceof fallback entirely

`AccessControlService.isUserInRole()` currently has:
```java
var principal = RequestContext.getPrincipal();
if (principal instanceof ApiKeyPrincipal apiKeyPrincipal) {
    return apiKeyPrincipal.getRoles().contains(role);
}
```

This block is removed. No replacement is needed — the `currentModelHelper.isUserInRole(user, role)` call that precedes it already performs the DynamoDB AccessGroup lookup using `user = principal.getName() = upn` for JWT clients.

**Alternative considered**: Replace the fallback with a DynamoDB-aware check that explicitly notes it's the UPN path. Rejected — the existing `currentModelHelper.isUserInRole()` call already does this. Duplicating it would add noise without value.

### D2 — Remove `roles` from `ApiKeyPrincipal` constructor, leave `IzgPrincipal.roles` field intact

The `Collection<String> jwtRoles` constructor parameter is removed from `ApiKeyPrincipal`. The inherited `IzgPrincipal.roles` field (a `TreeSet`) remains — it is part of the core model used by `RequestContext.setPrincipal()` and is not specific to JWT clients. For `ApiKeyPrincipal` instances it will always be an empty set, which is the same as `CertificatePrincipal`.

`RequestContext.setPrincipal()` calls `roles.set(izgPrincipal.getRoles())` — for both cert and JWT principals this will now be an empty set at principal-creation time. The `ADMIN` role is added to `RequestContext.getRoles()` by `AccessControlValve.updateRoles()` after a successful DynamoDB check, which is unchanged.

**Alternative considered**: Remove `roles` from `IzgPrincipal` entirely. Rejected — out of scope; `izgw-core` changes require coordination with `izgw-transform` consumers.

### D3 — Remove `roles` claim extraction from `ApiKeyPrincipalProvider`

`lookupAndCacheCredential()` currently extracts `claims.getStringListClaim("roles")` and passes it to the `ApiKeyPrincipal` constructor. Both lines are removed. The `roles` claim in the JWT payload is neither validated nor stored from this point forward — it is present in the token but ignored by Hub.

No JWT validation failure is introduced: the `roles` claim was never a required claim, and the `claims.getStringListClaim()` call was not used for any security decision.

## Risks / Trade-offs

- **JWT clients lose access until UPNs are provisioned in DynamoDB AccessGroups** → Mitigate by provisioning AccessGroup entries for all known JWT client UPNs before deploying this change. Verify in a non-production environment first.
- **Silent behavior change for existing JWT tokens with `roles` claim** → The `roles` claim is ignored after this change. Any JWT client that relied on role escalation via the token (bypassing AccessGroups) will lose access. This is intentional — the change enforces operator-controlled authorization.
- **`roles` claim in token is now dead weight** → Config Console may continue issuing it without harm, but new tooling should omit it. No urgent action required.

## Migration Plan

1. **Pre-deployment**: Verify that all active JWT client UPNs are present in the appropriate DynamoDB AccessGroups in each target environment.
2. **Deploy**: No configuration changes required. The change is self-contained to Hub code.
3. **Rollback**: Revert the three code changes and redeploy. No DynamoDB schema changes means rollback is clean.

## Open Questions

None.
