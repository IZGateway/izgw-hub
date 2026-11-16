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
  - date: '2026-11-13T21:06:08.981Z'
    user: boonek
    agent:
      name: claude-code
      version: 1.0.0
    llm:
      name: claude-sonnet-5
      version: '5'
    prompt_uri: >-
      prompt:/claude-code/6489d253-64a8-47f7-9d36-4bd4d59d6e03/~0f4963bc-d59c-4f0d-921d-a60f0932a8ba
    summary: >-
      Correct 6.6's now-stale claim and add 6.7 for the keyword/date-shape
      cross-check fix from the second independent review
  - date: '2026-11-13T20:36:46.617Z'
    user: boonek
    agent:
      name: claude-code
      version: 1.0.0
    llm:
      name: claude-sonnet-5
      version: '5'
    prompt_uri: >-
      prompt:/claude-code/6489d253-64a8-47f7-9d36-4bd4d59d6e03/~db3b975b-82fb-46c0-b844-9bda262bcec2
    summary: >-
      Correct 5.3's now-stale note and add Groups 6-7 for CSV Annual support and
      the DynamoDB trim fix
  - date: '2026-11-13T20:07:40.267Z'
    user: boonek
    agent:
      name: claude-code
      version: 1.0.0
    llm:
      name: claude-sonnet-5
      version: '5'
    prompt_uri: >-
      prompt:/claude-code/6489d253-64a8-47f7-9d36-4bd4d59d6e03/~721aa085-fa53-42a2-910d-82c5cc3c1534
    summary: >-
      Add Group 5 documenting the self-review-driven fixes applied after PR
      #209's independent review
  - date: '2026-11-13T17:36:26.669Z'
    user: boonek
    agent:
      name: claude-code
      version: 1.0.0
    llm:
      name: claude-sonnet-5
      version: '5'
    prompt_uri: >-
      prompt:/claude-code/6489d253-64a8-47f7-9d36-4bd4d59d6e03/~525bf686-2d85-4ae2-965d-3df5700c5329
    summary: Add task 4.7 for the type-specific structural rejection message fix
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
- [x] 4.7 **Fixed the structural-rejection error message**, flagged by Keith: it was
      stale (didn't mention the new bare-`YYYY` form) and, more importantly, not
      specific enough — since the valid form now depends on the report type, it
      names the one expected format (`periodFormatExample(establishedFrequency(...))`)
      instead of listing every structurally-valid shape. `genericImmunization` still
      lists all three, since it has no established frequency. Added
      `specs/period-frequency-validation/spec.md` requirement "Structural Rejection
      Message Names the Expected Format" and design.md Decision 6. Verified: 4 new
      tests in `MetadataBuilderSetPeriodTests` (39/39 total passing).

## 5. Fixes from the Independent Pre-Merge Review (PR #209)

Per the new two-independent-reviews-then-reconcile procedure: `/code-review` ran
against PR #209 before Copilot's findings were read, surfaced 9 items, and Keith
triaged each. Copilot's own review found nothing. Fixed below; the rest (blast-radius
risk of the new frequency cross-check, `setPeriod()`'s unenforced call-order
assumption, and unifying `classifyPeriodFrequency`/`getPeriodAsCalendar` into one
parser) were explicitly left alone — deferred to CI/CD, ignored, or deferred as
not-enough-information, respectively, per Keith's call.

- [x] 5.1 Fixed `MetadataImpl.getPeriodAsCalendar()`: it never got a branch for the
      new bare-`YYYY` annual format, so an annual period's month silently fell back
      to the real-world "now" (masked only by `MetadataBuilder.checkDate()`'s
      divisor-12 trick elsewhere). Now defaults to January deterministically for an
      annual period, via `MetadataBuilder.classifyPeriodFrequency()` (reused, not
      duplicated). New `MetadataImplGetPeriodAsCalendarTests` (3 tests) verify this
      and the pre-existing quarterly/monthly branches.
- [x] 5.2 Replaced `StringUtils.containsIgnoreCase()` with `Strings.CI.contains()` in
      `isAnnual()` and `establishedFrequency()`, per `java-maven-style`'s documented
      (and, in this same diff, already-followed-elsewhere-in-`ZipFilenameComponents`)
      convention. Saved as a feedback memory
      (`feedback_strings_ci_over_stringutils.md`) so this isn't missed again.
- [x] 5.3 Consolidated the `MONTHLY`/`QUARTERLY` constants: `MetadataBuilder.MONTHLY`
      and `.QUARTERLY` now reference `CsvFilenameValidator.FREQ_MONTHLY`/
      `.FREQ_QUARTERLY` (widened to `public`) instead of duplicating the literals.
      (`ANNUAL` was unique to `MetadataBuilder` at the time — Task 6 below later
      gives CSV an annual concept too and completes this consolidation.)
- [x] 5.4 Added an inline comment at the `divisor` computation in
      `validateZipMetadata()` explaining the `12` "always equal, ignore month" trick,
      which previously existed only in `design.md`, not at the code site.
- [x] 5.5 Hoisted the duplicate `isAnnual(reportType)` call in `validateZipMetadata()`
      (eligibility check and divisor computation) to a single local variable.

## 6. CSV Annual Support

