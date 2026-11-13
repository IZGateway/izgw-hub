---
schema_version: '1.0'
created:
  date: '2026-11-13T07:05:05.194Z'
  user: boonek
  agent:
    name: claude-code
    version: 1.0.0
  llm:
    name: claude-sonnet-5
    version: '5'
  prompt_uri: >-
    prompt:/claude-code/6489d253-64a8-47f7-9d36-4bd4d59d6e03/~dd641be6-9c76-48ec-9370-9243b651be97
  summary: Task breakdown for annual RI aggregate ZIP submission support
updated:
  - date: '2026-11-13T17:30:22.818Z'
    user: boonek
    agent:
      name: claude-code
      version: 1.0.0
    llm:
      name: claude-sonnet-5
      version: '5'
    prompt_uri: >-
      prompt:/claude-code/6489d253-64a8-47f7-9d36-4bd4d59d6e03/~5d2b09a6-68c3-406a-834d-ca5307759040
    summary: >-
      Sync task 2.3's period value reference to the now-decided bare 2025
      format; Resolve 3.3's blocking note; rewrite Task 4 as completed
      implementation of PERIOD_PATTERN bare-year support plus the new frequency
      cross-check
  - date: '2026-11-13T16:59:14.845Z'
    user: boonek
    agent:
      name: claude-code
      version: 1.0.0
    llm:
      name: claude-sonnet-5
      version: '5'
    prompt_uri: >-
      prompt:/claude-code/6489d253-64a8-47f7-9d36-4bd4d59d6e03/~e19f6c61-cc21-402b-8034-d685cbb7dd17
    summary: >-
      Check off completed Groups 1-3.1 work, document actual test scope and
      Newman addition, sync NIH->XXA, add pending PERIOD_PATTERN decision task
  - date: '2026-11-13T15:47:17.810Z'
    user: boonek
    agent:
      name: claude-code
      version: 1.0.0
    llm:
      name: claude-sonnet-5
      version: '5'
    prompt_uri: >-
      prompt:/claude-code/6489d253-64a8-47f7-9d36-4bd4d59d6e03/~7dcdcce1-6482-49e3-81c9-de3d1dba562c
    summary: >-
      Sync task 3.3 wording: YYYY is now a confirmed decision, not an open
      question
change_request: annual-ri-aggregate-zip-submission
ticket: IGDD-3743
---
# Tasks

## 1. ZIP Eligibility and Cadence-Driven Date Matching

- [x] 1.1 In `src/main/java/gov/cdc/izgateway/ads/MetadataBuilder.java`, add a
      package-private static helper `isAnnual(String reportType)` that returns true
      when the report type contains `"annual"` case-insensitively. Verified by
      compiling (`mvn compile`) and by the new `isAnnual_*` tests in
      `MetadataBuilderValidateZipMetadataTests.java`.
- [x] 1.2 Replace the ZIP file-type-mismatch check in `validateZipMetadata()` — was
      keyed off `pf.getFiletype().equalsIgnoreCase(meta.getExtEvent())` against the
      hardcoded `ZipFilenameComponents.ROUTINE_IMMUNIZATION` literal — with a check
      against `meta.getExtEvent()` directly: eligible when it equals
      `routineImmunization` (case-insensitive) or `isAnnual(...)` is true. Verified
      with new unit tests in `MetadataBuilderValidateZipMetadataTests.java` covering:
      `routineImmunization` zip accepted, `riAnnualAggregate` zip accepted,
      `riQuarterlyAggregate` zip rejected with a file-type error, `genericImmunization`
      zip skips the check. `mvn test -Dtest=MetadataBuilderValidateZipMetadataTests`:
      15/15 passing.
- [x] 1.3 Replace the hardcoded `divisor = pf.getFiletype().equals(ROUTINE_IMMUNIZATION) ? 3 : 1`
      in `validateZipMetadata()` with `isAnnual(meta.getExtEvent()) ? 12 : 3`. Verified
      with unit tests covering: a `routineImmunization` filename in a different month
      but same quarter as the period matches (existing behavior preserved); a
      `riAnnualAggregate` filename in a different month but same year as the period
      matches; a `riAnnualAggregate` filename in a different year does not match.
