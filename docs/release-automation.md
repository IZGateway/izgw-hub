# Release automation

IZ Gateway Hub releases run from two manually dispatched GitHub Actions
workflows. This page is the operator runbook: what to prepare, what to dispatch,
what each run writes, and how to recover when one fails.

The model follows
[izgw-transform](https://github.com/IZGateway/izgw-transform), with the
additions Hub needs: a deployment and integration-test gate, and promotion by
image digest.

## The workflows

| Workflow | File | Dispatch from | Purpose |
| --- | --- | --- | --- |
| Release - Standard | `.github/workflows/release.yml` | the base branch, normally `develop` | Cut `X.Y.Z` from the base branch |
| Release - Hotfix | `.github/workflows/hotfix.yml` | `hotfix/X.Y.Z` | Cut a patch release from released trunk history |
| Release - Common | `.github/workflows/_release_common.yml` | not dispatched directly | The shared release job both wrappers call |
| Java CI with Maven | `.github/workflows/maven.yml` | automatic on `develop`, or manual | Development CI. Publishes no release |

Releases and development CI share the `dev` concurrency group, so only one of
them changes the shared dev environment at a time. A later dispatch waits; it
does not cancel a run that is in progress.

## Before you dispatch

### 1. Release the shared libraries first

The release requires released, non-SNAPSHOT versions of both `izgw-bom` (the
parent) and `izgw-core`. The workflow reads the resolved Maven versions and
stops before any write if either is a SNAPSHOT. It never changes a dependency
version for you.

Release `izgw-bom` and `izgw-core`, then set the released versions in `pom.xml`
on the base branch and merge that first.

### 2. Choose the version

`release-version` is three numbers, `X.Y.Z`. No `v` prefix and no suffix. The
workflow derives everything else:

| Output | Form | Example |
| --- | --- | --- |
| Maven version on the release | `X.Y.Z-IZGW-RELEASE` | `2.17.0-IZGW-RELEASE` |
| Git tag | `vX.Y.Z` | `v2.17.0` |
| Release branch | `release/X.Y.Z` | `release/2.17.0` |
| Container tags | `X.Y.Z-RELEASE-<run>`, `X.Y.Z-RELEASE`, `latest`, `good` | |
| APHL tag | `izgw-hub-X.Y.Z` | `izgw-hub-2.17.0` |
| Maven site | `current/` and `vX.Y.Z/` | |

`next-snapshot-version` is optional. Leave it blank to increment the minor
version and reset the patch: a release of `2.17.3` sets the base branch to
`2.18.0-IZGW-SNAPSHOT`. Supply `X.Y.Z` or `X.Y.Z-IZGW-SNAPSHOT` to override it.

### 3. Confirm the credentials

The release App writes the repository. Two secrets must exist:

- `RELEASE_AUTOMATION_APP_ID`
- `RELEASE_AUTOMATION_APP_KEY`

The App must also be allowed to write `main` and `develop` and their tags. Test
branches carry no rulesets, so a rehearsal cannot prove that access. Check the
branch rulesets directly before the first real release.

Everything else already exists for development CI: `COMMON_PASS`,
`ELASTIC_API_KEY`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `NVDAPIKEY`,
`TESTING_CERT`, `TESTING_KEY`, `TESTING_PASS`, the `BEARER_TOKEN_*` values, and
the `APHL_*` values. Repository variables: `AWS_REGION`, `AWS_REGIONS`,
`ECS_DESIRED_COUNT`, `AWS_SECURITY_GROUP_ID`.

## Standard release

Dispatch **Release - Standard** from the base branch. The workflow fails if the
dispatch branch and `base-branch` do not match.

| Input | Value |
| --- | --- |
| `release-version` | `2.17.0` |
| `next-snapshot-version` | blank, or `2.18.0-IZGW-SNAPSHOT` |
| `base-branch` | `develop` |
| `trunk-branch` | `main` |
| `dry-run` | `false` for a real release |

## Hotfix release

Prepare the branch yourself, from released trunk history:

```
git checkout -b hotfix/2.17.1 origin/main
# make the fix
git commit -am "fix: the problem"
git push origin hotfix/2.17.1
```

Then dispatch **Release - Hotfix** from that branch. The branch name and the
version must match exactly, and a version tag must be an ancestor of the branch.

A hotfix keeps the base branch's development version. If `develop` is on
`2.18.0-IZGW-SNAPSHOT`, it stays there.

A hotfix runs the release workflows from its own branch, which you cut from
`main`. A change to the release workflows on `develop` does not apply to
hotfixes until a standard release merges it into `main`.

## What the gates are

A release completes only after all of these pass:

1. Maven build and unit tests
2. OWASP dependency scan, blocking at CVSS 7 or above
3. Deployment to the dev ECS service
4. The deployment is stable and still the primary deployment
5. The Hub container is running the exact candidate image digest
6. The logging client connected, checked in the verified tasks' own log streams
7. The Newman integration suite

The candidate branch and its container images are written before the gates,
because the candidate has to be deployed to be tested. Everything else — APHL
delivery, the trunk merge and tag, Pages, and the GitHub Release — happens only
after every gate passes.

## Operator rules

These four are not optional. Each one exists because it caused a real problem.

### Act on every hotfix review warning

The back-merge prefers base content on a conflict, so a hotfix change can be
left on the trunk and never reach the base branch. When that happens the run
warns:

> The back-merge into develop did not carry these hotfix paths in full: ...

Apply those changes to the base branch by hand before the next standard
release. **The next release will not warn you again.** After a hotfix, the trunk
equals the merge base of the next release, so the release content replaces the
omitted change with no conflict and no report. The warning is the only signal
you get.

### Return the base branch to SNAPSHOT dependencies

The release requires released `izgw-bom` and `izgw-core` versions, and the
back-merge leaves the base branch pinned to them. Set them back to SNAPSHOT
after every standard release. The workflow does not do it.

### Pick a rehearsal version nothing plans to use

A dry-run is a rehearsal, not a simulation. It writes a **real** `vX.Y.Z` tag.
If you rehearse with the next real version, that tag then blocks the real
release through the duplicate check. Use something unmistakable such as
`99.0.0`.

### Delete the draft release a failed dry-run leaves behind

Every dry-run creates a draft GitHub Release. A run that fails during
publication may leave it. The duplicate check does see drafts, so the next run
for that version will refuse to start until you remove it.

## Dry-run: what it does and does not skip

`dry-run: true` is a rehearsal against real infrastructure. It **does**:

- deploy to **shared dev**, replacing whatever runs there
- write the real `vX.Y.Z` tag
- write real GHCR and dev ECR images, and move `latest`
- advance the dev ECR `good` tag when the gates pass
- merge and push the trunk and the base branch

It **does not**:

- deliver to APHL, or configure APHL credentials at all
- publish Pages to `current/` — it uses `test/current/` and `test/vX.Y.Z/`
- publish the GitHub Release — it creates a draft

Test branches do not isolate shared dev, global tags, or the registries.

## When a release fails

Cleanup runs automatically and reverses only what that run recorded writing,
and only while the remote still matches what it pushed.

| State | What cleanup does |
| --- | --- |
| Base or trunk branch pushed | Restores the recorded previous commit |
| Trunk created by this run | Deletes it |
| Version tag created | Deletes it, after comparing the exact tag object |
| GitHub Release created | Deletes it by recorded id, or resolves it by tag when creation did not finish |
| Standard release branch created | Deletes it |
| Hotfix branch | **Always preserved**, for investigation and retry |
| Unrelated work arrived in the meantime | Leaves it alone and reports it |

Cleanup never reverses these. The run summary lists them with their identities:

- container images in GHCR and dev ECR, including `latest`
- the dev ECR `good` tag
- an image already delivered to APHL
- published Pages
- the deployment on shared dev

Normal development CI overwrites shared dev and `latest` on its next run. The
other items need a deliberate decision.

Retrying is safe. Input, dependency, and duplicate checks all run again, and a
retry never adopts a previous run's objects as its own.

## Branches

- `develop` — the base branch and the repository default.
- `main` — the trunk. Release content reaches it only through a release.
- `release/X.Y.Z` — created by a standard release. Kept after success.
- `hotfix/X.Y.Z` — created by you. Never deleted by the automation.
- `Release_v*` — **historical, reference only.** Frozen at cutover. Their names,
  contents, and history are unchanged, and they still contain the old workflow
  definitions. Do not cut releases from them.

The legacy `main.yml` workflow is removed from the current source line and
disabled repository-wide. Freezing a branch does not disable manual dispatch, so
the rule is simply that nobody dispatches from a historical branch.

## Rehearsing on test branches

Rehearsals use `developalm` and `mainalm` with `dry-run: true`.

`workflow_dispatch` only lists a workflow if the file is on the repository's
**default** branch, and a run executes the file from the ref you select. So a
rehearsal needs the workflows on both the default branch and the branch you
dispatch from. The usual approach is to make `developalm` the default branch for
the rehearsal window.

**Restore `develop` as the default branch when the window closes, including
after a failed rehearsal, and before deleting any test branch.** While
`developalm` is the default it also becomes the target for new pull requests and
the branch scheduled CI runs on.

## Documentation outputs

- `RELEASE_NOTES.md` gains a section per release, generated from the merged pull
  requests in the change set, with titles and links. History is preserved.
- `docs/release` holds the tracked root Markdown, without `README.md`, plus that
  release's notes only. It is rebuilt from a cleared directory, so a document
  removed from the repository root stops being attached.
- The Maven site publishes to `current/` and `vX.Y.Z/`. Earlier releases used
  the complete Maven version in that path, for example `v2.16.0-IZGW-RELEASE`.
  Those directories keep their original names.

## Cutover

1. Confirm the release App can write the real `main` and `develop`.
2. Drain or cancel outstanding legacy release runs, and stop new dispatches.
3. Make the existing `Release_v*` branches read-only, with no bypass for
   automation identities. Do not rename, delete, or rewrite them.
4. Disable the legacy workflow by its path, `.github/workflows/main.yml`. Both
   workflows share a display name, so identify it by path or id.
5. Merge the automation into `develop` and keep `develop` as the default branch.
6. Confirm development CI still passes.
7. Do the first standard release soon after the merge. Until a standard release
   merges the automation into `main`, you cannot dispatch a hotfix. A branch
   that you cut from `main` does not contain `hotfix.yml` or
   `_release_common.yml`. Step 4 disables the legacy workflow, so no automated
   hotfix path exists in this period.

**The first real release needs separate approval.** Successful rehearsals do not
authorise it.

## A note on development CI

`maven.yml` now calls the same `verify-hub` action as the release path, so the
two cannot drift, and it promotes `good` by image digest.

Its dependency scan blocks at CVSS 7 or above. It previously passed
`--data ~/dependency-check-data`, which pointed the scanner at an empty
directory inside the scan container and made every scan fail; `continue-on-error`
hid that. The scan now runs for real, so findings that were invisible before
will fail the build.
