## Purpose

Provide an operator-triggered standard and hotfix release process that preserves
Hub's version formats, deployment gates, and publication outputs while following
the Transform release model. This capability covers release operations, not
changes to runtime message processing or application retry behavior.

## ADDED Requirements

### Requirement: Manual release entry points replace legacy release triggers

The repository SHALL provide separate manual standard-release and hotfix-release
entry points. Pushes and pull requests to legacy `Release*` branches or `main`
SHALL NOT independently start another release. Develop push/PR CI, scheduled CI,
and manual CI SHALL remain available without performing release publication.
The cutover SHALL NOT modify or delete existing release branches.

#### Scenario: Operator starts a standard release
- GIVEN the release workflows are available on the selected base branch
- WHEN an operator dispatches a standard release
- THEN one release execution handles that request
- AND branch updates made by that execution do not start a second release

#### Scenario: Existing development and legacy branches
- GIVEN the new release entry points have replaced the legacy release path
- WHEN develop CI runs or an existing legacy release branch receives a push
- THEN develop CI retains its existing build, deployment, and verification role
- AND the legacy branch push does not independently publish a release
- AND existing release branches are not removed by the cutover

### Requirement: Release inputs retain Hub version syntax

Both entry points SHALL accept `release-version` as three non-negative integer
components in `X.Y.Z` form, `base-branch` defaulting to `develop`, `trunk-branch`
defaulting to `main`, and boolean `dry-run` defaulting to `false`. Standard
releases SHALL additionally accept optional `next-snapshot-version` in `X.Y.Z`
or `X.Y.Z-IZGW-SNAPSHOT` form. Empty or omitted next-version input SHALL select
the automatic version increment. Invalid input SHALL fail with an explicit
explanation before release-controlled repository, registry, or deployment writes.

Example valid standard-release inputs:

```json
{
  "release-version": "2.17.0",
  "next-snapshot-version": "2.18.0-IZGW-SNAPSHOT",
  "base-branch": "develop",
  "trunk-branch": "main",
  "dry-run": false
}
```

Example invalid standard-release inputs:

```json
{
  "release-version": "v2.17.0",
  "next-snapshot-version": "2.18"
}
```

#### Scenario: Valid release inputs
- GIVEN the valid input object above and all other prerequisites are satisfied
- WHEN the operator starts the release
- THEN the release version is `2.17.0`
- AND the next development version is `2.18.0-IZGW-SNAPSHOT`

#### Scenario: Invalid release version
- GIVEN `release-version` is empty, `2.17`, `v2.17.0`, or `2.17.0-IZGW-RELEASE`
- WHEN the request is validated
- THEN the run fails with a release-version format error
- AND no release-controlled writes occur

#### Scenario: Invalid explicit next version
- GIVEN a standard release specifies `next-snapshot-version` as `2.18`
- WHEN the request is validated
- THEN the run fails with a next-version format error
- AND no release-controlled writes occur

### Requirement: Dispatch context and release identity are validated

A standard release SHALL run from the configured base branch and create
`release/X.Y.Z` for the requested version. A hotfix SHALL run from an
operator-prepared `hotfix/X.Y.Z` branch originating from released trunk history,
with its version matching the request. Selected branch names SHALL be valid
branch references and base and trunk SHALL be distinct. Existing standard-release
branches, version tags, or GitHub Releases for the requested version SHALL cause
an explicit failure without altering those existing objects.

#### Scenario: Standard release dispatched from the wrong branch
- GIVEN `base-branch` is `develop` and the workflow is dispatched from `main`
- WHEN the dispatch context is validated
- THEN the run fails before creating a release branch or publishing an image

#### Scenario: Matching hotfix branch
- GIVEN the operator prepared `hotfix/2.17.1` from released trunk history
- WHEN a hotfix for `2.17.1` is dispatched from that branch
- THEN that existing branch is used as the release source
- AND the workflow does not create a standard-release branch

#### Scenario: Hotfix branch does not match its requested version
- GIVEN the workflow is dispatched from `hotfix/2.17.1`
- WHEN the requested hotfix version is `2.17.2`
- THEN the run fails with a branch/version mismatch
- AND the operator's branch remains unchanged

#### Scenario: A requested release already exists
- GIVEN `release/2.17.0`, tag `v2.17.0`, or its GitHub Release already exists
- WHEN a standard release for `2.17.0` is requested
- THEN the run reports the conflicting object and fails
- AND the existing object is not overwritten or removed by failure cleanup

### Requirement: Shared dependencies are released before Hub

