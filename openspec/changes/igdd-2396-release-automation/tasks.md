## 1. Dispatch Wrappers and Prerequisites

Implement the [specification](specs/release-automation/spec.md) using the approved
[design](design.md). Every checkbox below is implementation or rollout work, not
work completed by creating the planning artifacts. Estimates and dependencies
are in section 10.

Follow the shape of Transform's `release.yml`, `hotfix.yml`, and
`_release_common.yml`. Keep the shell inline in workflow steps and in composite
action steps, as `.github/actions/ecs-deploy/action.yml` already does. Do not add
a shell library, a script directory, or a test framework. No Java packages, new
Java test classes, `package-info.java`, DynamoDB schema changes, or
BCFIPS/keystore-format changes are planned. Existing Maven unit tests and Newman
provide the runtime gates. Pause for scope approval if implementation requires
changing those boundaries.

Do not inspect secret values. Use the confirmed App setup. If execution reports a
problem, request the missing secret name from the maintainer.

### Local verification is limited

Inline workflow shell cannot run outside GitHub. Local work therefore proves
syntax, structure, and review only. Behavior is proved by the maintainer's
rehearsals in section 8, which is how Transform's release was proved. Expect
rehearsal cycles to find logic errors, and budget more than one window.

### Execution boundary

Sections 1 to 7 are local work: write and review workflow files, and run local
lint and Maven checks. Sections 8 and 9 are the maintainer's work. The maintainer
is the only actor who operates GitHub and AWS for this change.

An assistant working this checklist must never perform these actions:

- Dispatch, re-run, or cancel any GitHub Actions workflow, by UI, API, or
  `gh workflow run`.
- Push any branch, tag, or ref to `origin`, including test branches such as
  `developalm` and `mainalm`.
- Change the default branch, a branch ruleset, or a workflow enabled state.
- Create, edit, publish, or delete a GitHub Release, a tag, or a Pages
  publication.
- Write to GHCR, dev ECR, APHL ECR, dev ECS, or any other AWS resource.
- Commit or amend anything without the explicit approval of the maintainer.
- Mark a task in section 8 or 9 complete from a plan, an inference, or an
  expected result.

For each task in sections 8 and 9, the assistant prepares inputs, drafts the
exact commands and dispatch values, and states the expected result. The
maintainer runs the action. The assistant then records only the evidence that the
maintainer supplies. If evidence is absent, the task stays unchecked.

- [x] 1.1 Add `.github/workflows/release.yml` and `.github/workflows/hotfix.yml`
  as thin dispatch wrappers with the spec's typed inputs, the required
  permissions, and workflow-level `concurrency: dev` with
  `cancel-in-progress: false`. Keep Transform's hotfix branch guard.
  **Done when:** the input names, types, and defaults match the spec, both
  wrappers call `_release_common.yml` with `secrets: inherit`, and the operator
  descriptions warn that a dry-run uses shared dev and writes real tags.
- [x] 1.2 Add `.github/workflows/_release_common.yml` with one reusable release
  job: App token generation, checkout, Java 21, Maven, the toolchain and settings
  setup that `maven.yml` uses, and Git identity for the App. Do not reacquire the
  `dev` concurrency group. Do not fall back to `secrets.ACTIONS_KEY` or an SSH
  identity. **Done when:** review confirms the App identity for repository
  writes, `GITHUB_TOKEN` for package reads and PR reads, an explicit failure when
  App token generation fails, and no token value in any output or log.
- [x] 1.3 Add the input validation step to `_release_common.yml`. Pass inputs as
  environment values and validate them as data. Cover the `X.Y.Z` release
  version, the optional next version in `X.Y.Z` or `X.Y.Z-IZGW-SNAPSHOT` form,
  the automatic next minor version with patch zero, valid and distinct
  base and trunk branch names, and the boolean dry-run.
  **Done when:** review confirms rejection of the spec's invalid examples,
  exactly one `-IZGW-SNAPSHOT` suffix on the computed next version, and that this
  step precedes every release-controlled write.
- [x] 1.4 Add the dispatch-context and release-identity validation step to
  `_release_common.yml`. Require a standard release to run from its base branch
  and a hotfix to run from a matching `hotfix/X.Y.Z` branch over released trunk
  history. Use explicit remote ref lookups. Reject an existing release branch,
  version tag, or GitHub Release for the requested version.
  **Done when:** review confirms that a failed remote or API lookup fails the run
  instead of reading as an absent object, that an absent trunk is allowed only
  for a standard release, and that no existing object is altered on rejection.
