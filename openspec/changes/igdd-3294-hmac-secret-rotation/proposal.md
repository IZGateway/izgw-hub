## Why

[IGDD-3294](https://izgateway.atlassian.net/browse/IGDD-3294) (epic IGDD-2702, API Key use) asked
whether the HMAC secret used to sign API-key JWTs can be rotated without invalidating every
active key, and what the rotation procedure is. This change is the **spike deliverable**: a
decision record and an operational runbook. It was opened as an OpenSpec change so the
investigation sat next to the API-key changes it depends on (`igdd-2705-api-key-principal-provider`).

## What Changes

Nothing in code, configuration, or schema. The spike found that the mechanism already ships:
Config Console stamps the Secrets Manager `VersionId` into the JWT `kid` header, and Hub's
`ApiKeyPrincipalProvider.resolveSecret(kid)` reads that exact version, so any number of secret
versions can be valid at once. The only action rotation needs is operational — label the outgoing
secret version so it stays resolvable for up to 366 days. See `design.md` for the analysis; the
procedure is published to Confluence as [Runbook: Rotating the API-Key JWT HMAC Signing Secret](https://izgateway.atlassian.net/wiki/spaces/IGDD/pages/1011253250/Runbook+Rotating+the+API-Key+JWT+HMAC+Signing+Secret)
and is not kept in this repo, because a runbook is an operating procedure rather than an OpenSpec artifact.

## Capabilities

None added or modified (`skip_specs: true`). The `kid`-based secret lookup this spike relies on is
already specified under `api-key-principal-provider` ("Secret cache lookup by `kid`").

## Impact

- No Hub, Config Console, Terraform, DynamoDB, or CI changes.
- Follow-on items surfaced by the spike (JWT signing secret not under Terraform; no alert on
  `kid`-resolution failures) are listed in `design.md` § Follow-on work and are not tracked here.