The release SHALL require non-SNAPSHOT resolved versions of both the parent
`izgw-bom` and `izgw-core`. An unresolved version or a SNAPSHOT version SHALL fail
before release-controlled writes. The workflow SHALL identify the offending
dependency and SHALL NOT automatically select or upgrade its version.

#### Scenario: Released dependencies
- GIVEN both the resolved BOM and core versions are non-SNAPSHOT versions
- WHEN dependency prerequisites are evaluated
- THEN these prerequisites permit the release to continue

#### Scenario: Either shared dependency is a SNAPSHOT
- GIVEN either the BOM or core resolves to a version ending in `-SNAPSHOT`
- WHEN dependency prerequisites are evaluated
- THEN the run fails and identifies that dependency
- AND it directs the maintainer to prepare a released dependency version
- AND it does not change the dependency declaration

#### Scenario: Dependency version cannot be resolved
- GIVEN a required shared dependency version cannot be resolved
- WHEN dependency prerequisites are evaluated
- THEN the run fails rather than assuming that the dependency is released

### Requirement: Standard and hotfix versions follow Hub conventions

The candidate and released trunk SHALL use Maven version `X.Y.Z-IZGW-RELEASE`.
A successful standard release SHALL update the base branch to the explicit next
version with exactly one `-IZGW-SNAPSHOT` suffix, or to the next minor version
with patch zero when no override is supplied. A hotfix back-merge SHALL preserve
the base branch's current development version.

#### Scenario: Automatic next minor version
- GIVEN a standard release of `2.17.3` with no explicit next version
- WHEN the release completes its base-branch update
- THEN the released version is `2.17.3-IZGW-RELEASE`
- AND the base version is `2.18.0-IZGW-SNAPSHOT`

#### Scenario: Explicit next version
- GIVEN a standard release supplies either `2.17.4` or `2.17.4-IZGW-SNAPSHOT`
- WHEN the next development version is applied
- THEN the base version is `2.17.4-IZGW-SNAPSHOT` in both cases

#### Scenario: Hotfix does not replace the development version
- GIVEN the base branch is developing `2.18.0-IZGW-SNAPSHOT`
- WHEN hotfix `2.17.1` is released and merged back
- THEN the released version is `2.17.1-IZGW-RELEASE`
- AND the base version remains `2.18.0-IZGW-SNAPSHOT`

### Requirement: Release repository writes use the release App

Release branch, trunk, base, tag, GitHub Release, and Pages writes SHALL use the
configured release GitHub App identity. Missing App credentials or denied write
authorization SHALL be reported as failures rather than silently using another
identity. Credential values SHALL NOT appear in diagnostics.

#### Scenario: App credentials are unavailable
- GIVEN either required release App credential is unavailable
- WHEN the workflow attempts to establish its release identity
- THEN the run fails before release repository writes
- AND it identifies the missing credential by name without disclosing values

#### Scenario: A release write is denied
- GIVEN the App cannot perform a required repository write
- WHEN that write is attempted
- THEN the release is marked failed
- AND recovery follows the run-owned cleanup policy

### Requirement: Shared dev use is serialized with development CI

Real releases, dry-runs, and develop CI SHALL NOT overlap operations that can
replace the shared dev candidate or its registry aliases during another run's
deployment, verification, promotion, or cleanup. A later dispatch SHALL NOT
cancel an active release merely to acquire shared dev.

#### Scenario: CI arrives during a release
- GIVEN a release owns shared dev and is running Newman
- WHEN a develop CI run becomes ready to publish or deploy
- THEN that CI run does not replace the active candidate
- AND the release retains exclusive shared-dev use through promotion or cleanup

#### Scenario: A dry-run arrives during CI
- GIVEN develop CI is verifying its deployed candidate
- WHEN a release dry-run is dispatched
- THEN the dry-run waits to acquire shared dev before conflicting writes
- AND it does not cancel the active run

### Requirement: All Hub release gates block completion

A release SHALL pass Maven tests, OWASP dependency scanning, deployment to the
existing dev ECS service, ECS health checks, logging checks, and Newman integration
tests. Unsuppressed findings with CVSS >= 7 SHALL block release completion.
An unsuccessful scan, failed test setup, or failed verification SHALL NOT count
as a pass. APHL delivery, the trunk merge/tag, release Pages publication, and
GitHub Release publication SHALL occur only after all gates pass. Candidate
branch and image writes needed for deployment can precede these gates.

#### Scenario: Candidate passes every gate
- GIVEN the candidate was deployed and each required gate passed
- WHEN the release continues
- THEN promotion and release publication are permitted