- [x] 1.5 Add the shared dependency prerequisite step to `_release_common.yml`.
  Read the resolved parent and `izgw-core` versions from Maven's resolved model
  and dependency list, not from the text of `pom.xml`.
  **Done when:** review confirms rejection of a SNAPSHOT parent, a SNAPSHOT core,
  an unresolved core, and a failed Maven resolution, and confirms that the step
  changes no dependency declaration.

## 2. Candidate Preparation and Build

- [x] 2.1 Add release-notes generation to `_release_common.yml`. Use the
  preceding reachable version tag and the source range captured before this run's
  generated commits. Resolve and deduplicate merged PR titles and links. Use
  commit descriptions only for a genuine no-PR result.
  **Done when:** review confirms Hub's `# IZ Gateway Release X.Y.Z` heading,
  preserved historical entries, replacement of a retried unpublished entry
  instead of a duplicate heading, and that a GitHub read failure fails the step
  rather than selecting the commit fallback.
- [x] 2.2 Add `docs/release` staging to `_release_common.yml`. Build it from
  tracked root Markdown, preserving Hub's existing selection: omit the root
  `README.md` and use the generated current-release notes in place of the whole
  history. **Done when:** review confirms a fresh staging set so that removed
  documents do not survive as stale attachments, and that untracked local files
  such as `CLAUDE.local.md` are never copied.
- [x] 2.3 Add candidate preparation to `_release_common.yml`: create
  `release/X.Y.Z` from the dispatched base SHA for a standard release, retain the
  operator's branch for a hotfix, set `X.Y.Z-IZGW-RELEASE` with Maven version
  tooling, and commit only the intended tracked paths. Make the `<name>` element
  in `pom.xml` version-derived so that it does not go stale.
  **Done when:** review confirms selective staging with no backup POMs and no
  whole-workspace `git add -A`, unchanged artifact coordinates and dependency
  declarations, and a recorded candidate commit ID.
- [x] 2.4 Wire Hub's Maven build, test, and site generation plus the blocking
  dependency scan into `_release_common.yml`. Preserve Java 21, `SPRING_DATABASE=jpa`,
  the test keystores, and `COMPUTERNAME`. Use `X.Y.Z-RELEASE-<run>` as
  `image.tag` so that the scanner reads `target/<image.tag>.jar`.
  **Done when:** review confirms that the release scan omits
  `continue-on-error: true` and blocks at CVSS 7, that an unsuccessful scan is a
  failure rather than zero findings, that the project's suppression file and NVD
  cache are retained, and that dev CI scan policy is unchanged.
- [x] 2.5 Add one Buildx image build and explicit GHCR and dev ECR pushes to
  `_release_common.yml`, with `JAR_FILENAME` and `IZGW_VERSION` set from
  `image.tag`. Capture `target/classes/build.txt` values and the registry
  manifest digest. **Done when:** review confirms the
  `X.Y.Z-RELEASE-<run>`, `X.Y.Z-RELEASE`, and `latest` tags, no `--all-tags`
  publication, no second image build, and that the recorded digest and build
  metadata are carried forward as step outputs.

## 3. Shared Deployed-Hub Verification

Hub has dev-CI verification worth sharing with the release path, and Transform
has none. This section is the one deliberate addition beyond Transform's shape.
Keep the shell inline in each composite action.

- [x] 3.1 Extend `.github/actions/ecs-deploy/action.yml` to record a per-region
  deployment receipt as each region succeeds. Preserve the existing inputs and
  the `deployment_ids` output for current callers.
  **Done when:** review confirms that partial progress stays available when a
  later region fails, and that `maven.yml` keeps working unchanged.
- [x] 3.2 Extend `.github/actions/ecs-wait-healthy/action.yml` with bounded
  service-stability waiting, an intended-primary-deployment check, and an
  expected Hub image digest check in every configured region. Identify the Hub
  container through the service's load-balancer container mapping.
  **Done when:** review confirms failure on a replaced or rolled-back deployment,
  on absent digest evidence, on an unhealthy target, and on a mismatched region,
  and confirms backward compatibility for existing callers.
- [x] 3.3 Create `.github/actions/verify-hub/action.yml` to orchestrate health
  and logging verification. Select CloudWatch streams from the verified task IDs
  and their logging configuration, with bounded waiting, in place of the current
  `taskArns[0]` lookup. **Done when:** review confirms that an unrelated task's
  logs cannot satisfy the check, that missing logging evidence fails, and that
  the action performs no image promotion and no release publication.
