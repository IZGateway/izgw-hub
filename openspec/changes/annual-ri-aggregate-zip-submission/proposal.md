---
schema_version: '1.0'
created:
  date: '2026-11-13T06:56:27.679Z'
  user: boonek
  agent:
    name: claude-code
    version: 1.0.0
  llm:
    name: claude-sonnet-5
    version: '5'
  prompt_uri: >-
    prompt:/claude-code/6489d253-64a8-47f7-9d36-4bd4d59d6e03/~dd641be6-9c76-48ec-9370-9243b651be97
  summary: Initial proposal for annual RI aggregate ZIP submission support
updated:
  - date: '2026-11-13T17:28:49.644Z'
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
      Add What Changes bullet and new capability for the period-frequency
      cross-check; Update Impact section: resolved NDLP format dependency, add
      setPeriod change; Resolve the NDLP wire-format dependency note now that
      YYYY is confirmed and implemented
  - date: '2026-11-13T16:57:08.989Z'
    user: boonek
    agent:
      name: claude-code
      version: 1.0.0
    llm:
      name: claude-sonnet-5
      version: '5'
    prompt_uri: >-
      prompt:/claude-code/6489d253-64a8-47f7-9d36-4bd4d59d6e03/~e19f6c61-cc21-402b-8034-d685cbb7dd17
    summary: Sync test artifact filename example from NIH to XXA after Keith's rename
  - date: '2026-11-13T07:02:26.545Z'
    user: boonek
    agent:
      name: claude-code
      version: 1.0.0
    llm:
      name: claude-sonnet-5
      version: '5'
    prompt_uri: >-
      prompt:/claude-code/6489d253-64a8-47f7-9d36-4bd4d59d6e03/~dd641be6-9c76-48ec-9370-9243b651be97
    summary: >-
      Correct proposal: no computePeriodType() exists in shipped code; collapse
      to ZIP-local helpers; Remove invented compute-period-type capability from
      proposal; Correct Impact section to remove computePeriodType reference
change_request: annual-ri-aggregate-zip-submission
ticket: IGDD-3743
---
# Proposal

## Why

