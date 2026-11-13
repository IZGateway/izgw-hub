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

- [ ] 1.1 In `src/main/java/gov/cdc/izgateway/ads/MetadataBuilder.java`, add a small
      private static helper (e.g. `isAnnual(String reportType)`) that returns true
      when the report type contains `"annual"` case-insensitively. Verify by
      compiling and confirming no existing tests break (`mvn test`).
- [ ] 1.2 Replace the ZIP file-type-mismatch check in `validateZipMetadata()` — today
      keyed off `pf.getFiletype().equalsIgnoreCase(meta.getExtEvent())` against the
      hardcoded `ZipFilenameComponents.ROUTINE_IMMUNIZATION` literal — with a check
      against `meta.getExtEvent()` directly: eligible when it equals
      `routineImmunization` (case-insensitive) or `isAnnual(...)` is true. Verify
      with new unit tests in
      `src/test/java/gov/cdc/izgateway/ads/MetadataBuilderTests.java` (or a new
      `MetadataBuilderValidateZipMetadataTests.java` if that class is large) covering:
      `routineImmunization` zip accepted, `riAnnualAggregate` zip accepted,
      `riQuarterlyAggregate` zip rejected with a file-type error,
      `genericImmunization` zip skips the check.
- [ ] 1.3 Replace the hardcoded `divisor = pf.getFiletype().equals(ROUTINE_IMMUNIZATION) ? 3 : 1`
      in `validateZipMetadata()` with `isAnnual(meta.getExtEvent()) ? 12 : 3`. Verify
      with unit tests covering: a `routineImmunization` filename in a different month
      but same quarter as the period matches (existing behavior preserved); a
      `riAnnualAggregate` filename in a different month but same year as the period
      matches; a `riAnnualAggregate` filename in a different year does not match.
- [ ] 1.4 Remove the now-unused `ZipFilenameComponents.ROUTINE_IMMUNIZATION` constant
      and the `filetype`/`parseRoutineImmunizationFilename()` assignment of it, or
      repurpose `filetype` as a doc-only label if still referenced elsewhere in the
      class — confirm with `grep -rn "getFiletype\|ROUTINE_IMMUNIZATION"
      src/main/java` that no other caller depends on the removed constant before
      deleting it. Verify by compiling cleanly and running the existing
      `ZipFilenameComponents`-related tests (if any exist today).
- [ ] 1.5 Update the Javadoc on `ZipFilenameComponents` and `validateZipMetadata()` to
      describe the new name-based eligibility rule and cadence-driven divisor,
      replacing the outdated "ZIP filenames are routine immunization only" framing.
      Verify by reading the rendered Javadoc comment for accuracy against the new
      code.

## 2. End-to-End Verification Against the NDLP Test Artifact

- [ ] 2.1 Add an integration-style test (in
      `src/test/java/gov/cdc/izgateway/ads/ADSControllerTests.java` or equivalent)
      that submits a file named like `NIH_20251231_20260131_Agg_Z.zip` with
      `reportType=riAnnualAggregate` and a `period` of `2025-12-31`, and verifies the
      submission is accepted (no validation errors) and the resulting metadata's
      `data_stream_id` is `ri-annual-aggregate`. Verify: test passes with
      `mvn test -Dtest=ADSControllerTests`.
- [ ] 2.2 Add a negative test confirming a `riQuarterlyAggregate` submission with a
      `.zip` file is still rejected with a file-type-mismatch error (regression guard
      for the eligibility check in 1.2). Verify: test passes.

## 3. Deployment Configuration (tracked, not code)

- [ ] 3.1 Add a `riAnnualAggregate` `FileType` registry entry in the dev environment
      (DynamoDB, via the same mechanism used for `riQuarterlyAggregate` in
      IGDD-2464), gated separately from code deployment. Verify: `GET
      /rest/ads/reportTypes` on dev lists `riAnnualAggregate`.
- [ ] 3.2 Resubmit the actual NDLP test artifact
      (`testing/testdata/NIH_20251231_20260131_Agg_Z.ZIP`) against dev end-to-end once
      both 1.x code and 3.1 configuration are deployed. Verify: submission succeeds
      and the file lands in NDLP's expected `ri-annual-aggregate` folder path.
- [ ] 3.3 Confirm with NDLP that the delivered `meta_ext_submissionperiod` value
      (implemented as the 4-digit `YYYY` year, confirmed per design.md Decision 4) is
      acceptable; file a follow-up task if a different format is required. Verify:
      written confirmation from NDLP, or a corrected format implemented and
      reverified per 2.1.

## Workflow follow-up

- Archive this change once the above is verified end-to-end and merged, per the
  repo's standard `openspec archive annual-ri-aggregate-zip-submission` workflow.
- Update `projects-in-progress.md` to reflect the CR's progress/completion.