- [x] 3.4 Move the Newman setup, smoke warmup, and authoritative `Working` run
  from `.github/workflows/maven.yml` into `verify-hub`. Retain the keepalive
  patch, the JWT inputs, host-scoped mTLS, and the no-cert host. Accept the
  expected build and timestamp as inputs, and resolve
  `testing/certs/izgwroot.pem` independently of the working directory.
  **Done when:** review confirms the corrected CA path, nonzero failure
  propagation, rejection of missing required inputs, and no weakening of the
  existing collection assertions. The `build` and `timestamp` values are empty
  today: `maven.yml:497-499` reads `target\classes\build.txt`, and bash turns
  `\c` and `\b` into plain characters, so the path becomes
  `targetclassesbuild.txt` (shellcheck SC1001). The collection then applies
  `|| ".*"`, so the assertion matches any value. Use `target/classes/build.txt`
  and confirm the expected format of both values before the first rehearsal.
- [x] 3.5 Add temporary access and sensitive-file handling to `verify-hub`.
  Record only an ingress rule that this run created, and remove that rule on
  success or failure. **Done when:** review confirms that a pre-existing rule is
  never removed, that a revoke failure is reported rather than hidden, and that
  private keys and the password-bearing certificate list are excluded from the
  action's artifacts.

## 4. Gates, Promotion, and Publication

- [x] 4.1 Wire deployment and `verify-hub` into `_release_common.yml`, passing
  the candidate digest and the captured build metadata explicitly. Place every
  publication step after the gate. **Done when:** review confirms that each
  required gate applies to this candidate, and that a failed or missing gate
  blocks APHL delivery, the trunk merge and tag, Pages, and the GitHub Release.
- [x] 4.2 Add promotion by digest to `_release_common.yml`: advance the dev ECR
  `good` tag to the verified digest, and deliver that same image to APHL as
  `izgw-hub-X.Y.Z` for a real release only. **Done when:** review confirms that
  the promotion source is the recorded digest and never a later lookup of
  mutable `latest`, that no second build occurs, that APHL credentials are
  configured only for real delivery, and that dev credentials are restored
  afterwards.
- [x] 4.3 Add the merge, tag, and back-merge steps to `_release_common.yml`
  following Transform's approach: merge into the trunk with `--no-ff -X theirs`,
  create annotated `vX.Y.Z` on the accepted trunk commit, and back-merge into the
  base with `--no-ff -X ours`. Apply the next development version for a standard
  release, and preserve the base development version for a hotfix. Report hotfix
  changes that the back-merge omitted as warnings for manual review.
  **Done when:** review confirms non-forced pushes with expected-tip checks, that
  only version metadata is rewritten rather than the whole old POM, that
  generated release notes omitted by a conflict are flagged, and that a warning
  alone does not fail the release.
- [x] 4.4 Add Pages publication and GitHub Release creation to
  `_release_common.yml`. Publish the Maven site to `current/` and `vX.Y.Z/`, or
  to `test/current/` and `test/vX.Y.Z/` for a dry-run. Attach the retained
  `docs/release` Markdown and the current-release notes.
  **Done when:** review confirms a non-draft, non-prerelease release for a real
  run and a draft for a dry-run, the recorded release ID, and that a partial
  publication invokes failure handling instead of reporting success.

## 5. Failure Handling and Summary

- [x] 5.1 Add run-owned cleanup to `_release_common.yml` using Transform's
  per-step flags. Record a `THIS_RUN_*` flag and the resulting object ID
  immediately after each confirmed write, and capture the expected remote tips
  before the first write. On failure, delete a standard-release branch, a version
  tag, and a GitHub Release only when the recorded object still matches the
  remote, and revert recorded trunk and base commits in reverse order when that
  is safe. Preserve the operator's hotfix branch.
  **Done when:** review confirms compare-and-delete conditions in place of an
  unguarded existence check, deletion of a GitHub Release by recorded ID rather
  than by tag name, no forced reset of shared history, no automatic rollback of
  images, Pages, or dev, and that cleanup errors stay visible without replacing
  the original failure.
- [x] 5.2 Add the run summary and an allowlisted artifact set to
  `_release_common.yml`. Report release type, version, source, branches, dry-run
  mode, completed gates, candidate digest, published outputs, the next
  development version, hotfix warnings, and any residual external effect.
  **Done when:** review confirms that a failure before the image build claims no
  image identity and no rollback, that dry-run side effects on shared dev and
  global tags are stated, and that the replacement for
  `path: .` excludes private keys, the certificate list, Maven settings, and
  token-bearing Git configuration.