#### Scenario: Vulnerability reaches the blocking threshold
- GIVEN the scan reports an unsuppressed vulnerability with CVSS `7.0`
- WHEN the scan result is evaluated
- THEN the release gate fails
- AND APHL delivery, the trunk merge/tag, and release publication do not occur

#### Scenario: Scan cannot complete
- GIVEN dependency scanning terminates without a successful result
- WHEN the release evaluates its gates
- THEN the missing result is treated as a failure, not as zero vulnerabilities
- AND release completion remains blocked

#### Scenario: Runtime verification or its setup fails
- GIVEN ECS health, logging verification, Newman, or required test setup fails
- WHEN the release evaluates its gates
- THEN APHL delivery, the trunk merge/tag, and release publication remain blocked
- AND the failure and any already-created candidate outputs are reported

#### Scenario: FIPS or mTLS test configuration fails
- GIVEN a FIPS keystore cannot be loaded or required mTLS test credentials are missing
- WHEN the build or integration gate runs
- THEN the affected gate fails rather than switching to a weaker configuration
- AND the release is not completed

### Requirement: Promotion preserves tags and the verified image identity

The release SHALL record the candidate image digest and its run-specific tag.
GHCR and dev ECR SHALL retain `X.Y.Z-RELEASE-<run>`, `X.Y.Z-RELEASE`, and `latest`
tags. The dev ECR `good` tag SHALL identify an image that passed verification.
APHL delivery SHALL use `izgw-hub-X.Y.Z` and the exact verified candidate, without
rebuilding it or resolving a later mutable `latest` tag as the promotion source.
A failed verification gate SHALL NOT advance `good` or deliver to APHL.

#### Scenario: Verified candidate is promoted
- GIVEN candidate digest `D` passed the release gates
- WHEN the run updates `good` and delivers its real release to APHL
- THEN both promoted references identify candidate `D`
- AND the APHL tag is `izgw-hub-X.Y.Z` for the requested version

#### Scenario: Mutable latest no longer identifies the candidate
- GIVEN candidate digest `D` passed and `latest` later identifies another image
- WHEN this release promotes its candidate
- THEN it uses recorded candidate `D`, not the image obtained from `latest`

#### Scenario: Candidate fails verification
- GIVEN the existing `good` tag identifies an earlier verified image
- WHEN the new candidate fails a required gate
- THEN `good` continues to identify the earlier image
- AND the failed candidate is not delivered to APHL

### Requirement: Release merges follow the selected Transform policy

After the gates pass, the release SHALL merge its branch into the configured
trunk, create annotated tag `vX.Y.Z` on the released trunk commit, and merge
release changes back into the base. Conflicting hunks SHALL prefer release
content toward the trunk and base content on the back-merge, subject to the
explicit version rules. A merge that cannot complete under those preferences
SHALL fail the run. Hotfix changes not carried into the base SHALL be identified
for manual review without making such a warning alone a release failure.

#### Scenario: Trunk merge has a resolvable content conflict
- GIVEN trunk and release contain conflicting edits to the same content
- WHEN the gated release merges into trunk
- THEN the conflicting content takes the release branch's value
- AND the version tag identifies the resulting released trunk commit

#### Scenario: Base retains conflicting hotfix content
- GIVEN base and hotfix conflict and the back-merge preference omits a hotfix change
- WHEN the hotfix is merged back
- THEN the base's conflicting content is retained
- AND the run identifies the affected hotfix change for manual review
- AND that warning alone does not mark the release failed

#### Scenario: A merge cannot complete
- GIVEN a required merge cannot complete under the agreed conflict preferences
- WHEN the workflow attempts that merge
- THEN the release is marked failed
- AND it follows the run-owned cleanup policy rather than claiming completion

### Requirement: Release notes describe the released change set

The release SHALL generate its current release notes from merged PRs in the
release's change set, including their titles and links. When no merged PRs are
found, it SHALL use commit descriptions and identify that fallback. The notes
SHALL identify the release version and date, be committed to `RELEASE_NOTES.md`,
and preserve historical release entries.

#### Scenario: Release includes merged PRs
- GIVEN the release change set includes merged PRs
- WHEN release notes are generated
- THEN the new version's entry includes their titles and links
- AND existing historical entries remain available

#### Scenario: Hotfix has no merged PRs
- GIVEN a hotfix change set contains commits but no merged PRs
- WHEN release notes are generated
- THEN commit descriptions supply the change list
- AND the run reports that it used the fallback

