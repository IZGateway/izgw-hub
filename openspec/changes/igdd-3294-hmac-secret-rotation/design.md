# Decision Record: HMAC JWT Signing Secret Rotation (IGDD-3294)

**Type:** Spike deliverable — decision record. Hub needs no code or schema changes.
Console needs one new feature (rotation capability); see "Follow-on work" for the ticket.

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

**None on Hub.** AWS Secrets Manager already stores every version of a secret under a
distinct `VersionId`, and `GetSecretValue` can fetch any version explicitly, not just the
current one. Hub's `resolveSecret(kid)` already does this, and it works regardless of
whether the version being fetched carries a staging label — Hub reads by `VersionId`, not
by label. So Hub needs zero changes to support any number of simultaneously-valid
versions.

**Console needs a new feature.** See "Decision" below — the operational safety net (an
explicit staging label on the outgoing version at rotation time) should be applied by
code, not left to whoever runs the AWS CLI commands correctly.

*Correction (2026-09-16, per code review on PR #194):* an earlier draft of this document
claimed AWS auto-deletes a deprecated (unlabeled) secret version within ~24h of losing its
last staging label. That is **not accurate**. AWS Secrets Manager only removes deprecated
versions once a secret has **more than 100 versions**, and even then never removes a
version less than 24h old — it is a count-based cleanup, not a time-based one. See
"Will rotation actually invalidate existing active keys?" below for the corrected
analysis.

### Will rotation actually invalidate existing active keys? — No, under any realistic rotation cadence.

1. **API-key JWTs are long-lived.** Console mints a token with `exp` = the credential's
   own `expiresAt`, which is set **365 days** out at issuance
   (`izg-configuration-console/src/pages/api/apikeys/index.ts:221`,
   `.../token.ts:74`). Hub enforces a `MAX_TOKEN_LIFETIME` of **366 days**
   (`ApiKeyPrincipalProvider.java:33`). A client can hold and keep replaying a token for
   up to a year without ever calling Console's `/token` endpoint again.
2. **AWS Secrets Manager's actual retention rule is count-based, not time-based.**
   `AWSCURRENT` and `AWSPREVIOUS` are the only two versions Secrets Manager labels
   automatically, but losing a label just makes a version "deprecated" — it does not
   schedule immediate deletion. Secrets Manager only deletes deprecated versions once the
   secret accumulates **more than 100 versions total**, and never deletes any version less
   than 24h old regardless of label state. Since no key can validly sign a token for more
   than 366 days, and reaching 100 accumulated versions would require roughly 100 rotations
   within that window, **any rotation cadence a human would plausibly run (monthly,
   quarterly, even weekly) never approaches the threshold where deletion could occur.**

So the failure mode described in the original draft of this spike does not actually occur
under normal use — there is no evidence the secret has ever been rotated in dev, so this
was never observed either way. That said, "safe because we're unlikely to hit an
undocumented AWS threshold" is not something to depend on indefinitely (e.g. a
misconfigured retry loop or an automated rotation schedule could plausibly accumulate
versions faster than expected). The fix costs nothing and removes the dependency entirely:
explicitly label the outgoing version at rotation time so it can never become
"deprecated" in the first place. That's the actual recommendation — see "Decision" below.

### What does the rotation procedure look like operationally?

See `runbook.md` in this same directory. The Console-side rotation feature (new,
follow-on work — see below) should apply the staging label automatically as part of
rotation. The runbook documents the manual/fallback procedure and the criteria for safely
releasing a retired version after ~366 days.

### Is a DynamoDB schema change required?

**No.** Secret versioning lives entirely inside AWS Secrets Manager, addressed by `kid`
in the JWT header. Nothing about the secret or its version is stored on
`ApiKeyCredential` or any other DynamoDB item — that table only ever deals with `jti`
(the token), not `kid` (the signing key generation used to produce it). No schema change,
migration, or new attribute is needed for rotation.

## Decision

**Rotation is supported using the existing `kid` / Secrets Manager mechanism — Hub needs
no changes.** Console needs one new feature: a rotation capability that generates a new
secret value and explicitly labels the outgoing version, mirroring the existing password
encryption key rotation (`izg-configuration-console/src/pages/api/rotatekey/index.ts` +
`src/lib/security/crypto/DbCrypto.ts::rotateKey` + the admin trigger in
`PasswordEncryptionCard.tsx`). This is not required to prevent invalidating active keys
under realistic rotation cadences (see above), but it removes the dependency on an
undocumented AWS cleanup threshold and gives operators one button instead of a multi-step
AWS CLI runbook for the common case. Filed as a follow-on story — see below.

## Follow-on work

- **New story: Console — JWT signing secret rotation capability.** Genuine new feature
  work, not a doc/ops gap. Drafted as a follow-on ticket (see PR/ticket description);
  scope: new Console endpoint that calls `PutSecretValue` then `UpdateSecretVersionStage`
  to label the outgoing version, plus an admin UI trigger. No Hub-side change needed.
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
| AWS Secrets Manager deletes deprecated versions only past a 100-version count threshold (never <24h old) — corrects this doc's earlier ~24h claim | AWS Secrets Manager documentation (`UpdateSecretVersionStage` / staging label lifecycle); flagged in PR #194 code review |
| Existing precedent for an admin-triggered key rotation feature in Console | `izg-configuration-console/src/pages/api/rotatekey/index.ts`, `src/lib/security/crypto/DbCrypto.ts::rotateKey` |
