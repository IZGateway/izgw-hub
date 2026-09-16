## Context

See [proposal.md](proposal.md) for motivation and
[the release-automation specification](specs/release-automation/spec.md) for the
behavior contract. The implementation model is Transform's `release.yml`,
`hotfix.yml`, and `_release_common.yml`, introduced in
[IZGateway/izgw-transform#253](https://github.com/IZGateway/izgw-transform/pull/253).

The following inspected Hub details determine the adaptations:

- `maven.yml` currently owns build, container publication, dev deployment,
  verification, and release-branch APHL delivery. Its `concurrency: dev` covers
  both build and verification. `main.yml` separately generates release documents
  and a draft GitHub Release.
- Hub names its JAR through Maven's `${image.tag}` and builds its image with
  Docker Buildx. Transform's Docker Maven plugin and `xform-*.jar` paths do not
  apply. Hub requires Java 21, its JPA test configuration, and existing test
  keystores; Transform's `SSL_SHARE=target` setup is not copied.
- Existing `ecs-deploy` returns deployment IDs by region. `ecs-wait-healthy`
  waits for those tasks and their ALB health, but does not confirm their image
  digest or the removal of the old deployment from service.
- The current verification job selects an arbitrary service task for its logging
  check and obtains `good` from mutable `latest`. Its Newman build metadata
  lookup refers to a build output absent from that separate job. The repository
  CA file is `testing/certs/izgwroot.pem`, not a file under `testing/testdata/certs`.
- Historical release branches still contain active workflow definitions.
  Updating the files on `develop` does not change those historical definitions.
  The maintainer selected branch freezing, not branch renaming or rewriting.

## Goals / Non-Goals

**Goals:** Keep Transform's small dispatch wrappers and single-job release
orchestrator; share Hub's deployment verification with dev CI; preserve candidate
identity through build, deployment, Git tagging, and image delivery; make partial
failure recovery attributable to one workflow attempt.

**Non-Goals:** No general release platform, new AWS environment, automatic APHL
runtime deployment, application code changes, or automatic dependency selection.
No transactional rollback of registries, Pages, or dev. No automatic resumption
of interrupted releases. No renaming, deletion, or content/history updates to
legacy release branches.

## Decisions

### 1. Keep a single release job and extract shared verification

Use these implementation boundaries:

| Surface | Responsibility |
| --- | --- |
| `.github/workflows/release.yml` | Standard dispatch inputs, permissions, and the shared dev concurrency boundary |
| `.github/workflows/hotfix.yml` | Hotfix dispatch inputs, permissions, and the same concurrency boundary |
| `.github/workflows/_release_common.yml` | One release job: prerequisites, candidate preparation, build, deploy, verify, publish, cleanup, and summary |
| `.github/workflows/maven.yml` | Develop push/PR, schedule, and manual CI; retain its build/verify jobs and remove release/APHL paths |
| `.github/actions/verify-hub/action.yml` | Shared deployed-image, health, logging, and Newman verification, with temporary access cleanup |
| Existing ECS actions | Deployment receipts and bounded deployment/health waiting; extend interfaces without breaking existing callers |
| `.github/scripts/release.sh` and focused fixture tests | Release input/version processing, notes, Git operations, non-secret state, and cleanup helpers |
| `docs/release-automation.md` | Operator inputs, prerequisites, cutover, rehearsal, and recovery instructions |

The shared verification action accepts deployment IDs, configured regions, the
expected Hub image digest, expected build/timestamp, and the existing service and
security-group identifiers. Test credentials arrive through the caller's
environment, not committed configuration. The caller establishes AWS credentials.
The action verifies only: it does not publish `good`, APHL, or release metadata.

Retaining one release runner keeps the candidate image, generated site, Git
objects, and cleanup receipts together. The dev pipeline can retain separate
build/verify jobs by passing non-secret image and build metadata as job outputs.
Extract rather than copy the Newman stage so release and development behavior
cannot silently diverge.

**Alternative:** A multi-job release workflow would require passing images/sites
and partial-failure state across runners. That is unnecessary complexity for the
Transform model. Copying the verification stage into a second workflow would
create two owners for certificate handling and test fixes.

### 2. Serialize the complete operation, not individual jobs

Declare the same workflow-level concurrency group, `dev`, on both dispatch
wrappers and `maven.yml`, with `cancel-in-progress: false`. The reusable release
workflow and nested actions do not acquire that group again: a caller and its
callee must not wait on each other's lock.

The lock covers all mutable image aliases, deployment, verification, promotion,
and cleanup. App-authenticated pushes back to `develop` can enqueue ordinary CI;
the release does not wait for that queued CI to complete while holding the lock.
Use the dispatched source SHA consistently, rather than silently switching to a
newer base commit after a queued run starts.

This does not promise FIFO ordering or unlimited pending runs. It also does not
prevent an administrator or another repository from modifying AWS resources;
digest and deployment checks detect an unexpected replacement.

**Alternative:** Locking only deployment would leave `latest` and verification
exposed to builds or publication steps in other runs.

### 3. Validate before writes and retain the App identity

Pass workflow inputs into shell steps as environment values and validate them as
data. Use the spec's version syntax, valid Git branch names, distinct base/trunk
refs, exact hotfix branch/version matching, and explicit remote ref lookups.
Authentication/API failures are not evidence that a branch, tag, or release is
absent. Reject conflicting release identities before candidate publication.

Resolve the selected source and base branch. Resolve the trunk when present;
retain Transform's first-release behavior of creating an absent trunk from the
candidate after the gates pass. A hotfix requires an existing released trunk
history and a version-tagged ancestor shared with its source branch. Test
branches used for rehearsals are prepared explicitly.

Resolve the parent and core dependency versions through Maven's resolved model/
dependency information, not text matching an arbitrary `<version>` in `pom.xml`.
Require one resolved core version and reject unresolved or SNAPSHOT dependencies.
The maintainer supplies released dependency versions before dispatch.

Use `RELEASE_AUTOMATION_APP_ID` and `RELEASE_AUTOMATION_APP_KEY` for release Git,
GitHub Release, and Pages writes. The maintainer has confirmed availability and
access. Keep package authentication and PR reads on the appropriate existing
`GITHUB_TOKEN` permissions; the release App is not a replacement for every build
credential. Do not fall back to the personal/SSH identity used by legacy CI.

Do not persist a single App token in the checkout and expect it to survive the
build and integration run. Refresh App authentication before post-gate writes
and again for failure cleanup when needed, and configure Git to use that current
identity. Token generation failure prevents writes and is reported.

**Alternative:** Allowing SNAPSHOT core, inferring successful lookups from empty
output, or reusing checkout credentials throughout a long run would weaken the
agreed prerequisites and recovery guarantees.

### 4. Prepare one candidate and carry its metadata forward

For a standard release, prepare `release/X.Y.Z` from the dispatched base SHA.
For a hotfix, retain the operator's branch and record its original tip and
released-history fork point before adding generated changes.

Generate notes and release Markdown, set `X.Y.Z-IZGW-RELEASE` with Maven's version
tooling, and commit only the intended tracked paths. Avoid backup POMs and broad
workspace staging. Keep Maven's project display name version-derived rather
than leaving its current hard-coded version stale after automated version bumps;
artifact coordinates and Hub's suffix conventions remain unchanged.

Capture the resulting candidate commit and tree IDs. Use Hub's existing Maven
build/site lifecycle, Java 21 toolchain, package settings, `SPRING_DATABASE=jpa`,
and test environment. Set `COMPUTERNAME` as current Hub CI does. Use
`X.Y.Z-RELEASE-<run>` as `image.tag`, so the scanner and Docker build consume
`target/<image.tag>.jar`. Run the existing external dependency scanner with
blocking results and errors for releases, retaining the project's suppressions
and NVD cache. Do not broaden this into an unrelated dev-CI scan-policy change.

Build the Docker image once with Hub's Buildx arguments:
`JAR_FILENAME=<image.tag>.jar` and `IZGW_VERSION=<image.tag>`. Capture Maven's
generated `target/classes/build.txt` values at build time. Pass these expected
values into verification; a later job does not try to rediscover them from its
source-only checkout.

Push only the explicit GHCR/dev ECR tags for this candidate, not `--all-tags`.
Record the registry manifest digest after publication and confirm that the
recorded run tag identifies that candidate. Retain the local image and original
build/site outputs through publication. Detect unexpected tracked-file changes
from the build rather than silently including them in the release commit.

**Alternative:** Copying Transform's artifact paths, reconstructing metadata in
the verification job, or rebuilding for APHL would break Hub's image identity.

### 5. Prove that dev is serving the candidate before Newman

Continue using the existing dev ECS service and its configured task definition;
do not introduce a new image-pinning task-definition lifecycle in this change.
Under the shared lock, publish the candidate alias and force deployment through
the existing ECS action. Record each region's deployment receipt as it succeeds,
including partial progress if a later region fails.

Extend health verification to wait for service stability as well as task/ALB
health. Confirm the recorded deployment is still the intended primary deployment
and that the Hub containers serving it have the expected image digest in every
configured region. Identify the Hub container through the service's load-balancer
container mapping rather than assuming every container is the application.
Missing digest evidence, rollback to an old deployment, or a mismatched region
fails the release gate.

Select CloudWatch streams from those verified task IDs and their logging
configuration, not the first task returned by a service-wide query. Preserve the
existing Filebeat connection assertion, using bounded waiting for startup logs.
The existing Newman suite still targets its configured dev endpoint; this does
not create a new per-region functional-test suite.

The shared verifier retains smoke warmup, the authoritative `Working` suite, TCP
keepalive, JWT test inputs, and host-scoped mTLS. Use the actual repository CA
path, resolved independently of the working directory. Preserve the no-cert
host behavior so certificate fallback cannot conceal expired-token failures.

Temporary security-group access is owned separately from release publication:
record any ingress rule created by this run and remove that rule during verifier
cleanup on success or failure. Do not remove a pre-existing rule. Report cleanup
failure explicitly. Certificate/key/password-bearing files are excluded from
artifacts and removed when the runner can perform cleanup.

**Alternative:** A shared lock alone does not prove which image ECS ran; the
current health-only waiter and arbitrary-task logging lookup can accept evidence
from the wrong deployment. Creating new AWS infrastructure is not required.

### 6. Apply the selected merge preferences without changing tested source

After all gates pass, prepare the trunk merge in a separate Git worktree using
`--no-ff -X theirs`. Compare its complete tracked tree with the recorded tested
candidate tree before any APHL or release publication. If they differ, fail and
report the differing paths; the maintainer aligns the source branches and retries.
Do not silently rebuild a changed merge tree or publish untested trunk content.
This is the maintainer-selected tree-drift rejection policy.

Capture the expected remote trunk/base tips and use ordinary non-forced pushes
for branch advancement. A concurrent update that makes the planned publication
unsafe fails rather than resetting the remote. Tag the exact accepted trunk
commit with annotated `vX.Y.Z`.

Prepare the base back-merge using `--no-ff -X ours`. For standard releases, apply
the selected next version. For hotfixes, preserve the development version from
the base being merged into. Update only version metadata as required; do not
restore the entire old POM and thereby discard non-conflicting dependency fixes.

Determine hotfix review warnings from the recorded pre-merge hotfix source/fork
and conflict paths, not a diff against trunk after trunk already contains the
hotfix. Probe conflicts in an isolated worktree before applying the automatic
preference. Report potential omitted operator changes conservatively; exclude
purely generated version noise, but do not hide dependency changes by excluding
all of `pom.xml`. Also flag generated release notes omitted by a back-merge
conflict for manual review. Structural merge failures remain release failures.

Separate worktrees keep Maven outputs and publication files intact; there is no
need to reset or clean the build workspace to move between branches.

**Alternative:** Building a local preview of the final trunk merge was considered.
The maintainer chose the smaller Transform-style build-from-release approach
with a tree-equality guard instead. Forced branch replacement is not a substitute
for either approach.

### 7. Stage publications and preserve Hub's documentation contract

Use this logical sequence; preparation and candidate writes are distinct from
release publication:

```text
Operator -> Dispatch wrapper: version, branches, dry-run
Wrapper  -> Shared dev lock: acquire
Release  -> Git/Maven: validate, prepare candidate and release documents
Release  -> Maven/scanner: build, test, scan
Release  -> GHCR/dev ECR: publish candidate tags and record digest
Release  -> ECS: deploy; record regional deployment IDs
Release  -> Shared verifier: stable deployment, digest, ALB, logging, Newman
Verifier -> Release: passed gates for this candidate
Release  -> Git worktrees: plan merges; reject trunk tree drift
Release  -> dev ECR: advance good using the verified digest
Release  -> APHL ECR: deliver that image [real release only]
Release  -> Git: publish accepted trunk commit, tag, and base update
Release  -> Pages: current/versioned paths [test paths for dry-run]
Release  -> GitHub Release: notes + attachments [draft for dry-run]
Release  -> Summary/cleanup: report outputs or run-owned recovery
Wrapper  -> Shared dev lock: release
```

Pull/promote by the recorded dev ECR digest, not by `latest`; compare the published
APHL image identity with the candidate. APHL credentials are configured only for
real delivery. Restore dev-account credentials before any subsequent AWS
diagnostics that require them.

Find the preceding version tag on the candidate's ancestry, rather than choosing
the largest version tag anywhere in the repository. Use only the source change
range captured before this run's generated commits. Resolve associated merged
PRs and deduplicate their numbers; use commit descriptions only for a genuine
no-PR result. A GitHub read failure is not an empty change list.

Use Hub's `# IZ Gateway Release X.Y.Z` heading and preserve historical entries.
On a retry from a retained hotfix branch, replace the current unpublished
version's generated section rather than adding duplicate headings. Keep
current-entry Markdown separately for the GitHub body and attached notes.

Build `docs/release` from tracked root Markdown, preserving Hub's existing
selection behavior: omit the root README and use the generated current-release
notes instead of copying the entire history. Use a fresh staging set so removed
documents do not survive as stale release attachments. Do not copy untracked
local notes or the working tree wholesale.

Publish the original Maven site to `current/` and `vX.Y.Z/`, or
`test/current/` and `test/vX.Y.Z/`. GitHub Release creation is the final public
completion marker, with the prepared Markdown attachments. There is no release
publication in the extracted verifier or ordinary dev CI.

**Alternative:** Publishing final metadata before Newman, promoting `latest`,
or retaining `main.yml` as a second publisher would leave mismatched outputs
and ambiguous ownership.

### 8. Keep an attempt-owned receipt journal for cleanup

Initialize a non-secret state journal under `RUNNER_TEMP`, identified by
repository, workflow run ID, and run attempt. Record source/candidate IDs,
original remote refs, planned mutations, confirmed resulting object IDs,
deployment receipts, image digests, published paths, and GitHub Release IDs.
Helpers use structured JSON and explicit status values, not commit-message
substring matching or global ref existence to infer ownership.

Record intent before a write and confirmation after a successful result. A
write whose result is uncertain remains uncertain unless its exact expected
identity and creation conditions can be established. Upload a sanitized journal
and summary even on failure when the runner is available. A later rerun starts
a new journal and never adopts previous-run objects as its own.

| State confirmed for this attempt | Recovery |
| --- | --- |
| Standard-release branch created | Delete only if the remote ref still matches the recorded run-owned ref |
| Version tag created | Compare its exact tag object before conditional deletion |
| GitHub Release created | Delete by its recorded release ID, not merely a matching tag name |
| Trunk/base commits pushed | Revert the recorded commits in reverse order when safe; push the revert normally |
| Trunk created for an initial release | Remove only the run-created branch at its recorded tip; do not try to revert a nonexistent merge parent |
| Hotfix branch prepared | Keep it for investigation and retry |
| Images, Pages, or dev deployment changed | No automatic rollback; report locations, identities, and manual recovery |
| Ownership or current remote state uncertain | Leave it intact and report manual recovery |

Branch/tag deletion uses a compare-and-delete condition against the recorded
object, not an unguarded check followed by a potentially racing deletion. Do not
force-reset shared history. If unrelated work has intervened or reversion cannot
complete safely, stop that recovery operation and identify it explicitly.

Keep the original release failure even when cleanup succeeds. Capture cleanup
errors individually so later safe cleanup attempts and the summary can still
run; do not turn them into success-shaped defaults. Fresh App authentication is
required for recovery writes. Forced cancellation or runner loss can prevent
cleanup entirely, so the runbook also covers receipt-based manual inspection.

**Alternative:** Transform's per-step completion flags are a useful starting
point, but exact receipts are needed for remote races, partial regional
deployment, and distinguishing pre-existing objects from this attempt's writes.
Rollback of all external systems would require a different release architecture.

### 9. Keep application behavior and diagnostics separate

This design adds workflow audit information, not new application audit events.
Use named Actions steps, a sanitized release-state artifact, and step summaries;
no `Markers2.*` changes are required. There are no new DynamoDB access patterns,
Spring Security filters, destination groups, circuit breakers, or Bouncy Castle
API calls. Existing BCFIPS provider/keystore behavior stays in the Hub build and
runtime.

These application paths are unchanged by the release work:

```text
SOAP routing
Provider/IIS -> ALB -> Hub SOAP routing -> Configured IIS or mock
Provider/IIS <- ALB <- Hub SOAP response <- Existing endpoint response

ADS submission
ADS client  -> ALB -> Hub ADS: existing upload/API request
ADS client  <- ALB <- Hub ADS: existing API response
                     Hub ADS -> Configured CDC DEX: existing delivery
```

Workflow dispatch uses existing GitHub operator permissions, App branch access,
and AWS credentials, not application admin/destination roles. Do not change
production PHI masking, SOAP fault mappings, REST responses, or the existing
application retry classifications.

| Failure | Workflow behavior | Application SOAP/HTTP/retry effect |
| --- | --- | --- |
| Input, dependency, or App prerequisite | Fail before controlled writes; report corrective prerequisite | None |
| Maven/FIPS test setup or dependency scan | Block release; retain permitted diagnostic reports | Existing test exceptions remain unchanged |
| ECS, logging, or Newman | Block promotion; report candidate and possible dev recovery | Existing response assertions remain unchanged |
| Git or publication operation | Fail and attempt run-owned cleanup | None |
| Hotfix omission warning | Complete with explicit manual review warning when other operations succeed | No new application behavior |

Reuse bounded AWS/logging waits; do not add blind whole-release retries. Replace
full-environment printing and whole-workspace uploads in the new/shared release
path with named reports, sanitized receipts, and test logs that preserve existing
secret/PHI protections. Do not upload private keys, password-bearing certificate
lists, Maven credentials, or token-bearing Git configuration.

## Risks / Trade-offs

- **Shared dev is replaced during rehearsal** -> Schedule the work, hold the
  shared lock, show digest/deployment receipts, and retain manual recovery steps.
  Later queued CI can deploy normally; that is not a promised rollback.
- **Rehearsal temporarily changes the default branch** -> Announce the PR-target
  and scheduled-CI impact, use an agreed test window, and restore `develop` even
  if rehearsal fails. Do not delete the temporary default branch before restoring
  the real default.
- **Legacy workflows remain in historical branches** -> Freeze legacy branch
  updates without bypass for CI identities, disable the old `main.yml` workflow
  repository-wide, and drain old runs during cutover. Freezing Git refs does not
  disable manual workflow dispatch: operators do not dispatch from those refs.
- **Automatic back-merge preferences can omit a fix** -> Retain the selected
  policy, preserve version metadata explicitly, and show hotfix review warnings.
- **Trunk contains changes absent from the tested source** -> Reject tree drift;
  require a maintainer to align branches and prepare another candidate.
- **Ref changes occur outside workflow concurrency** -> Use expected object IDs,
  normal advancement pushes, conditional deletion, and manual recovery on doubt.
- **A post-gate failure leaves APHL images or Pages published** -> Report the
  exact external effects; never claim Git cleanup restored those systems.
- **App credentials expire or the runner disappears** -> Refresh authentication
  at write boundaries; preserve sanitized receipts when possible and document
  manual recovery rather than promising unconditional automatic cleanup.
- **Scanner or integration instability blocks releases** -> Retain diagnostics
  and bounded waits; do not weaken the agreed gates to make a release pass.

## Migration Plan

1. Implement the wrappers, common workflow, helpers, and shared verifier on the
   change branch. Add fixture coverage using existing Bash/Git/jq and available
   runner tools, without a new test framework. Cover input rejection, version
   suffixes, notes, merge preferences/tree drift, exact-image selection, and
   cleanup against pre-existing or concurrently changed objects.
2. Prepare `developalm` and `mainalm` with the new workflow definitions and
   suitable released BOM/core versions. This includes removing the legacy
   release path in these test copies. Do not modify or rename real legacy release
   branches. Select unused rehearsal versions and document their expected effects.
   Following the maintainer-selected Transform approach, temporarily set
   `developalm` as the default branch so GitHub registers the manual workflows.
   Coordinate scheduled CI and the temporary default PR target during this window.
3. Run the required standard, hotfix, and deliberate failure/cleanup rehearsals
   with `dry-run=true`. Use controlled failure injection confined to test-branch
   workflow changes, not a permanent bypass or failure switch in real releases.
   Exercise partial Git publication and preservation of known pre-existing
   objects; record run URLs, digests, refs, site paths, and residual cleanup.
4. Restore `develop` as the default branch after rehearsals, including on failure,
   before removing any test branches. Remove only confirmed rehearsal-owned tags,
   draft releases, and test branches when approved. Treat test Pages, registry
   aliases, and shared dev as separate manual recovery items. Rehearsal success
   does not authorize a real release.
5. In a maintainer-controlled cutover window, drain or cancel outstanding legacy
   release runs and stop new legacy dispatches. Make existing `Release*` branches
   read-only with an update/deletion restriction and no bypass for automation
   identities. Keep their names, contents, and history unchanged. Disable the
   old workflow identified by `.github/workflows/main.yml` repository-wide;
   use its file path/ID, since both current workflows share a display name.
6. Merge the new automation and dev-only `maven.yml` into `develop`; retain
   `develop` as the default branch. Remove `main.yml` from the new source line.
   Confirm normal dev CI and the shared verification path still operate.
   New code reaches `main` through the approved standard release. Historical
   branches are for reference, not future release work.
7. Publish the operating/recovery runbook and update the repository's CI/branch
   guidance, including the stale release-path context in `openspec/config.yaml`.
   Obtain separate maintainer approval before dispatching the first real release.

For automation rollback, stop new release dispatches and inspect active-run
receipts first. Reverting workflow changes does not undo delivered images, Git
publication, or deployed services. Re-enabling a legacy workflow or unfreezing
a historical release branch is an explicit maintainer decision, not an automated
rollback side effect.

## Open Questions

Only rollout inputs remain: the unused rehearsal versions, the shared-dev
maintenance window, and the released BOM/core versions selected by the maintainer.
They do not change the design or the specification. Example versions in the
artifacts are not authorization to reuse an existing version or cut a real release.