NDLP is now ready to test IIS submission of an Annual Routine Immunization Aggregate
report as a ZIP file (see test artifact `XXA_20251231_20260131_Agg_Z.ZIP`), but IZGW
cannot currently accept it. The ZIP upload path (`ZipFilenameComponents`) hardcodes
its internal file type to the single literal `routineImmunization` — any other
`reportType`, including a new `riAnnualAggregate` type, is rejected by
`MetadataBuilder.validateZipMetadata()`'s file-type-mismatch check. There is no
annual cadence anywhere in the ZIP validation path (it has always assumed quarterly).
Jira: [IGDD-3743](https://izgateway.atlassian.net/browse/IGDD-3743).

Note: there is no general-purpose `computePeriodType()`/period-type concept in the
shipped codebase today. The CSV path (`CsvFilenameValidator`) already derives
cadence directly from the `Monthly`/`Quarterly` keyword in the filename itself, with
no external lookup. Only the ZIP path needs a cadence concept, and only to choose a
date-matching tolerance — so this change adds that logic locally to ZIP handling
rather than inventing shared infrastructure nothing else needs.

## What Changes

- Replace `ZipFilenameComponents`'s hardcoded `filetype = ROUTINE_IMMUNIZATION`
  literal, and the single-value equality check in
  `MetadataBuilder.validateZipMetadata()` that depends on it, with a small
  ZIP-local predicate: a `.zip` upload is eligible when the canonical report type
  (`meta.getExtEvent()`) equals `routineImmunization` or contains `"annual"`
  (case-insensitive). A generic "contains `ri`" rule is rejected because
  `riQuarterlyAggregate` also starts with `"ri"` but is CSV-only and never reaches
  this ZIP-only code path.
- Replace the hardcoded quarterly-only `divisor` (`3`) in
  `validateZipMetadata()`'s date-bucket comparison with a value derived from the same
  annual check: `12` when the report type contains `"annual"`, otherwise `3`
  (preserving today's behavior for `routineImmunization`, the only other ZIP-eligible
  type). A divisor of `12` collapses the month comparison to a year-only match, which
  is sufficient because the ZIP filename's existing period field already carries the
  full date.
- No change to `CsvFilenameValidator`, `computeDataStreamId()`, or
  `report-type-normalization` — the existing camelCase→kebab algorithm and three-tier
  registry lookup already handle `riAnnualAggregate` with no modification.
- `MetadataBuilder.PERIOD_PATTERN` gains a bare-`\d{4}` alternative so an annual
  submission can send `period=2025`, and `setPeriod()` gains a new cross-check:
  the submitted period's shape (monthly/quarterly/annual) must match the submitted
  report type's established frequency. This applies to every report type and file
  format (CSV and ZIP alike), not just the new annual type — it closes a gap where
  `PERIOD_PATTERN` validated that `period` matched *some* recognized shape but never
  that it matched the *right* one for the report type.

Deployment (not code, tracked separately): add a `FileType` registry entry for
`riAnnualAggregate`, the same mechanism already used for `riQuarterlyAggregate`
(IGDD-2464).

## Capabilities

### New Capabilities
- `zip-filename-validation`: `ZipFilenameComponents` parsing and
  `MetadataBuilder.validateZipMetadata()` — entity-ID matching, which report types
  may submit a `.zip` (today hardcoded to `routineImmunization` only, with no spec),
  and a cadence-driven date-bucket tolerance. This change generalizes the
  ZIP-eligibility check from a single hardcoded literal to a name-based rule, and
  derives the date-bucket divisor from the same check instead of a hardcoded
  constant.
- `period-frequency-validation`: `MetadataBuilder.setPeriod()` — structural format
  validation (`YYYYQ#`, `YYYY-MON`, now also bare `YYYY`) plus a cross-check that the
  submitted period's shape matches the report type's established frequency. Applies
  to all report types and both file formats; previously only structural validation
  existed, with no connection to the report type at all.

### Modified Capabilities
(none — `compute-data-stream-id`, `report-type-normalization`, `filename-validation`,
and `ads-folder-path-computation` are unaffected)

## Impact

- **`MetadataBuilder.java`**: new ZIP-eligibility predicate and divisor logic in
  `validateZipMetadata()` (ZIP-scoped); new bare-year `PERIOD_PATTERN` alternative
  and frequency cross-check in `setPeriod()` (applies to all report types/formats).
- **`ZipFilenameComponents.java`**: remove the hardcoded
  `filetype = ROUTINE_IMMUNIZATION` assignment in favor of a name-derived value.
- **No izgw-core, SOAP/WSDL, FIPS/crypto, or PHI-masking impact** — this is confined
  to ADS REST metadata computation; filenames and period values are not PHI, and no
  cryptographic or TLS code is touched.
- **No performance impact on the SOAP routing path** — ADS file upload is a separate,
  low-throughput REST path from HL7v2 message routing.
- **Backward compatible**: existing `routineImmunization` ZIP submissions are
  unaffected (still ZIP-eligible, still `QUARTERLY`, still divisor `3`). No REST or
  WSDL contract changes.
- **NDLP format confirmed**: `meta_ext_submissionperiod` for an annual submission is
  a bare `YYYY` (e.g. `"2025"`), confirmed by Keith and consistent with how monthly
  (`YYYY-MON`) and quarterly (`YYYYQ#`) each encode exactly their own granularity.
  `metadata-requirements.md` has no row for this yet (precedent: `measlesVaccination`'s
  row is marked "format TBD") — still pending NDLP's actual acceptance during dev
  testing (tasks.md 3.3), but the implementation decision itself is no longer open.
- **Deployment**: new `riAnnualAggregate` `FileType` registry entry (configuration
  only, no code), tracked as a task but performed outside this code change.
