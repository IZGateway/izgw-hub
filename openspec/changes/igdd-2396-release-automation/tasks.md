## 1. Release Helper Contracts

Implement the [specification](specs/release-automation/spec.md) using the approved
[design](design.md). Every checkbox below is implementation or rollout work, not
work completed by creating the planning artifacts. Estimates and dependencies
are in section 10.

Use existing Bash, Git, jq, Maven, Docker, and GitHub runner tools; do not introduce
a new test framework. No Java packages, new Java unit/Spring Boot test classes,
`package-info.java`, DynamoDB schema changes, or BCFIPS/keystore-format changes
are planned. Existing Maven unit tests and Newman provide the runtime gates.
Pause for scope approval if implementation requires changing those boundaries.

Maintainer-controlled tasks require explicit coordination before live mutations.
Do not inspect secret values; use the confirmed App setup and request missing
secret names from the maintainer if execution reports a problem. The first real
release is outside this checklist's execution authority.

- [ ] 1.1 Create `.github/scripts/release.sh` and
  `.github/scripts/tests/release-tests.sh` with explicit helper entry points and
  an offline fixture harness using disposable Git repositories and command stubs.
  **Done when:** the runner exercises the actual helpers, returns nonzero on a
  failed assertion, and makes no real GitHub, registry, or AWS writes.
- [ ] 1.2 Implement input, version, and Git-context validation in
  `.github/scripts/release.sh`. Cover standard/base matching, exact hotfix branch
  versions and released ancestry, distinct valid refs, duplicate identities, and
  explicit next-version normalization. **Done when:** fixtures cover valid and
  invalid spec examples, next-minor arithmetic, absent initial trunk, and the
  distinction between missing objects and failed remote/API lookups; rejected
  requests have no mutation calls.
- [ ] 1.3 Implement resolved BOM/core prerequisite checks in
  `.github/scripts/release.sh` using Maven model/dependency information.
  **Done when:** fixtures independently reject SNAPSHOT BOM, SNAPSHOT core,
  unresolved or ambiguous core versions, and failed Maven resolution; released
  inputs pass without changing dependency declarations.
- [ ] 1.4 Add the attempt-owned JSON receipt journal to
  `.github/scripts/release.sh`, stored under `RUNNER_TEMP`. Include repository,
  run/attempt IDs, intent, confirmation, original/current object IDs, and
  uncertain outcomes. **Done when:** fixtures distinguish planned writes from
  confirmed writes, refuse ownership from a different attempt, and show no
  credentials in serialized state or error output.

## 2. Candidate Preparation and Git Lifecycle

- [ ] 2.1 Add notes and Markdown staging to `.github/scripts/release.sh` for
  `RELEASE_NOTES.md` and `docs/release`. Use the preceding reachable tag and the
  source range before generated commits; resolve/deduplicate merged PRs and
  report genuine commit-only fallback. **Done when:** fixtures preserve history,
  replace a retried unpublished entry without duplicate headings, exclude
  untracked files/root README, remove stale staged documents, and attach only
  the current release's notes; GitHub lookup errors fail instead of invoking
  fallback.
- [ ] 2.2 Implement candidate preparation in `.github/scripts/release.sh` and
  make the project display name in `pom.xml` version-derived. Use Maven version
  tooling, selective staging, the dispatched source SHA, and Hub's suffixes.
  **Done when:** fixtures create a standard release branch, retain an existing
  hotfix branch, capture original/candidate commit and tree IDs, handle expected
  no-op preparation on a retry, and leave artifact coordinates, dependency
  declarations, and unrelated tracked files unchanged.
- [ ] 2.3 Implement trunk merge planning in `.github/scripts/release.sh` using
  isolated worktrees and the selected `--no-ff -X theirs` policy. Compare the
  planned trunk tree with the tested candidate tree before publication.
  **Done when:** fixtures accept an identical tree and an initial trunk, reject
  untested non-conflicting trunk content with a path report, and fail structural
  merge errors without rewriting shared refs or cleaning the build workspace.