### Requirement: Hub release documents and Pages remain available

The release SHALL retain generated Markdown in `docs/release` and attach those
release documents to the corresponding GitHub Release. The attached
`RELEASE_NOTES.md` SHALL describe the requested release rather than the entire
historical notes file. Successful real releases SHALL publish the Maven site to
`current/` and `vX.Y.Z/`. Published documents and sites SHALL correspond to the
requested release and SHALL NOT be published as completed release outputs
before the release gates pass.

#### Scenario: Real release publishes documentation
- GIVEN release `2.17.0` passed all required gates
- WHEN its documents and site are published
- THEN `docs/release` contains its release Markdown
- AND the GitHub Release includes the retained Markdown attachments
- AND its attached notes describe `2.17.0`
- AND the Maven site is available under `current/` and `v2.17.0/`

#### Scenario: Candidate fails before publication
- GIVEN a candidate failed a required release gate
- WHEN the workflow handles the failure
- THEN it does not replace current or versioned release Pages with that candidate
- AND it does not publish that candidate's documents as a completed release

### Requirement: Successful real releases publish automatically

A successful real release SHALL publish a non-draft, non-prerelease GitHub Release
for tag `vX.Y.Z`, with generated release notes and Hub's retained attachments.
The run SHALL report success only after all required release outputs and branch
updates complete. APHL publication SHALL deliver the image only; it SHALL NOT
introduce automated deployment of the APHL runtime.

#### Scenario: Real release completes
- GIVEN `dry-run` is false and all required gates and release operations succeeded
- WHEN the workflow finishes
- THEN the GitHub Release is published rather than left as a draft
- AND it is associated with the requested version tag and documentation

#### Scenario: A required publication fails
- GIVEN the candidate passed the gates but APHL, Pages, or GitHub publication fails
- WHEN the workflow finishes
- THEN the run is failed rather than successful with missing outputs
- AND cleanup and remaining external effects are reported

### Requirement: Dry-run remains a side-effecting release rehearsal

With `dry-run=true`, the release SHALL use the same branch, version, build,
shared-dev deployment, verification, and merge behavior as a real release.
It SHALL write real version tags and GHCR/dev ECR image tags, including `latest`
and verified `good`. It SHALL skip APHL delivery, publish Pages to
`test/current/` and `test/vX.Y.Z/`, and create a draft GitHub Release.
The operator-facing description and run summary SHALL state that test branches
do not isolate shared dev, global version tags, or registries.

#### Scenario: Successful rehearsal on test branches
- GIVEN a standard dry-run uses base `developalm`, trunk `mainalm`, and version `2.17.0`
- WHEN it passes all gates and completes
- THEN it updates the selected test branches and creates real tag `v2.17.0`
- AND it has deployed and verified its candidate on shared dev
- AND it has written GHCR/dev ECR images but has not delivered to APHL
- AND Pages use `test/current/` and `test/v2.17.0/`
- AND the GitHub Release is a draft

#### Scenario: Dry-run does not bypass verification
- GIVEN a dry-run has deployed its candidate
- WHEN Newman fails
- THEN the dry-run fails under the same release gate as a real release
- AND it does not merge/tag or publish a completed rehearsal

### Requirement: Failure cleanup changes only run-owned Git outputs

On failure, the workflow SHALL attempt to reverse trunk/base updates and remove
standard-release branches, version tags, and GitHub Releases only when it can
establish that the current run created or changed them. It SHALL preserve the
operator's hotfix branch. Cleanup SHALL NOT remove pre-existing objects or
overwrite unrelated work. When ownership or safe reversal is uncertain, it
SHALL leave the affected state intact and report manual recovery.

#### Scenario: Failure after creation of a standard-release branch
- GIVEN this run created its standard-release branch and has not merged into trunk
- WHEN the run fails
- THEN cleanup attempts to remove that run's release branch
- AND no unrelated branches or tags are removed

#### Scenario: Failure after partial Git publication
- GIVEN this run recorded successful trunk/base updates and creation of a version tag
- WHEN a later required operation fails
- THEN cleanup attempts to reverse those updates and remove that tag
- AND it removes a GitHub Release only if this run created that release

#### Scenario: Ownership is uncertain or unrelated work intervenes
- GIVEN safe reversal of a branch update or ownership of an object cannot be established
- WHEN failure cleanup evaluates that state
- THEN it does not overwrite unrelated work or delete the uncertain object
- AND it reports the unresolved state for manual recovery

