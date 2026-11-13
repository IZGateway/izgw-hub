---
schema_version: '1.0'
created:
  date: '2026-11-13T06:59:24.818Z'
  user: boonek
  agent:
    name: claude-code
    version: 1.0.0
  llm:
    name: claude-sonnet-5
    version: '5'
  prompt_uri: >-
    prompt:/claude-code/6489d253-64a8-47f7-9d36-4bd4d59d6e03/~dd641be6-9c76-48ec-9370-9243b651be97
  summary: Design doc for annual RI aggregate ZIP submission support
updated:
  - date: '2026-11-13T15:47:03.082Z'
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
      Keith confirmed YYYY as the annual meta_ext_submissionperiod format;
      promote from open question to decision; Remove resolved Open Question now
      that YYYY format is confirmed, not just assumed; Remove now-resolved Open
      Questions section per template
  - date: '2026-11-13T07:03:37.286Z'
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
      Correct design.md: no computePeriodType exists; collapse to one ZIP-local
      annual check
change_request: annual-ri-aggregate-zip-submission
ticket: IGDD-3743
---
# Design

## Context

See `proposal.md` - Why. One existing, undocumented behavior in `MetadataBuilder`/
`ZipFilenameComponents` is the current blocker. Note there is **no**
`computePeriodType()` or general period-type concept in the shipped codebase — the
only cadence-aware logic is the CSV path's own `Monthly`/`Quarterly` filename
keyword (unaffected by this change) and the ZIP path's hardcoded quarterly divisor:

```
POST /ads/{destinationId}?reportType=riAnnualAggregate&file=NIH_20251231_20260131_Agg_Z.zip
        │
        ▼
ADSController.postADSFile()
        │
        ▼
MetadataBuilder.setReportType("riAnnualAggregate")
        │   three-tier registry lookup (report-type-normalization) — unchanged
        ▼
MetadataBuilder.setFilename("NIH_20251231_20260131_Agg_Z.zip")
        │   ext != "csv"  →  ZipFilenameComponents.parse()
        ▼
validateZipMetadata()
        ├─ pf.getFiletype() is hardcoded "routineImmunization" → mismatch vs.
        │  "riAnnualAggregate" → REJECTED today
        ├─ entity match (NIH vs. submitted facility) — unaffected
        └─ divisor hardcoded to 3 (quarterly) whenever any zip is parsed — never
           reached today because the step above already rejects the upload
```

`report-type-normalization`, `compute-data-stream-id`, and CSV's `filename-validation`
are all unaffected and require no change — see proposal.md Impact.

## Goals / Non-Goals

**Goals:**
- Let a registered report type whose canonical name contains `"annual"` submit a
  `.zip` file and have it pass entity and date validation with year-level tolerance.
- Replace the hardcoded `routineImmunization` literal with a small, explicit,
  ZIP-local check — without inventing a general-purpose period-type mechanism that
  nothing else in the codebase needs (CSV already gets cadence from its own filename
  keyword).

**Non-Goals:**
- Changing the CSV filename grammar or `CsvFilenameValidator` in any way.
- Adding a new FileType entity field, DynamoDB schema change, or
  `izg-configuration-console` UI change (would reopen the computation-only decision
  from `ads-metadata-management`; not justified for one new name pattern).
- Building a reusable, general-purpose period-type/cadence abstraction. Only the ZIP
  path needs a cadence concept (to pick a date-matching tolerance), and it only ever
  needs to distinguish two cases: the existing quarterly default and the new annual
  case.
- Fixing the unrelated OpenSpec `filename-validation` spec/code drift noted in the
  proposal (`.zip` documented as CSV-grammar-eligible but not actually dispatched
  there) — out of scope for this change, tracked separately.
- Defining NDLP's exact wire format for an annual `meta_ext_submissionperiod` value
  (open question below).

## Decisions