- [ ] 2.4 Implement base merge planning in `.github/scripts/release.sh` using
  `--no-ff -X ours`, explicit standard next versions, and preservation of the
  current base development version for hotfixes. Derive review warnings from
  the original hotfix source/fork and conflict paths. **Done when:** fixtures
  distinguish non-conflicting changes from possible omissions, retain dependency
  fixes unless the selected conflict policy overrides them, report relevant POM
  and omitted release-note changes, and do not restore the entire old POM.
- [ ] 2.5 Implement Git publication in `.github/scripts/release.sh`: accepted
  trunk commit, annotated `vX.Y.Z`, and base update, with exact receipts after
  each confirmed write. **Done when:** disposable-remote fixtures show the tag
  identifies the accepted trunk commit, normal pushes reject unsafe concurrent
  changes, and partial success is recorded without treating existing objects
  as newly created.
- [ ] 2.6 Implement run-owned Git/GitHub cleanup in
  `.github/scripts/release.sh`. Use conditional ref deletion, recorded release
  IDs, and safe reverse-order reverts; preserve hotfix branches and all external
  publications/deployments. **Done when:** fixtures cover pre-existing objects,
  moved refs, uncertain writes, initial-trunk creation, partial publication,
  failed reverts, and fresh-run retries; unrelated state survives and cleanup
  errors remain visible without replacing the original failure.

## 3. Shared Deployed-Hub Verification

- [ ] 3.1 Extend `.github/actions/ecs-deploy/action.yml` and extract testable
  deployment logic into `.github/actions/ecs-deploy/deploy.sh`. Preserve existing
  inputs/output behavior and add per-region receipts as deployment succeeds.
  **Done when:** the fixture runner covers existing callers, multiple regions,
  invalid/missing deployment IDs, and failure after one region succeeds; partial
  progress remains available to the release journal.
- [ ] 3.2 Extend `.github/actions/ecs-wait-healthy/action.yml` and extract its
  logic into `.github/actions/ecs-wait-healthy/wait.sh`. Add bounded stability,
  intended-primary-deployment, serving-task/ALB, and expected Hub digest checks,
  preserving compatibility for existing callers. **Done when:** fixtures reject
  an old/replaced deployment, absent digest evidence, an unhealthy target, and a
  mismatched region; successful results identify the verified Hub tasks using
  the service's container mapping.
- [ ] 3.3 Create `.github/actions/verify-hub/action.yml` and
  `.github/actions/verify-hub/verify.sh` to orchestrate health and task-specific
  logging checks. Select CloudWatch streams from verified task IDs and their
  logging configuration with bounded waiting. **Done when:** fixtures cannot
  pass using an unrelated task's logs, missing logging evidence fails, and the
  action has no image-promotion or release-publication behavior.
- [ ] 3.4 Extract the existing Newman setup, smoke warmup, and authoritative
  `Working` run from `.github/workflows/maven.yml` into
  `.github/actions/verify-hub/action.yml` and its `verify.sh`. Retain keepalive,
  JWT inputs, host-scoped mTLS, and the no-cert host; consume expected build/
  timestamp inputs and the resolved `testing/certs/izgwroot.pem` path.
  **Done when:** fixtures capture the correct Newman arguments and nonzero
  failure propagation, reject missing required inputs/files, and do not weaken
  existing collection assertions or certificate matching.
- [ ] 3.5 Add temporary access and sensitive-file cleanup to
  `.github/actions/verify-hub/verify.sh` and its action wiring. Record only
  ingress rules created by this run and clean them on success or failure.
  **Done when:** fixtures preserve pre-existing rules, report revoke failures,
  remove generated credential files when possible, and exclude private keys and
  password-bearing certificate lists from the action's diagnostic artifacts.

## 4. Dispatch and Candidate Execution

- [ ] 4.1 Add `.github/workflows/release.yml`, `hotfix.yml`, and
  `_release_common.yml` with the spec's typed inputs, thin wrappers, and one
  reusable release job. Establish App identity without persistent stale checkout
  credentials; refresh it at post-gate and recovery write boundaries.
  **Done when:** workflow/helper contract checks confirm input propagation,
  prerequisite ordering, correct credential roles, explicit authentication
  failure, and no SSH/personal-identity fallback or token-bearing output.
