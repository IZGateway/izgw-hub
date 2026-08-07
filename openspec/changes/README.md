# Active OpenSpec Changes — Reading Guide

## How to read this directory

This directory holds **deltas**, not current-state requirements. Each change contains
`## ADDED / MODIFIED / REMOVED Requirements` sections describing what that change alters.
A change is a point-in-time proposal: it records what was reviewed and approved *then*.

Current-state requirements live in `openspec/specs/`. When a change is implemented and
archived, its deltas are applied into `openspec/specs/` and the change moves to
`changes/archive/` as history.

> **Important caveat today:** `openspec/specs/` currently contains only the ADS metadata
> specs (`compute-*.md`, `filename-validation.md`). **No API-key capability has been synced
> yet**, because the API-key chain is still mid-implementation. Until it is archived, the
> change directories below are the only description of API-key authentication — so they must
> be read as a **stack**, in order, not individually. Reading any one of them alone will give
> you a stale picture.

Do not "fix" an older change's delta to match a newer decision. Doing so destroys the
approval record and leaves the newer change's `## MODIFIED Requirements` block modifying
text that no longer exists. Later deltas supersede earlier ones by design.

## The API-key authentication stack

Read in this order. Later entries supersede earlier ones.

| # | Change | Jira | Implementation state |
|---|--------|------|----------------------|
| 1 | `igdd-2705-api-key-principal-provider` | IGDD-2705 | Mostly shipped; tasks 1.1, 1.2, 4.5–4.7, 9.5, 9.9 **re-opened** by the IGDD-3140 model change |
| 2 | `igdd-2711-grace-period-revocation` | IGDD-2711 | Mostly shipped; tasks 1.2, 1.3, 6.1 **re-opened** by the same |
| 3 | `jwt-upn-authorization` | — | Fully implemented and merged to `develop` |

### Net current model (what the stack actually says)

- **Sort key is `{jti}` alone** — no environment prefix. Lookup is
  `ApiKeyCredentialRepository.findByJti(jti)`.
- **The JWT is identity-only**: `iss`, `kid` (header), `jti`, `sub` (jurisdictionId), `upn`,
  `iat`, `exp`. There is **no `env` claim** and **no usable `roles` claim**.
- **Environment authorization is server-side** — an `environments` list on the
  `ApiKeyCredential` record, checked at routing time against `SystemUtils.getDestType()`.
- **Roles do not come from the token.** Entry 3 removed JWT-claim roles entirely.
  `ApiKeyPrincipal` carries no roles; `AccessControlValve` resolves roles from the DynamoDB
  AccessGroup table keyed on `principal.getName()` — the `upn` for JWT callers, the CN for
  mTLS callers. Entry 1's delta still shows the old `roles` → principal mapping; that is
  history, superseded by entry 3.
- **Usable statuses** are `active` and `grace_period`. `expired` is not stored — it is
  derived on the Console side.

### Known gap: use-type enforcement

The stack does **not** cover `credential.useTypes ∩ destination Jurisdiction.allowedUseTypes`.
That enforcement is tracked under **IGDD-3257** (*IZG Hub: Enhancement to API-key
authentication with useTypes*), which also owns the new `izgw-core` SecurityFault. See
`igdd-2705-api-key-principal-provider/design.md` § D10 for the model and why it is deferred.
Related: **IGDD-3258** (DynamoDB API-key data migration & sender seeding).

### Archiving

The three changes above must be archived **as a unit**, after the IGDD-3140 code rework
lands and the re-opened tasks close. They cannot be split: entry 3 *modifies* a requirement
that entry 1 *adds*, so until entry 1 is in `openspec/specs/` there is nothing for entry 3 to
modify.

## Other active changes

| Change | Jira | Notes |
|--------|------|-------|
| `fix-ndlp-folder-paths` | — | Has no `specs/` deltas, so `openspec validate` reports it as failing. Pre-existing; unrelated to the API-key work. |