**1. One small ZIP-local predicate, not a new FileType field, and not a new
general-purpose period-type function.**
Add a single static check — equivalent to
`"routineImmunization".equalsIgnoreCase(reportType) || containsIgnoreCase(reportType, "annual")`
— used in `validateZipMetadata()` both to decide ZIP eligibility and to pick the
date-bucket divisor. Alternatives considered: (a) a new `zipEligible` boolean field
on the `FileType` entity — rejected, reopens the computation-only cost tradeoff
(DynamoDB schema change + UI change) that `ads-metadata-management` deliberately
avoided, for one boolean; (b) a general `computePeriodType()`-style function
(`MONTHLY`/`QUARTERLY`/`BOTH`/`ANNUAL`) modeled on the archived
`ads-metadata-management` proposal's "Version 3.0" plan — rejected once source
inspection confirmed that function was never actually implemented, and nothing else
in the codebase needs it: `CsvFilenameValidator` already derives cadence from its own
`Monthly`/`Quarterly` filename keyword with no external lookup, so building shared
cadence infrastructure for a single ZIP-only consumer is unjustified scope; (c)
dispatch `.zip` vs `.csv` by which parser's regex structurally matches the filename,
rather than by extension — rejected as a much larger, riskier rework of
`MetadataBuilder.setFilename()`'s dispatch, which today is a simple extension check.

Because the check is against the literal `routineImmunization`/`"annual"` substring
rather than a `"ri"` prefix, there is no collision with `riQuarterlyAggregate` (which
never reaches this ZIP-only code path in the first place, since it is CSV-only) —
no ordering dependency to manage, unlike a prefix-based rule would require.

**2. ZIP date-bucket divisor driven by the same annual check: `3` (default,
quarterly-equivalent) or `12` (annual).**
Divisor `12` makes `month / 12` always `0` regardless of month, so the existing
`checkDate()` month-bucket comparison collapses to year-only with no new branch.
Alternative considered: a dedicated `yearOnly` boolean bypassing the divisor math
entirely for annual — rejected; the existing divisor arithmetic already produces the
correct result for `12`, so a bypass flag would just be redundant code.

**3. No change to the `period` request parameter format (`PERIOD_PATTERN`).**
The existing `YYYY-MM-DD` option already accepts an annual submission's period value
(e.g. `2025-12-31`), so intake needs no new pattern. Alternative considered: add a
bare `YYYY` period format — rejected as unnecessary; `YYYY-MM-DD` already covers it
and preserves the period-end-date precision the ZIP filename's `Period` segment
provides.

**4. `meta_ext_submissionperiod` for an annual submission is a 4-digit `YYYY` year
(confirmed by Keith).** No row exists for this in `metadata-requirements.md` today
(precedent: `measlesVaccination`'s row is marked "format TBD"). `YYYY` is consistent
with the year-containing formats used by every other cadence in that table. NDLP
validation of this value happens during dev testing (Migration Plan step 4) as a
verification step, not as an open design question — if NDLP requires a different
format, it is an isolated constant change, not a rework of this change's approach.

## Risks / Trade-offs

- [Risk] Removing the single-literal ZIP filetype-match check could make it easy to
  forget updating ZIP eligibility if NDLP later adds a third zip-based cadence.
  → Mitigation: the replacement is one small, explicitly-named, explicitly-tested
  static check, not a removal of validation — adding a new zip-eligible type later is
  a one-line change plus a test, not a silent gap.
- [Risk] NDLP's actual acceptance of the `YYYY`-format `meta_ext_submissionperiod`
  value is not yet verified end-to-end against the live DEX endpoint.
  → Mitigation: this is an isolated constant/format choice, not a structural
  dependency for either code change, so a later correction from NDLP does not require
  revisiting the specs, the approach, or the task breakdown — see Migration Plan
  step 4.
- [Risk] Uploads for `riAnnualAggregate` will fail at the `setReportType()` registry
  lookup until the `FileType` entry exists in the target environment, independent of
  whether the code changes are deployed.
  → Mitigation: tracked as an explicit, separately-gated deployment task in
  `tasks.md`, not bundled into the "code complete" milestone.

## Migration Plan

No DB schema or data migration (computation-only, consistent with IGDD-2464's
precedent). Deployment sequence:
1. Merge and deploy the code changes to dev.
2. Add the `riAnnualAggregate` `FileType` registry entry in dev (configuration only).
3. Resubmit the NDLP test artifact (`NIH_20251231_20260131_Agg_Z.ZIP`-equivalent) to
   dev end-to-end.
4. Confirm with NDLP that the delivered metadata — especially
   `meta_ext_submissionperiod` — is acceptable; adjust the default format if not.
5. Promote the `FileType` entry and code to production via the standard release
   process.

**Rollback:** pure code revert; no data migration to undo. A `riAnnualAggregate`
`FileType` entry added before a rollback is simply inert (unreachable) once the code
reverts, since nothing else depends on its existence.