- [ ] 4.2 Wire candidate preparation, Hub Maven build/site generation, and the
  blocking external dependency scan in
  `.github/workflows/_release_common.yml`. Preserve Java 21/JPA/test-keystore
  settings, set `COMPUTERNAME`, and capture `target/classes/build.txt`.
  **Done when:** checks show scanner input `target/<image.tag>.jar`, Hub release
  version/name and build metadata, and blocking behavior for test/setup failure,
  CVSS `7.0`, or an unsuccessful scan; no Transform JAR paths or `SSL_SHARE=target`
  assumptions remain.
- [ ] 4.3 Wire one Hub Buildx build and explicit GHCR/dev ECR candidate pushes in
  `.github/workflows/_release_common.yml`, with receipt helpers in
  `.github/scripts/release.sh`. **Done when:** fixtures/contract checks show the
  correct Docker build arguments, retained run/version/`latest` tags, captured
  registry digest, and detection of unexpected tracked-file changes; no
  `--all-tags` publication or second image build is used.
- [ ] 4.4 Connect deployment receipts and the shared verifier in
  `.github/workflows/_release_common.yml`, passing the candidate digest and
  generated build metadata explicitly. **Done when:** integration checks show
  that every required gate applies to this candidate and any failed or missing
  gate blocks promotion, trunk/tag updates, Pages, and GitHub publication while
  preserving already-created candidate receipts.

## 5. Publication, Dry-run, and Failure Completion

- [ ] 5.1 Wire post-gate merge planning/tree-drift rejection before `good` and
  APHL publication in `.github/workflows/_release_common.yml` and
  `.github/scripts/release.sh`. Promote by the verified digest and compare the
  APHL image identity; configure APHL credentials only for real delivery.
  **Done when:** fixtures show a changed `latest` is ignored, tree drift prevents
  publication, failed verification leaves `good` unchanged, and APHL delivery
  does not rebuild the image or deploy an APHL runtime.
- [ ] 5.2 Wire accepted Git publication, original Maven Pages output, and final
  GitHub Release creation in `.github/workflows/_release_common.yml`.
  **Done when:** checks show the design's operation order, current/versioned site
  paths, recorded release ID, current notes and retained Markdown attachments,
  and a non-draft/non-prerelease real release only after required outputs and
  branch updates succeed; partial publication invokes failure handling.
- [ ] 5.3 Wire attempt-owned recovery, authentication refresh, summaries, and
  allowlisted artifacts in `.github/workflows/_release_common.yml` and
  `.github/scripts/release.sh`. **Done when:** failure-path fixtures cover errors
  before journal completion and after publication, preserve the failed result,
  identify unknown/residual external state, and never claim registry, Pages, or
  dev rollback; successful summaries expose candidate identity and output links
  without credentials or full-environment dumps.
- [ ] 5.4 Extend `.github/scripts/tests/release-tests.sh` with the complete
  standard/hotfix and real/dry-run mode matrix across the workflows and helpers.
  **Done when:** assertions show the same gates and real Git/GHCR/dev writes for
  dry-run, `test/current/` and `test/vX.Y.Z/` Pages, draft GitHub Releases, and no
  APHL credential setup/delivery; operator descriptions and summaries explicitly
  warn about shared dev, global tags, and mutable aliases.

## 6. Development CI Integration

- [ ] 6.1 Refactor `.github/workflows/maven.yml` to use the shared verifier with
  build-time metadata and the exact candidate digest, and promote its verified
  `good` by digest. Retain develop push/PR, schedule, manual CI, and existing
  development scan policy; remove legacy release/APHL logic and delete
  `.github/workflows/main.yml` only from the new source line.
  **Done when:** checks cover all retained CI entry points and outputs, no
  release publication remains in dev CI, and no legacy branch ref/content has
  been modified. The actual shared-CI regression run is covered in section 8.
- [ ] 6.2 Apply workflow-level `dev` concurrency with
  `cancel-in-progress: false` in `.github/workflows/maven.yml`, `release.yml`,
  and `hotfix.yml`, without reacquiring it in `_release_common.yml` or actions.
  **Done when:** contract review confirms coverage from conflicting writes
  through cleanup, no caller/callee deadlock, and no release dependency on the
  CI run queued by its own App-authenticated base push; live overlap evidence is
  captured during rehearsal.

