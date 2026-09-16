# Runbook: Rotating the API-Key JWT HMAC Signing Secret (IGDD-3294)

Operational procedure for rotating the shared HS256 secret that Config Console uses to
sign API-key JWTs and Hub uses to verify them. See `design.md` in this directory for the
decision and evidence behind this procedure — read that first if you haven't.

> **Note:** This is a draft artifact kept with the spike, following the same convention as
> `openspec/changes/igdd-2711-grace-period-revocation/runbook.md`. Relocate to the team's
> canonical runbook location (Confluence / ops docs) when finalized.

## The one rule

**Label the outgoing secret version at rotation time, and don't remove that label for at
least 366 days.** A version with no staging label is "deprecated," and Secrets Manager
*can* delete deprecated versions — but only once a secret accumulates more than 100
versions, and never before a version is 24h old. It is not a short fixed window, and
under any realistic rotation cadence you will never come close to 100 accumulated
versions within a token's 366-day life
(`ApiKeyPrincipalProvider.MAX_TOKEN_LIFETIME`) — so skipping this step is unlikely to
actually break anything today. Labeling anyway is cheap insurance that removes the
dependency on an undocumented AWS threshold entirely, which is why every step below keeps
a label on every version that's still "in flight." (A Console feature to automate this
labeling step is planned as follow-on work — see `design.md`. Until it ships, this manual
procedure is the primary path.)

## Prerequisites

- `secretsmanager:PutSecretValue`, `secretsmanager:UpdateSecretVersionStage`,
  `secretsmanager:GetSecretValue`, and `secretsmanager:ListSecretVersionIds` on the
  target secret ARN (the secret named by `jwt.secrets-manager-secret-name` / the
  `JWT_SECRET_NAME` task-def env var — `/izg/dev/jwt/signing-secret` in dev per
  `iz-gateway-terraform/hub/service/terraform.tfvars:24`).
- Confirm which region(s) hold the secret before starting — see the open item in
  `design.md` about the secret not being modeled in Terraform (unlike
  `password_encrypt_key`, it has no `replica` block anywhere to confirm from code).
  If Hub runs multi-region and the secret is single-region, every step below must be
  repeated in each region Hub reads from.

## Procedure

1. **Identify the version being retired.** Before creating a new secret value, note the
   current `AWSCURRENT` version's `VersionId` — this is the `kid` every token issued up to
   this point carries:
   ```
   aws secretsmanager get-secret-value --secret-id <secret-name> --query VersionId
   ```
2. **Label it explicitly.** Attach a custom staging label to that version so it stays
   labeled (and therefore never "deprecated") even after a future rotation demotes it
   below `AWSPREVIOUS`:
   ```
   aws secretsmanager update-secret-version-stage \
     --secret-id <secret-name> \
     --version-stage RETAIN-<yyyy-mm-dd> \
     --move-to-version-id <version-id-from-step-1>
   ```
   Do this **before** step 3 on every rotation, including the first — the whole point is
   that this label survives past the point where `AWSPREVIOUS` would normally drop it.
3. **Write the new secret value.**
   ```
   aws secretsmanager put-secret-value --secret-id <secret-name> --secret-string <new-value>
   ```
   This creates a new version, which AWS automatically marks `AWSCURRENT`; the version
   that was `AWSCURRENT` automatically becomes `AWSPREVIOUS` (in addition to the
   `RETAIN-*` label already on it from step 2, so it now carries two labels — that's
   fine).
4. **No deploy needed.** Config Console fetches whatever is `AWSCURRENT` on every token
   mint (`getJwtSigningSecret()` in `izg-configuration-console/src/lib/apikeys/jwt.ts:24-33`,
   no `VersionId` specified) and stamps its `VersionId` as `kid`. The very next token any
   client requests is signed with the new secret automatically. Hub needs no restart
   either — it resolves whatever `kid` shows up on each incoming token, on demand.
5. **Verify.** Mint a token via Console's `/token` endpoint and confirm it authenticates
   against Hub. Confirm the retired version still validates too, if you have an existing
   token to test with (or via `resolveSecret`'s cache — same code path, older `kid`).

## Releasing a retained version

Do this only once you're confident no live token still references the version, i.e. **at
least 366 days** after it stopped being `AWSCURRENT`, or sooner if you can independently
confirm (e.g. via Hub's `resolveSecret` cache metrics, or by cross-referencing every
active `ApiKeyCredential`'s issuance time in DynamoDB) that nothing outstanding was signed
against it.

```
aws secretsmanager update-secret-version-stage \
  --secret-id <secret-name> \
  --version-stage RETAIN-<yyyy-mm-dd> \
  --remove-from-version-id <version-id>
```

Once a version has no labels at all, Secrets Manager deprecates and deletes it
automatically — no separate delete call needed. There is no cost or functional harm in
leaving `RETAIN-*` labels attached longer than necessary beyond minor Secrets Manager
version clutter; when in doubt, wait.

## Rollback

If the new secret value was wrong (typo, wrong format) and no client has picked it up
yet, move `AWSCURRENT` back to the previous version:
```
aws secretsmanager update-secret-version-stage \
  --secret-id <secret-name> \
  --version-stage AWSCURRENT \
  --move-to-version-id <previous-version-id>
```
This is safe because step 2 already protected that version with a `RETAIN-*` label, so
it's guaranteed to still exist regardless of how long ago the mistake was made.

## Monitoring gap (flagged, not yet built)

`ApiKeyPrincipalProvider` already logs the exact signal a botched rotation would produce
— `"JWT rejected: unable to resolve signing secret for kid={}"`
(`ApiKeyPrincipalProvider.java:132`) and `"Secrets Manager: no version found for
kid={}"` (line 276) — but nothing currently alerts on it, the same gap the IGDD-2711
runbook flagged for its own job. If this rotation procedure sees real use, a CloudWatch
Logs metric filter on either message (alarm `>= 1`) would catch a version released too
early, before it shows up as a support ticket from a customer whose integration silently
started failing.
