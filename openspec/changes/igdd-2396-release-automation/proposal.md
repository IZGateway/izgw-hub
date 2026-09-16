## Why

Hub releases currently span separate branch-triggered workflows for version changes,
image delivery, and GitHub release documentation, leaving operations to coordinate
those outputs and risking promotion of a different image from the one verified.
[IGDD-2396](https://izgateway.atlassian.net/browse/IGDD-2396) adopts the standard/hotfix
model established in [IZGateway/izgw-transform#253](https://github.com/IZGateway/izgw-transform/pull/253),
with the adaptations needed to preserve Hub's release gates and artifact contracts.

## What Changes

- **BREAKING (release operations only):** Replace automatic releases triggered by
  `Release*` and `main` with manually dispatched standard and hotfix workflows using
  shared release logic. Retire `.github/workflows/main.yml`; retain develop push/PR
  CI, scheduled runs, and manual CI in `maven.yml`. Leave existing release branches
  untouched.
- Accept an `X.Y.Z` release version and configurable base/trunk branches, defaulting
  to `develop`/`main`. Standard releases create `release/X.Y.Z`; operators prepare
  `hotfix/X.Y.Z` from released main before dispatching a hotfix. Merge successful
  releases into the trunk, tag them `vX.Y.Z`, and merge changes back into the base.
- Preserve Maven versions `X.Y.Z-IZGW-RELEASE` and `X.Y.Z-IZGW-SNAPSHOT`. Standard
  releases accept an optional next development version, otherwise increment the
  minor version and reset the patch to zero. Hotfix back-merges retain the base
  branch's current development version.
- Require released, non-SNAPSHOT versions of both `izgw-bom` and `izgw-core`.
  Preparing those dependencies remains an explicit maintainer prerequisite; the
  workflow does not silently select or upgrade dependencies. The back-merge
  therefore leaves the base branch pinned to released dependency versions. The
  runbook lists the return to SNAPSHOT versions as an operator step after every
  standard release.
- Use the existing release GitHub App for automated repository writes. Match
  Transform's conflict preferences: release content wins conflicting hunks when
  merging into the trunk; base content wins during the back-merge. Retain visible
  warnings about hotfix changes that require manual review rather than introducing
  a mandatory manual conflict-resolution gate. Reject a trunk merge tree that
  differs from the tested candidate tree. That guard covers trunk content outside
  the candidate's ancestry, not hotfix content that the back-merge omitted.
- Build and deploy a release candidate to the existing dev ECS service. Serialize
  releases and develop CI across deployment and verification so another run cannot
  replace the candidate while it is being tested.
- Require Maven tests, an OWASP scan blocking at CVSS >= 7, ECS health, logging
  checks, and Newman integration tests to pass before APHL delivery, the trunk
  merge/tag, release Pages publication, or GitHub Release publication. Candidate
  branch and image writes can occur earlier to support deployment.
- Preserve Hub container tags `X.Y.Z-RELEASE-<run>`, `X.Y.Z-RELEASE`, `latest`, and
  `good`, and the APHL tag `izgw-hub-X.Y.Z`. Promote the exact verified image using
  its run-specific identity/digest, not a later lookup of mutable `latest`.
- Generate and commit release notes from merged PRs. Retain `docs/release` Markdown
  output and GitHub Release attachments, and publish current and versioned Maven
  Pages. The versioned path becomes `vX.Y.Z`, in place of the earlier path that
  used the complete Maven version, for example `v2.16.0-IZGW-RELEASE`. Existing
  published directories keep their original names. Successful real releases
  publish a GitHub Release automatically rather than leaving a draft for manual
  publication.
- Preserve Transform's side-effecting `dry-run`: skip APHL delivery, publish Pages
  under `/test/`, and create a draft GitHub Release. Rehearsals still write branches,
  real version tags, and GHCR/dev ECR images, including `latest`, and deploy to shared
  dev for the full Hub verification gate. Test branches do not isolate the runtime
  environment or registry.
- On failure, attempt cleanup only of Git changes and GitHub Releases created by
  that run, retain hotfix branches, and report incomplete cleanup. Do not
  automatically restore registry images, published Pages, or the deployed dev
  service; report remaining effects and manual recovery steps.
- Require standard-release, hotfix, and forced-failure/cleanup GitHub rehearsals
  using `developalm`/`mainalm` and `dry-run=true`, including dev deployment and
  Newman. Rehearsals use versions that no planned release uses, because a dry-run
  writes a real global tag. Cutting the first real release requires separate
  approval.

### Alternatives considered

Keeping a parallel legacy release path was rejected because it would leave two
owners for version changes and publication. Copying Transform unchanged was rejected
because it omits Hub's deployment/Newman gate, permits internal SNAPSHOT dependencies,
and does not preserve Hub's version and documentation formats. Isolated rehearsal
infrastructure or a no-write preview was rejected in favor of Transform's test-branch
approach against shared dev, with its side effects made explicit. Automatic rollback
of all external publications and deployments is outside the agreed scope.

## Capabilities

### New Capabilities

- `release-automation`: Operator-triggered standard and hotfix release lifecycle,
  including prerequisites, version and branch management, serialized deployment
  gates, verified-image promotion, documentation publication, rehearsals, and
  run-owned failure cleanup.

### Modified Capabilities

## Impact

- **Repository:** Add `.github/workflows/release.yml`, `hotfix.yml`, and
  `_release_common.yml`; reduce `maven.yml` to development CI and retire `main.yml`.
  Reuse the existing ECS deployment/health actions and Newman suite. Release-time
  updates affect `pom.xml`, `RELEASE_NOTES.md`, and `docs/release`; update release
  operating guidance and project CI/branching documentation with the new process.
- **Platforms and credentials:** Use existing GitHub Actions, GHCR, dev/APHL ECR,
  dev ECS, and GitHub Pages. The maintainer confirmed that
  `RELEASE_AUTOMATION_APP_ID`, `RELEASE_AUTOMATION_APP_KEY`, and the App's required
  repository/branch permissions are available. Current CI reaches protected
  branches through `secrets.ACTIONS_KEY`, so the cutover also confirms App bypass
  on the real `main` and `develop` rulesets. Test branches carry no rulesets, so a
  rehearsal cannot prove that access. Reuse existing Hub build, AWS/APHL,
  and Newman secrets. No new AWS environment or APHL runtime deployment automation
  is introduced.
- **Dependencies and compatibility:** BOM/core releases are prerequisites, not
  shared-library implementation changes. No changes are planned to `hub`, `ads`,
  `soap`, or `dynamodb` runtime behavior, database schemas, SOAP WSDL contracts, or
  REST APIs. Existing consumers retain Hub's Maven and container naming formats.
- **Rehearsal expectations:** Two current-state facts change the first rehearsal.
  Dev CI runs the dependency scanner with `continue-on-error: true`, so the
  release gate is the first blocking use of that scanner. The Newman `build` and
  `timestamp` assertion is inactive today, because the values are empty and the
  collection applies `|| ".*"`. Real metadata makes that assertion active.
- **Security and performance:** No runtime cryptography, TLS, or authentication
  changes are planned; Bouncy Castle FIPS and mTLS behavior remain unchanged.
  Workflow logs and artifacts must retain existing PHI masking and secret-file
  exclusions. There is no routing-path performance impact; serialized releases
  occupy shared dev and can delay queued CI runs.