- [x] 1.4 Removed the now-unused `ZipFilenameComponents.ROUTINE_IMMUNIZATION` constant
      and the `filetype` field/assignment entirely (confirmed via
      `grep -rn "getFiletype\|ROUTINE_IMMUNIZATION" src/main/java` that no other caller
      depended on it before deleting). Verified by `mvn compile` succeeding cleanly.
- [x] 1.5 Updated the Javadoc on `ZipFilenameComponents` and
      `MetadataBuilder.validateZipMetadata()` to describe the new name-based
      eligibility rule and cadence-driven divisor, replacing the outdated "ZIP
      filenames are routine immunization only" framing.

## 2. Verification Against the NDLP Test Artifact

- [x] 2.1/2.2 **Scoping note:** tasks originally referenced
      `ADSControllerTests.java`, but no such class (or any Spring-context ADS
      integration test) exists in this repo, and no test anywhere previously
      exercised `ZipFilenameComponents`/`validateZipMetadata` at all. Rather than
      introduce a new full-Spring-context integration test, verification was folded
      into `MetadataBuilderValidateZipMetadataTests.java` (package-private,
      no-Spring-context style already used by `MetadataBuilderComputationTests` /
      `MetadataBuilderSetReportTypeTests`), which exercises the full
      `setReportType()` → `setPeriod()` → `setFilename()` → `build()` chain:
    - [x] End-to-end: `riAnnualAggregate` + `.zip` accepted, `data_stream_id` computed
          as `ri-annual-aggregate`.
    - [x] Regression guard: `riQuarterlyAggregate` + `.zip` still rejected
          (file-type-mismatch error).
      All 15 tests in the class pass (`mvn test -Dtest=MetadataBuilderValidateZipMetadataTests`).
- [x] 2.3 **Added a Newman/Postman test** — not in the original task list, added per
      Keith's instruction. `testing/scripts/IZGW_2.0_Integration_Test.postman_collection.json`,
      "DEX Tests" folder: new request "Write riAnnualAggregate Submission to DEX Mock"
      inserted immediately after "Write riQuarterlyAggregate Submission to DEX Mock",
      submitting `XXA_20251231_20260131_Agg_Z.ZIP` with `reportType=riAnnualAggregate`,
      `facilityId=XXA`, `period=2025`. Also added `ri-annual-aggregate` to that
      folder's `checkDexResponse` `data_stream_id` whitelist regex (it only covers
      report types that actually exist, so the new type needed adding). Verified: JSON
      parses cleanly (`node -e "JSON.parse(...)"`); not run live (no dev host access in
      this session — this is a Newman test, run via CI/CD or manually against dev).
- [x] 2.4 **Renamed the test artifact** from `testing/testdata/NIH_20251231_20260131_Agg_Z.ZIP`
      to `testing/testdata/XXA_20251231_20260131_Agg_Z.ZIP` — `XXA` is the
      already-established testing destination used elsewhere in this collection
      (e.g. the `riQuarterlyAggregate` test), rather than `NIH`. All references to the
      old filename in `proposal.md`, `design.md`, and `specs/zip-filename-validation/spec.md`
      synced to match.

## 3. Deployment Configuration (tracked, not code)

- [x] 3.1 Add a `riAnnualAggregate` `FileType` registry entry in the dev environment
      (DynamoDB, via the same mechanism used for `riQuarterlyAggregate` in
      IGDD-2464) — **done by Keith.**
- [ ] 3.2 Resubmit the actual NDLP test artifact
      (`testing/testdata/XXA_20251231_20260131_Agg_Z.ZIP`) against dev end-to-end once
      both 1.x code and 3.1 configuration are deployed. Verify: submission succeeds
      and the file lands in NDLP's expected `ri-annual-aggregate` folder path.