#### Scenario: Hotfix release fails
- GIVEN the operator supplied a hotfix branch containing the fix
- WHEN the release fails and cleanup runs
- THEN the hotfix branch remains available for investigation and recovery

### Requirement: External publication and dev rollback remain manual

Failure cleanup SHALL NOT automatically restore or delete registry publications,
published Pages, or the dev service deployment. It SHALL report which external
effects occurred, distinguish successful cleanup from unresolved cleanup, and
identify the remaining operator actions. Retrying a failed release SHALL NOT
bypass input, dependency, or duplicate-release checks.

#### Scenario: Failure after image delivery or Pages publication
- GIVEN this run delivered images or published Pages before a later failure
- WHEN cleanup completes
- THEN those external outputs are not automatically rolled back
- AND the summary identifies their locations and required manual recovery

#### Scenario: Verification fails after replacing dev
- GIVEN the failed candidate is deployed on shared dev
- WHEN release verification fails
- THEN the workflow does not claim to have restored the previous deployment
- AND it identifies the deployed candidate and the need for operator recovery

#### Scenario: Retry encounters an unresolved release object
- GIVEN a failed run left its version tag or standard-release branch in place
- WHEN an operator dispatches a new run for the same version
- THEN normal duplicate-release checks apply
- AND the new run does not treat the earlier run's objects as its own cleanup targets

### Requirement: Release results expose identity and recovery information

Each release SHALL provide a summary identifying its type, version, source,
base/trunk branches, dry-run mode, completed gates, and candidate image identity
when available. Successful runs SHALL identify published outputs and any next
development version. Failed runs SHALL identify the failed operation and cleanup
outcomes. Hotfix review warnings and dry-run side effects SHALL remain visible.

#### Scenario: Successful standard release summary
- GIVEN a standard release completes
- WHEN the operator reads its summary
- THEN the released version, version tag, verified image digest, output locations,
  and next development version are identifiable
- AND dry-run status is explicit

#### Scenario: Failure before an image is built
- GIVEN a release fails during prerequisite validation
- WHEN its summary is produced
- THEN it identifies the failed prerequisite without inventing an image identity
- AND it does not claim that release outputs were created or rolled back

### Requirement: Release automation preserves runtime and credential protections

The release process SHALL preserve Hub's SOAP WSDL and REST contracts, FIPS
cryptography, mTLS behavior, and production PHI masking. Release logs and uploaded
artifacts SHALL NOT expose workflow secret values, private client keys, or
generated Newman certificate lists containing passwords. Release errors SHALL be
reported as workflow failures, not new application SOAP faults or retry categories.

#### Scenario: Newman includes expected application error responses
- GIVEN the existing suite asserts responses to invalid or unauthorized requests
- WHEN that suite runs as a release gate
- THEN its existing response expectations remain unchanged
- AND the release gate evaluates test assertions rather than redefining SOAP faults

#### Scenario: Failed integration run uploads diagnostics
- GIVEN integration setup created private key and password-bearing certificate-list files
- WHEN failure diagnostics are uploaded
- THEN those files and secret values are excluded
- AND useful gate failure information remains available without new PHI exposure

### Requirement: Rollout proves standard, hotfix, and failure behavior

Before the automation is accepted for a real release, rollout SHALL demonstrate
standard-release, hotfix-release, and deliberately induced failure/cleanup
rehearsals in GitHub using `developalm`/`mainalm` and `dry-run=true`. Successful
rehearsals SHALL include the full shared-dev deployment and Newman gate.
The failure rehearsal SHALL demonstrate run-owned cleanup and protection of
pre-existing objects. The rollout record SHALL identify the runs, outcomes,
and remaining cleanup. Cutting the first real release SHALL require separate
maintainer approval; successful rehearsals alone SHALL NOT authorize it.

#### Scenario: Standard and hotfix rehearsals complete
- GIVEN suitable test branches and unused rehearsal versions are prepared
- WHEN both rehearsal types complete successfully in GitHub
- THEN their records show the full Hub gates and expected Git, image, and documentation outputs
- AND the hotfix record shows preservation of the base development version

#### Scenario: Forced failure exercises cleanup
- GIVEN a rehearsal creates run-owned state alongside known pre-existing objects
- WHEN a deliberate failure invokes cleanup
- THEN the record shows attempted cleanup of run-owned state
- AND the known pre-existing objects are preserved
- AND residual external effects and manual actions are documented

#### Scenario: Rehearsals do not authorize a real release
- GIVEN the required rehearsals have succeeded
- WHEN the first real release is considered without separate maintainer approval
- THEN the rollout does not initiate that real release