## 7. Documentation and Pre-rehearsal Review

- [ ] 7.1 Create `docs/release-automation.md`, link it from `README.md`, and update
  `.github/copilot-instructions.md`, `.claude/CLAUDE.md`, and
  `openspec/config.yaml` for the new CI/branching model. Document dependency
  preparation, input examples, App/secret names, side-effecting dry-runs,
  warnings, recovery, and the staged cutover.
  **Done when:** the runbook preserves old branch names/history, distinguishes
  reference-only branches from new release/hotfix branches, explains temporary
  default-branch restoration and manual-dispatch limits, and requires separate
  approval for the first real release. Do not pre-populate a real
  `RELEASE_NOTES.md` entry; its generator is covered by task 2.1.
- [ ] 7.2 Complete a security review checkpoint for the new workflows,
  `.github/scripts/release.sh`, and the touched `.github/actions/` code.
  Review input quoting, App permissions/token renewal, conditional Git writes,
  temporary ingress ownership, mTLS test boundaries, and secret/PHI-safe
  diagnostics. **Done when:** the reviewed revision and resolved in-scope
  findings are recorded in
  `openspec/changes/igdd-2396-release-automation/rehearsal-results.json`; relevant
  regression fixtures pass, and no runtime FIPS, TLS, or authorization weakening
  is needed.
- [ ] 7.3 Run the complete offline fixture suite, shell syntax checks for all
  added/extracted helpers, Maven model checks for version/display metadata, and
  workflow contract review. **Done when:** results recorded in
  `openspec/changes/igdd-2396-release-automation/rehearsal-results.json` cover
  early failure, every gate, tree drift, both conflict preferences, exact-image
  promotion, receipt ownership/races, and failure summaries; no runtime outcome
  is claimed from inspection or planning alone.

## 8. Maintainer-controlled GitHub Rehearsals

These tasks use shared dev and create real remote state. Record actual run IDs,
ref/object IDs, digests, sites, approvals, and residual actions in
`openspec/changes/igdd-2396-release-automation/rehearsal-results.json`, without
credentials. Estimates exclude unattended cloud runtime and approval delays.
Task 8.5 is an exit obligation whenever task 8.1 changes the default branch,
including when later rehearsal tasks fail or are cancelled.

- [ ] 8.1 Obtain the rehearsal window and inputs from the maintainer, then
  prepare `developalm`/`mainalm` with the new definitions and selected released
  BOM/core versions. Record unused rehearsal versions, starting refs, and the
  default-branch restoration procedure. **Done when:** the maintainer-controlled
  temporary switch to `developalm` makes the dispatch workflows available,
  scheduled-CI/PR-target impacts are acknowledged, and no real legacy release
  branch or first real release has been changed.
- [ ] 8.2 Run a standard `dry-run=true` release and exercise the refactored
  manual CI path on the test source, including an overlap attempt to prove the
  shared lock. **Done when:** recorded GitHub runs show Maven/scanner and full
  dev health/digest/logging/Newman gates, expected Git/image/docs outputs and
  next snapshot, no APHL writes, and no candidate replacement or active-run
  cancellation by overlapping CI.
- [ ] 8.3 Prepare an operator hotfix from the rehearsed released trunk and run
  its `dry-run=true` workflow. **Done when:** GitHub evidence shows the exact
  verified image and version tag, retained base development version, preserved
  operator branch, correct conflict-review reporting where exercised, test
  Pages/draft attachments, and no APHL writes.
- [ ] 8.4 Run the forced-failure/cleanup rehearsal using failure injection
  confined to test-branch workflow changes. Exercise partial Git publication and
  preservation of known pre-existing objects, including a duplicate-version
  rejection probe. **Done when:** GitHub evidence shows failed status, safe
  cleanup of confirmed run-owned state, untouched pre-existing objects, visible
  residual external effects, and no permanent failure/bypass switch in the
  implementation intended for `develop`.