- [ ] 3.3 Confirm with NDLP that the delivered `meta_ext_submissionperiod` value
      (`"2025"`, per Task 4 below) is accepted as-is during dev testing.

## 4. Period Format: Bare YYYY + Report-Type Frequency Cross-Check

Keith confirmed option (b): `period=2025` (bare year), not `period=2025-12-31`,
matching how monthly (`YYYY-MON`, e.g. `"2022-SEP"`) and quarterly (`YYYYQ#`, e.g.
`"2025Q4"`) each encode exactly their own granularity in the real Postman collection
data. Keith also asked for a new cross-check: the `period` value's shape must match
the submitted report type's established frequency — this did not exist before this
change (`PERIOD_PATTERN` only checked that `period` matched *some* recognized shape).

- [x] 4.1 In `src/main/java/gov/cdc/izgateway/ads/MetadataBuilder.java`, make
      `PERIOD_PATTERN`'s trailing suffix group optional (one added `?`) so a bare
      `\d{4}` validates. Verified: `mvn compile` succeeds;
      `MetadataBuilderSetPeriodTests.setPeriod_bareYear_isStructurallyValid` and
      `setPeriod_existingFormats_stillValid` pass.
- [x] 4.2 Add `MetadataBuilder.classifyPeriodFrequency(String)` (package-private
      static) — classifies an already-valid period as `Quarterly`/`Monthly`/`Annual`,
      or `null` for the legacy numeric `YYYY-MM`/`YYYY-MM-DD` form. Verified:
      `classifyPeriodFrequency_recognizedShapes` and
      `classifyPeriodFrequency_legacyNumericDate_returnsNull` pass.
- [x] 4.3 Add `MetadataBuilder.establishedFrequency(String)` (package-private
      static) — `Annual` via `isAnnual(...)`, `Quarterly` for `routineImmunization`
      or any name containing `"quarter"`, `Monthly` otherwise. Verified:
      `establishedFrequency_knownTypes` (8 known report types) and
      `establishedFrequency_annualCheckTakesPrecedenceOverRiPrefix` pass.
- [x] 4.4 Wire the cross-check into `setPeriod()`: after structural validation
      passes, compare `classifyPeriodFrequency(period)` against
      `establishedFrequency(meta.getExtEvent())`, skipping when the classification is
      `null` (legacy form) or the report type is `genericImmunization`. Verified:
      `setPeriod_matchingFrequency_noCrossCheckError` (4 cases),
      `setPeriod_mismatchedFrequency_addsError` (8 cases),
      `setPeriod_genericImmunization_skipsCrossCheck` (3 cases), and
      `setPeriod_legacyNumericDate_skipsCrossCheckRegardlessOfReportType` all pass.
      `mvn test -Dtest=MetadataBuilderSetPeriodTests`: 35/35 passing.
- [x] 4.5 Updated the Newman annual test's `period` field (and `X-Message-ID`
      descriptive text) from `2025-12-31` to `2025`, and updated the four
      `riAnnualAggregate`-related cases in
      `MetadataBuilderValidateZipMetadataTests.java` to use `period="2025"` instead
      of the placeholder `"2025-12-31"` used before this was decided. Verified: both
      test classes re-run clean (15/15 and 35/35).
- [x] 4.6 Added `specs/period-frequency-validation/spec.md` (new capability — this
      behavior applies to every report type and file format, not just ZIP, so it
      does not belong under `zip-filename-validation`) and updated `proposal.md`'s
      Capabilities/Impact sections and `design.md`'s Decisions/Risks/Goals
      accordingly. Verified: `openspec validate annual-ri-aggregate-zip-submission --strict`
      passes.

## Workflow follow-up

- Archive this change once the above is verified end-to-end and merged, per the
  repo's standard `openspec archive annual-ri-aggregate-zip-submission` workflow.
- Update `projects-in-progress.md` to reflect the CR's progress/completion.