Keith: "Introduce the concept of Annual for CSV." Mirrors the ZIP/period-frequency
work but for `CsvFilenameValidator`, which previously only recognized `Monthly`/
`Quarterly` keywords and `YYYYQ#`/`YYYYMMM` date shapes.

- [x] 6.1 Added a third alternative to `FILENAME_PATTERN` — a bare `(\d{4})` — for
      the date segment, shifting the extension capture group from 8 to 9. Verified:
      `mvn compile` succeeds; existing Monthly/Quarterly filenames still parse
      identically (regex backtracking never reaches the new alternative for them).
- [x] 6.2 Added `CsvFilenameValidator.FREQ_ANNUAL = "Annual"` (public, alongside
      `FREQ_QUARTERLY`/`FREQ_MONTHLY`) and a third `parseFilename()` branch detecting
      it case-insensitively in the prefix, mirroring the existing two branches.
      `MetadataBuilder.ANNUAL` now references it, completing Task 5.3's
      consolidation.
- [x] 6.3 Added `CsvFilenameComponents.isAnnual()` and corrected `isMonthly()` from
      `!isQuarterly()` to an explicit `FREQ_MONTHLY.equals(frequency)` match (still
      defaulting `true` for a `null` frequency) — with three frequencies now
      possible, "not quarterly" would have wrongly also been `true` for an annual
      filename. This was a real correctness fix, not just a refactor.
- [x] 6.4 Updated class/method Javadoc (`CsvFilenameValidator`, `CsvFilenameComponents`)
      and the structural-rejection error message to mention `Annual`/bare-`YYYY`.
- [x] 6.5 Added 4 new tests to `CsvFilenameValidatorTests` (`AnnualRI_XXA_2026.csv`
      plain case, `riAnnualAggregate_XXA_2026.csv` mid-prefix case, each with a
      `parseFilename` and `validate` test). `mvn test -Dtest=CsvFilenameValidatorTests`:
      18/18 passing (14 existing + 4 new, zero regressions).
- [x] 6.6 Added `MODIFIED` delta for the `filename-validation` capability
      (`specs/filename-validation/spec.md`) — copied the "Filename Parses
      Successfully" requirement forward in full per OpenSpec discipline, adding
      `Annual` scenarios.
- [x] 6.7 **Fix from a second independent review pass, confirmed by Keith**: the
      bare-`YYYY` date alternative added in 6.1 matches regardless of which
      frequency keyword is in the prefix, so without a cross-check,
      `"QuarterlyRI_XXA_2026.csv"` would parse successfully (a `Quarterly` keyword
      paired with a bare-year date) — structurally impossible before this change,
      since the old grammar had only two date shapes, each tied to one keyword.
      `parseFilename()` now rejects (`null`) whenever the detected keyword doesn't
      match the date shape actually matched. Added `ADDED` requirement "Frequency
      Keyword Matches Date Shape" to the `filename-validation` delta and Decision 7
      correction in `design.md`. 5 new tests (3 keyword/date mismatch combinations,
      1 `validate()`-level error check, plus regression coverage for the existing
      matching cases). `mvn test -Dtest=CsvFilenameValidatorTests`: 23/23 passing.

## 7. DynamoDB `FileType` Name Hygiene (Real Incident Fix)

PR #209's `verify` CI job deployed this change to dev and failed for real: the
hand-entered `riAnnualAggregate` registry entry had a trailing space, breaking the
three-tier `reportType` match. Keith: "Trim names coming back from DynamoDB. I will
go back and correct it [the bad data]."

- [x] 7.1 `NewModelHelper.refresh()` now trims `fileTypeName` when rebuilding
      `fileTypeCache` — mutating the `FileType` entity's own field, not just the
      cache key, since `getFileType()` callers (e.g.
      `MetadataBuilder.setReportType()`) read `fileType.getFileTypeName()` directly
      off the returned entity. Scoped to `FileType` specifically, not added to the
      shared `refreshCache()` helper for every repository type (no other cached
      entity's name is echoed back out as a canonical business value the way
      `fileTypeName` is).
- [x] 7.2 Added `NewModelHelperFileTypeNameTrimTests` (2 tests) using the same
      `RepositoryFactory`-mock scaffolding as `AccessControlServiceSourceAttackTests`.
      **Verified as a real regression guard, not just a passing test**: confirmed
      both tests fail without the fix (temporarily reverted it, re-ran, restored it)
      before leaving it in its passing state. `mvn test
      -Dtest=NewModelHelperFileTypeNameTrimTests,NewModelHelperGetFileTypeTests`:
      19/19 passing.
- [x] 7.3 Added `MODIFIED` delta for the `report-type-normalization` capability
      (`specs/report-type-normalization/spec.md`) — copied the "Three-tier registry
      lookup" requirement forward in full, adding the trim behavior and a new
      scenario.
- [ ] 7.4 **Keith, separately, outside this code change**: correct the actual bad
      `riAnnualAggregate` `FileType` entry in dev (strip the trailing space). The
      code fix makes this class of typo non-fatal going forward, but the existing
      bad row should still be cleaned up.

## Workflow follow-up

- Archive this change once the above is verified end-to-end and merged, per the
  repo's standard `openspec archive annual-ri-aggregate-zip-submission` workflow.
- Update `projects-in-progress.md` to reflect the CR's progress/completion.