## 6. Development CI Integration

- [ ] 6.1 Refactor `.github/workflows/maven.yml` to call `verify-hub` with
  build-time metadata and the exact candidate digest, and to promote its verified
  `good` tag by digest. Retain develop push and PR triggers, the schedule, manual
  CI, and the existing development scan policy. Remove the legacy release and
  APHL paths, and delete `.github/workflows/main.yml` from the new source line
  only. Remove the `List m2` debug step, which interpolates `github.base_ref`
  and `github.head_ref` straight into an inline script (`maven.yml:168-170`).
  **Done when:** review confirms every retained CI entry point and output,
  no release publication in dev CI, no change to any legacy branch ref or
  content, and no increase in the `maven.yml` `actionlint` count. The shared-CI
  regression run is covered in section 8.
- [x] 6.2 Confirm workflow-level `concurrency: dev` with
  `cancel-in-progress: false` on `maven.yml`, `release.yml`, and `hotfix.yml`,
  and confirm that `_release_common.yml` and the composite actions do not
  reacquire it. **Done when:** review confirms coverage from the first
  conflicting write through cleanup, no caller and callee deadlock, and no
  release dependency on the CI run that its own base push enqueues. Live overlap
  evidence is captured during rehearsal.

## 7. Documentation and Pre-rehearsal Review

- [ ] 7.1 Create `docs/release-automation.md`, link it from `README.md`, and
  update `.github/copilot-instructions.md`, `.claude/CLAUDE.md`, and
  `openspec/config.yaml` for the new CI and branching model. Document dependency
  preparation, input examples, App and secret names, side-effecting dry-runs,
  warnings, recovery, and the staged cutover.
  **Done when:** the runbook preserves old branch names and history,
  distinguishes reference-only branches from new release and hotfix branches,
  explains temporary default-branch restoration and manual-dispatch limits, and
  requires separate approval for the first real release. Do not pre-populate a
  real `RELEASE_NOTES.md` entry; its generator is covered by task 2.1.
  The runbook must also state these three operator rules:
  - Act on every hotfix review warning before the next standard release. That
    release replaces omitted hotfix content on the trunk without a conflict,
    because the trunk equals the merge base of the next release.
  - Return the base branch to SNAPSHOT `izgw-bom` and `izgw-core` versions after
    every standard release. The workflow does not change dependency versions.
  - Select a rehearsal version that no planned release uses. A dry-run writes a
    real global version tag.
  The runbook must record the new versioned Pages path `vX.Y.Z` and the earlier
  path that used the complete Maven version.
- [ ] 7.2 Complete a security review checkpoint for the new workflows and the
  touched `.github/actions/` code. Review input quoting and injection through
  workflow expressions, App permissions and token renewal, conditional Git
  writes, temporary ingress ownership, mTLS test boundaries, and secret-safe and
  PHI-safe diagnostics. `actionlint` flags one injection site in existing code at
  `maven.yml:168`. Confirm that no new step interpolates an operator-supplied
  input or a ref name into a script body; pass such values through `env` and
  quote them. **Done when:** the reviewed revision and the resolved
  in-scope findings are recorded in
  `openspec/changes/igdd-2396-release-automation/rehearsal-results.json`, and no
  runtime FIPS, TLS, or authorization weakening is needed.
- [ ] 7.3 Run the static checks and complete a workflow contract review.
  `actionlint` 1.7.12 is available locally and bundles `shellcheck`.
  Apply this standard, which avoids unrelated cleanup in existing files:
  - `actionlint` must report zero findings for `release.yml`, `hotfix.yml`, and
    `_release_common.yml`.
  - `maven.yml` carries 71 pre-existing findings and `main.yml` carries 16.
    Task 6.1 must not increase the `maven.yml` count. Fixing the rest is out of
    scope for this change.
  - `actionlint` reads workflows only. It rejects a composite action file for a
    missing `on` and `jobs` section, so check each action's shell by extracting
    its `run:` bodies and passing them to `shellcheck` directly.
  **Done when:** results are recorded in
  `openspec/changes/igdd-2396-release-automation/rehearsal-results.json`, the
  review covers early failure, every gate, both conflict preferences,
  digest-based promotion, cleanup ownership, and failure summaries, and no
  runtime outcome is claimed from inspection alone.

