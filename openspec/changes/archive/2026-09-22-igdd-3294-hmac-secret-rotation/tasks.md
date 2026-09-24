# Tasks: igdd-3294-hmac-secret-rotation

Jira: [IGDD-3294](https://izgateway.atlassian.net/browse/IGDD-3294) (epic IGDD-2702, API Key use).
This is a spike. The deliverables are a decision, a documented procedure, and linked follow-on
tickets — not code. No Hub or Config Console change was needed.

## 1. Investigation (the ticket's questions)

- [x] 1.1 Determine whether `kid` is in the JWT header and whether Hub can use it to look up the
      signing secret version. **Yes, and it already ships** — Config Console sets `kid` to the
      Secrets Manager `VersionId` at signing time; Hub reads it
      (`ApiKeyPrincipalProvider.java:125`) and fetches that exact version (`resolveSecret`,
      `:249-274`), not "whatever is current".
- [x] 1.2 Determine the minimum code change needed to support several simultaneously-valid secret
      versions. **None**, on Hub or on Config Console. Secrets Manager already keeps every version
      and `GetSecretValue` can read any of them by `VersionId`.
- [x] 1.3 Determine whether rotation invalidates existing active keys. **No**, under any realistic
      rotation cadence. Tokens live up to 366 days (`ApiKeyPrincipalProvider.MAX_TOKEN_LIFETIME`,
      `:33`), and Secrets Manager removes a deprecated version only once a secret passes a
      100-version count threshold, never while a version is under 24h old.
- [x] 1.4 Determine whether a DynamoDB schema change is required. **No** — secret versioning lives
      entirely in Secrets Manager and is addressed by `kid`. No `ApiKeyCredential` attribute
      records the signing key generation.

## 2. Deliverables

- [x] 2.1 Decision record — `design.md`, with the evidence index and the 2026-09-16 correction to
      an earlier draft's "AWS deletes unlabeled versions within ~24h" claim (PR #194 review).
- [x] 2.2 Rotation procedure. Published to Confluence (2026-09-22):
      [Runbook: Rotating the API-Key JWT HMAC Signing Secret](https://izgateway.atlassian.net/wiki/spaces/IGDD/pages/1011253250/Runbook+Rotating+the+API-Key+JWT+HMAC+Signing+Secret).
      It is not kept in this repo, because a runbook is an operating procedure rather than an
      OpenSpec artifact.
- [x] 2.3 Follow-on tickets created and linked — see section 3.

## 3. Follow-on tickets

The spike surfaced gaps in ownership and verification, not in the rotation mechanism. Both are
tracked outside this change and neither blocks it:

- [x] 3.1 [IGDD-3489](https://izgateway.atlassian.net/browse/IGDD-3489) — Bring JWT signing secret
      under Terraform management. Covers the design's finding that the secret has no
      `aws_secretsmanager_secret` resource, so neither its replication to `us-west-2` nor the IAM
      grants for `secretsmanager:PutSecretValue` / `UpdateSecretVersionStage` can be audited from
      code. The rotation procedure needs to know which regions to write to.
- [x] 3.2 [IGDD-3490](https://izgateway.atlassian.net/browse/IGDD-3490) — Alert on JWT
      kid-resolution failures (API key auth). Covers the design's finding that
      `ApiKeyPrincipalProvider` already logs the exact signal a botched rotation produces
      (`"unable to resolve signing secret for kid"`, `:132`; `"no version found for kid"`, `:276`)
      but nothing watches for it.

## Not done (out of scope)

- No Hub, Config Console, Terraform, DynamoDB, or CI change. The spike's conclusion is that
  rotation needs none. Terraform management of the secret is IGDD-3489's scope, not this change's.
- No rotation has been performed in any environment. The procedure is documented, not exercised.