- [ ] 8.5 Restore `develop` as the default after the rehearsal window, including
  on failure, before deleting any test branch. Perform only approved cleanup of
  confirmed rehearsal-owned refs/releases; address Pages, registry aliases, and
  shared dev separately. **Done when:** the actual default is restored and the
  rollout record identifies removed objects and any residual effects with
  explicit owners/recovery actions; unsuccessful rehearsals remain unchecked.

## 9. Maintainer-controlled Cutover and Handoff

- [ ] 9.1 Obtain the cutover window, drain or cancel legacy release runs, and
  stop legacy dispatches. Have the maintainer freeze updates/deletions on
  existing `Release*` branches without automation bypass and disable the old
  `.github/workflows/main.yml` workflow repository-wide by path/ID.
  **Done when:** the rollout record shows the controls applied and before/after
  legacy branch names and object IDs unchanged; no old branch was renamed,
  deleted, or rewritten.
- [ ] 9.2 Merge the approved implementation into `develop` through the agreed
  repository process, keeping it as the default branch and retaining `maven.yml`
  for development CI. **Done when:** the post-cutover CI run passes the shared
  verification path, new dispatch workflows are available, legacy `main.yml`
  is disabled, and `main` has not been advanced by an unapproved real release.
- [ ] 9.3 Complete the rollout record and operator handoff in
  `openspec/changes/igdd-2396-release-automation/rehearsal-results.json` and
  `docs/release-automation.md`. **Done when:** all required rehearsals and the
  CI regression have real evidence, remaining external recovery is explicit,
  maintainers can locate release/recovery instructions, and the first real
  release remains a separately approved action rather than an automatic final
  step of this change.

## 10. Task Summary

Rough active engineering estimates; each task is a 1-4 hour work unit. These are
not elapsed-time promises. Rehearsal failures can require fixes and another
approved window. Dependencies identify prerequisites, not permission to perform
live mutations.

| Task | Hours | Dependencies |
| --- | ---: | --- |
| 1.1 | 2 | None |
| 1.2 | 3 | 1.1 |
| 1.3 | 2 | 1.1 |
| 1.4 | 3 | 1.1 |
| 2.1 | 4 | 1.2, 1.4 |
| 2.2 | 3 | 1.2, 1.3, 1.4, 2.1 |
| 2.3 | 3 | 2.2 |
| 2.4 | 4 | 2.2 |
| 2.5 | 3 | 2.3, 2.4 |
| 2.6 | 4 | 1.4, 2.5 |
| 3.1 | 2 | 1.1, 1.4 |
| 3.2 | 3 | 3.1 |
| 3.3 | 3 | 3.2 |
| 3.4 | 3 | 3.3 |
| 3.5 | 2 | 3.3, 3.4 |
| 4.1 | 3 | 1.2, 1.3, 1.4 |
| 4.2 | 3 | 2.2, 4.1 |
| 4.3 | 3 | 4.2 |
| 4.4 | 3 | 3.1, 3.2, 3.3, 3.4, 3.5, 4.3 |
| 5.1 | 3 | 2.3, 2.4, 4.4 |
| 5.2 | 4 | 2.1, 2.5, 5.1 |
| 5.3 | 3 | 2.6, 3.5, 5.2 |
| 5.4 | 2 | 5.3 |
| 6.1 | 4 | 3.5, 4.3, 5.1 |
| 6.2 | 1 | 4.1, 6.1 |
| 7.1 | 3 | 5.4, 6.1 |
| 7.2 | 2 | 5.4, 6.2, 7.1 |
| 7.3 | 3 | 7.2 |
| 8.1 | 3 | 7.3; maintainer-approved inputs/window |
| 8.2 | 4 | 8.1 |
| 8.3 | 4 | 8.2 |
| 8.4 | 3 | 8.1, 8.3 |
| 8.5 | 2 | Exit obligation from 8.1, after attempts including failures |
| 9.1 | 2 | 8.2, 8.3, 8.4, 8.5; maintainer-approved cutover |
| 9.2 | 3 | 9.1; approved merge |
| 9.3 | 1 | 9.2 |
| **Total** | **103** | **36 tasks** |