## 8. GitHub Rehearsals — the maintainer runs every step

The maintainer performs every live action in this section. An assistant prepares
and records only. See the execution boundary in section 1.

These steps use shared dev and create real remote state. Record actual run IDs,
ref and object IDs, digests, sites, approvals, and residual actions in
`openspec/changes/igdd-2396-release-automation/rehearsal-results.json`, without
credentials. Estimates cover assistant preparation and record work. They exclude
maintainer time, unattended cloud runtime, and approval delays.
Task 8.5 is an exit obligation whenever task 8.1 changes the default branch,
including when later rehearsal steps fail or are cancelled.

Two rehearsal expectations follow from the current pipeline. Dev CI runs the
dependency scanner with `continue-on-error: true`, so the release gate is the
first blocking use of that scanner. Budget triage time for findings that the
current pipeline tolerates. The Newman `build` and `timestamp` assertion is
inactive today, so real metadata can produce a new failure.

Because the inline shell has no offline suite, expect the first rehearsals to
find logic errors. Plan for more than one window.

- [ ] 8.1 Rehearsal setup.
  *Assistant:* ask the maintainer for the window and the inputs. Propose
  rehearsal versions that no planned release uses, because a dry-run writes a
  real global tag. Draft the exact branch content for `developalm` and `mainalm`,
  including the selected released BOM and core versions. Write the
  default-branch restoration procedure. List the branch rulesets to inspect for
  the real `main` and `develop`.
  *Maintainer:* create and push the test branches. Switch the default branch to
  `developalm`. Inspect the rulesets and report whether the release App can write
  the real `main` and `develop` and their tags. Current CI bypasses protection
  with `secrets.ACTIONS_KEY`, and test branches carry no rulesets, so a rehearsal
  cannot prove this access.
  **Done when:** the maintainer reports that the dispatch workflows are
  available, the maintainer acknowledges the scheduled-CI and PR-target impacts,
  the App bypass result for the real branches is recorded in
  `rehearsal-results.json` from the maintainer's report, and no real legacy
  release branch or first real release has been changed.
- [ ] 8.2 Standard-release rehearsal.
  *Assistant:* draft the dispatch inputs, the overlap-attempt sequence, and the
  list of evidence to collect. State the expected result of each gate.
  *Maintainer:* dispatch the standard release with `dry-run=true`. Dispatch the
  refactored manual CI on the test source to attempt the overlap. Supply the run
  URLs and outputs.
  **Done when:** the maintainer's run records show Maven and scanner results and
  the full dev health, digest, logging, and Newman gates, expected Git, image,
  and documentation outputs, the expected next snapshot, no APHL writes, and no
  candidate replacement or active-run cancellation by overlapping CI.
- [ ] 8.3 Hotfix rehearsal.
  *Assistant:* draft the hotfix branch content, its fork point on the rehearsed
  released trunk, and the dispatch inputs. State the expected conflict-review
  warnings.
  *Maintainer:* create and push the hotfix branch. Dispatch the hotfix workflow
  with `dry-run=true`. Supply the run URLs and outputs.
  **Done when:** the maintainer's GitHub evidence shows the exact verified image
  and version tag, the retained base development version, the preserved operator
  branch, correct conflict-review reporting where exercised, test Pages and draft
  attachments, and no APHL writes.
- [ ] 8.4 Forced-failure and cleanup rehearsal.
  *Assistant:* write the failure-injection change and confine it to the test
  branches. Draft the pre-existing objects to create, the partial-publication
  sequence, and the duplicate-version rejection probe. State the expected
  cleanup result.
  *Maintainer:* push the injection change to the test branch. Dispatch the
  failing run. Supply the run URLs, the resulting refs, and the cleanup output.
  **Done when:** the maintainer's GitHub evidence shows failed status, safe
  cleanup of confirmed run-owned state, untouched pre-existing objects, visible
  residual external effects, and no permanent failure or bypass switch in the
  implementation intended for `develop`.
- [ ] 8.5 Rehearsal exit.
  *Assistant:* list the confirmed rehearsal-owned refs and releases for removal.
  Identify the Pages paths, registry aliases, and shared-dev state that stay as
  separate manual items with named owners. Remind the maintainer to restore the
  default branch first.
  *Maintainer:* restore `develop` as the default branch, including after a failed
  rehearsal, before deleting any test branch. Delete only the approved objects.
  Report what was removed.
  **Done when:** the maintainer confirms that the actual default is restored, and
  the rollout record identifies removed objects and residual effects with
  explicit owners and recovery actions. Unsuccessful rehearsals remain unchecked.

