# Decision Record: HMAC JWT Signing Secret Rotation (IGDD-3294)

**Type:** Spike deliverable — decision record. No code or schema changes accompany this
document; see "Follow-on work" for what (if anything) should be ticketed next.

**Epic:** IGDD-2702 (API Key use)

## Objective

Determine how the HMAC signing secret used for API key JWTs can be rotated without
invalidating all existing active keys, and document the procedure.

## Key questions (from the ticket)

### Is `kid` stored in the JWT header, and can Hub use it to look up the signing secret version?

Yes — and this is not new work, it already ships on `develop`:

- Config Console signs every API-key JWT with a `kid` header set to the **AWS Secrets
  Manager `VersionId`** of the secret it just fetched
  (`izg-configuration-console/src/lib/apikeys/jwt.ts:24-33`, `signJwt` at line 14 puts
  `kid` in the header).
- Hub reads that header and resolves the secret for that exact version:
  `ApiKeyPrincipalProvider.java:125` (`signedJwt.getHeader().getKeyID()`) →
  `resolveSecret(kid)` at line 249, which calls
  `secretsManagerClient.getSecretValue(req -> req.secretId(...).versionId(kid))`
  (lines 270-272) — an explicit-version read, not "give me whatever is current."
- Results are cached per-`kid` (`secretCache`, TTL = `jwt.secret-cache-ttl`, default 1h —
  `JwtConfig.java:24`) and unknown `kid`s are negatively cached for 60s
  (`NEGATIVE_SECRET_CACHE_TTL`, line 41) to avoid hammering Secrets Manager on garbage
  tokens.
- Confirmed merged to `develop` via commit `506b38f58` ("Security hardening from code
  review of JWT/API key implementation"), part of the IGDD-2705 line. The
  `igdd-2705-api-key-principal-provider/design.md` (lines 10-11, 32-33) already documents
  this as intentional design, not an accident: *"Validate HS256 JWTs issued by Config
  Console with `kid`-based Secrets Manager lookup."*

**So the mechanism this spike was asked to find already exists and is live in dev.** The
open question is not "can it be built" but "does it actually survive a real rotation,"
answered below.

### What is the minimum code change needed to support multiple active secret versions simultaneously?

**None.** AWS Secrets Manager already stores every version of a secret under a distinct
`VersionId`, and `GetSecretValue` can fetch any version explicitly, not just the current
one. Hub's `resolveSecret(kid)` already does this. Console already reads whatever is
current (`GetSecretValueCommand` with no `VersionId` returns `AWSCURRENT`) and stamps its
`VersionId` as `kid` for newly-minted tokens. Rotation is therefore possible with **zero
changes to Hub or Console** — it is purely an operational action against Secrets Manager,
*provided* the version being retired is not allowed to be garbage-collected before every
token signed with it has expired. That caveat is the actual finding of this spike:

### Will rotation actually invalidate existing active keys? — Yes, if done naively.

Two facts collide:

1. **API-key JWTs are long-lived.** Console mints a token with `exp` = the credential's
   own `expiresAt`, which is set **365 days** out at issuance
   (`izg-configuration-console/src/pages/api/apikeys/index.ts:221`,
   `.../token.ts:74`). Hub enforces a `MAX_TOKEN_LIFETIME` of **366 days**
   (`ApiKeyPrincipalProvider.java:33`). A client can hold and keep replaying a token for
   up to a year without ever calling Console's `/token` endpoint again.
2. **AWS Secrets Manager does not retain old versions indefinitely.** Only two versions
   are protected by automatically-managed staging labels at any time — `AWSCURRENT` and
   `AWSPREVIOUS`. When `PutSecretValue` creates a new current version, the version that
   *was* `AWSPREVIOUS` loses its last label and becomes "deprecated." AWS documents that
   deprecated versions are automatically deleted, typically **within ~24 hours**.

Putting these together: after a **first** rotation, the previously-current secret becomes
`AWSPREVIOUS` and stays resolvable — outstanding tokens signed with it keep validating.
After a **second** rotation (any time within the ~366-day life of tokens from the first
secret), that version loses its label entirely and Secrets Manager deletes it within
about a day. Any client still holding a token whose `kid` points at that version starts
failing authentication — `resolveSecret` returns null, logged as `"JWT rejected: unable
to resolve signing secret for kid={}"` (line 132) after Secrets Manager throws
`ResourceNotFoundException` (line 276, `"Secrets Manager: no version found for
kid={}"`). **This is exactly the failure mode the ticket asked us to avoid**, just
delayed until the second rotation rather than the first — which is why it hasn't been
noticed yet (the secret has, as far as I can tell, never been rotated in dev).

### What does the rotation procedure look like operationally?

See `runbook.md` in this same directory. In short: rotation is safe as long as every
secret version that might still be referenced by a live token is protected from Secrets
Manager's automatic cleanup with an explicit custom staging label, held for at least 366
days (or until independently confirmed no traffic uses that `kid` anymore) before being
released.

### Is a DynamoDB schema change required?

**No.** Secret versioning lives entirely inside AWS Secrets Manager, addressed by `kid`
in the JWT header. Nothing about the secret or its version is stored on
`ApiKeyCredential` or any other DynamoDB item — that table only ever deals with `jti`
(the token), not `kid` (the signing key generation used to produce it). No schema change,
migration, or new attribute is needed for rotation.

## Decision

**Rotation is supported using the existing `kid` / Secrets Manager mechanism.** No Hub or
Console code changes are required. The missing piece was operational discipline, not
capability: a rotation must explicitly manage Secrets Manager staging labels so a
version isn't garbage-collected while tokens signed with it are still valid. That
procedure is documented in `runbook.md`.

## Open items / follow-on work

These are gaps in *ownership and verification*, not in the mechanism, surfaced while
tracing this end-to-end. Flagging here per the ticket's "follow-on tasks or spikes are
created and linked" deliverable — not yet ticketed, pending confirmation.

- **The JWT signing secret is not in Terraform at all.** Every other Hub-managed secret
  (e.g. `password_encrypt_key` in `iz-gateway-terraform/hub/service/secrets.tf`) has an
  `aws_secretsmanager_secret` resource, and that one is even explicitly multi-region
  (`replica { region = var.aws_region_2 }`). The JWT secret (`/izg/dev/jwt/signing-secret`
  per `terraform.tfvars:24`) has no matching resource anywhere in the repo — it appears to
  have been created by hand. Two consequences worth confirming with AWS access before
  relying on the runbook in prod:
  - Whether it's replicated to `us-west-2` (Hub runs multi-region; an un-replicated
    secret would mean region-2 Hub instances can't resolve any `kid` at all, rotation or
    not — this would be a pre-existing bug, not one this spike introduces, but rotation
    procedure needs to know which region(s) to write to).
  - Who/what currently holds `secretsmanager:PutSecretValue` and
    `secretsmanager:UpdateSecretVersionStage` on it, since nothing in Terraform grants or
    documents that access.