## 9. Cutover and Handoff — the maintainer runs every step

The maintainer performs every live action in this section. An assistant prepares
and records only. See the execution boundary in section 1.

- [ ] 9.1 Cutover controls.
  *Assistant:* draft the cutover checklist. Record the current legacy branch
  names and object IDs before the change. Draft the ruleset settings and the
  workflow path or ID to disable.
  *Maintainer:* confirm the window. Drain or cancel legacy release runs and stop
  legacy dispatches. Freeze updates and deletions on the existing `Release*`
  branches with no automation bypass. Disable the old
  `.github/workflows/main.yml` workflow repository-wide by path or ID.
  Do not start the cutover until the recorded App bypass result from task 8.1
  confirms write access to the real `main` and `develop`.
  **Done when:** the rollout record shows the applied controls, and the
  before-and-after legacy branch names and object IDs are unchanged. No old
  branch was renamed, deleted, or rewritten.
- [ ] 9.2 Merge into `develop`.
  *Assistant:* prepare the branch and the pull request content for review. Wait
  for approval before any commit.
  *Maintainer:* review, approve, and merge the pull request into `develop`
  through the agreed repository process. Keep `develop` as the default branch.
  **Done when:** the maintainer reports that the post-cutover CI run passes the
  shared verification path, the new dispatch workflows are available, legacy
  `main.yml` is disabled, `maven.yml` still serves development CI, and `main` has
  not been advanced by an unapproved real release.
- [ ] 9.3 Rollout record and operator handoff.
  *Assistant:* complete
  `openspec/changes/igdd-2396-release-automation/rehearsal-results.json` and
  `docs/release-automation.md` from the evidence that the maintainer supplied.
  Record no result that the maintainer did not report.
  *Maintainer:* accept the handoff.
  **Done when:** all required rehearsals and the CI regression have real
  evidence, remaining external recovery is explicit, maintainers can locate
  release and recovery instructions, and the first real release remains a
  separately approved action rather than an automatic final step of this change.

## 10. Task Summary

Rough active engineering estimates; each task is a 1-4 hour work unit. These are
not elapsed-time promises. Rehearsal failures can require fixes and another
approved window. Dependencies identify prerequisites, not permission to perform
live mutations.

The estimates fell from the earlier plan because the inline approach drops a
shell library and its offline suite. That moves verification effort into the
rehearsal windows in section 8 rather than removing it.

| Task | Hours | Dependencies |
| --- | ---: | --- |
| 1.1 | 2 | None |
| 1.2 | 3 | 1.1 |
| 1.3 | 2 | 1.2 |
| 1.4 | 3 | 1.2 |
| 1.5 | 2 | 1.2 |
| 2.1 | 3 | 1.3, 1.4 |
| 2.2 | 2 | 2.1 |
| 2.3 | 3 | 1.3, 1.4, 1.5, 2.1 |
| 2.4 | 3 | 2.3 |
| 2.5 | 3 | 2.4 |
| 3.1 | 2 | None |
| 3.2 | 3 | 3.1 |
| 3.3 | 3 | 3.2 |
| 3.4 | 3 | 3.3 |
| 3.5 | 2 | 3.3, 3.4 |
| 4.1 | 3 | 2.5, 3.1, 3.2, 3.3, 3.4, 3.5 |
| 4.2 | 2 | 4.1 |
| 4.3 | 4 | 4.1 |
| 4.4 | 3 | 2.2, 4.3 |
| 5.1 | 4 | 4.2, 4.3, 4.4 |
| 5.2 | 2 | 5.1 |
| 6.1 | 4 | 3.5, 4.2 |
| 6.2 | 1 | 1.1, 6.1 |
| 7.1 | 4 | 5.2, 6.1 |
| 7.2 | 2 | 5.2, 6.2, 7.1 |
| 7.3 | 2 | 7.2 |
| 8.1 | 4 | 7.3; maintainer-approved inputs/window |
| 8.2 | 4 | 8.1 |
| 8.3 | 4 | 8.2 |
| 8.4 | 3 | 8.1, 8.3 |
| 8.5 | 2 | Exit obligation from 8.1, after attempts including failures |
| 9.1 | 2 | 8.2, 8.3, 8.4, 8.5; maintainer-approved cutover |
| 9.2 | 3 | 9.1; approved merge |
| 9.3 | 1 | 9.2 |
| **Total** | **93** | **34 tasks** |