- **No monitoring exists for kid-resolution failures.** `ApiKeyPrincipalProvider` already
  logs the exact signal that would show a botched rotation (`"unable to resolve signing
  secret for kid"` / `"no version found for kid"`), but nothing watches for it — same
  situation the IGDD-2711 runbook describes for its own job, where alerting was
  deliberately deferred. Worth deciding whether this warrants a CloudWatch metric filter
  now, given a miss here means live traffic getting 401s.
- **Consider bringing the secret under Terraform management** so its existence,
  replication, and IAM grants are auditable the same way `password_encrypt_key` is,
  rather than living only as a manually-created resource referenced by name.

## Evidence index

| Claim | Source |
|---|---|
| `kid` = Secrets Manager VersionId, set at signing | `izg-configuration-console/src/lib/apikeys/jwt.ts:14,24-33` |
| Hub resolves secret by exact `kid`/VersionId | `izgw-hub/src/main/java/gov/cdc/izgateway/hub/security/ApiKeyPrincipalProvider.java:125-133,249-274` |
| Mechanism is documented intentional design, already merged | `izgw-hub/openspec/changes/igdd-2705-api-key-principal-provider/design.md:10-11,32-33`; commit `506b38f58` |
| Token lifetime ~366 days | `ApiKeyPrincipalProvider.java:33` (`MAX_TOKEN_LIFETIME`); `izg-configuration-console/src/pages/api/apikeys/index.ts:221`, `token.ts:74` |
| Secret path, not in Terraform as a managed resource | `iz-gateway-terraform/hub/service/{ecs.tf:179-181, variables.tf:175, terraform.tfvars:24}`; absence confirmed against `hub/service/secrets.tf` |
| AWS Secrets Manager deprecated-version auto-deletion (~24h) | AWS Secrets Manager documentation (staging labels / `UpdateSecretVersionStage`) |
