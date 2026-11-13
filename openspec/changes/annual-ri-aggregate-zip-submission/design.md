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
  - date: '2026-11-13T17:28:11.691Z'
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
      Finalize Decision 4 as option (b), confirmed by Keith; add Decision 5 for
      the new frequency cross-check; Remove resolved open-question risk, add
      cross-check scope risk; remove stale Non-Goal; remove resolved Open
      Questions section; Remove stale Non-Goal now that NDLP wire format is
      decided; Update Migration Plan step 4 wording and remove resolved Open
      Questions section; Add Goal for the new period-frequency cross-check
  - date: '2026-11-13T16:58:24.506Z'
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
      Sync Context diagram's example filename from NIH to XXA; Correct Decisions
      3/4: meta_ext_submissionperiod is a verbatim pass-through of the period
      parameter, not a separately computed field -- reopen the literal-value
      choice as a genuine open question; Correct risk item wording and sync NIH
      filename reference; re-add Open Questions section; Sync Migration Plan's
      NIH filename reference to XXA; Re-add Open Questions section for the
      reopened period-literal-value decision
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
POST /ads/{destinationId}?reportType=riAnnualAggregate&file=XXA_20251231_20260131_Agg_Z.zip
        │
        ▼
ADSController.postADSFile()
        │
        ▼
MetadataBuilder.setReportType("riAnnualAggregate")
        │   three-tier registry lookup (report-type-normalization) — unchanged
        ▼
MetadataBuilder.setFilename("XXA_20251231_20260131_Agg_Z.zip")
        │   ext != "csv"  →  ZipFilenameComponents.parse()
        ▼
validateZipMetadata()
        ├─ pf.getFiletype() is hardcoded "routineImmunization" → mismatch vs.
        │  "riAnnualAggregate" → REJECTED today
        ├─ entity match (XXA vs. submitted facility) — unaffected
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
- Accept a bare `YYYY` period for annual submissions, and cross-check that every
  submitted `period` value's shape (monthly/quarterly/annual) actually matches the
  established frequency for its report type — not just that it matches *some*
  recognized shape.

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

**3. Correction: `meta_ext_submissionperiod` is not a separately computed field.**
Earlier drafts of this document described `meta_ext_submissionperiod` as something
IZGW computes. Source inspection corrected this: `Metadata.getPeriod()` is annotated
`@JsonProperty("meta_ext_submissionperiod")` directly — whatever string the caller
submits as the `period` form parameter (after `setPeriod()`'s uppercase/trim
normalization) **is** `meta_ext_submissionperiod`, verbatim. There is no intermediate
"derive `YYYY` from the date" step anywhere in the code. This does not change the
ZIP filename's own date-matching logic (Decision 2's divisor is unaffected either
way), but it does change what "confirm `YYYY`" can mean in practice — see the Open
Question below, which this correction reopens.

**4. `PERIOD_PATTERN` gains a bare-`\d{4}` alternative (option (b), confirmed by
Keith).** Caller submits `period=2025` for an annual submission; `PERIOD_PATTERN`'s
trailing suffix group (`Q[1-4]` / month abbreviation / one-to-two `-dd` groups) is
made optional (one added `?`) so a bare 4-digit year validates too. This widens valid
`period` values for every report type, not just annual — mitigated by Decision 5
below, which cross-checks the period's shape against the report type's established
frequency, so a monthly type still can't actually submit a bare year in practice.
`meta_ext_submissionperiod` sent to NDLP for an annual submission is literally
`"2025"`, matching the confirmed format, since `getPeriod()` forwards it verbatim
(Decision 3).

**5. New cross-check: the `period` parameter's shape must match the report type's
established frequency.** This did not exist before this change — `PERIOD_PATTERN`
only validated that `period` matched *some* recognized shape, never that it matched
the *right* shape for the submitted report type. Real precedent from the existing
Postman collection confirms neither monthly nor quarterly types ever send a bare
year: monthly sends `YYYY-MON` (e.g. `"2022-SEP"`), quarterly sends `YYYYQ#` (e.g.
`"2025Q4"`) — each cadence's period value encodes exactly its own granularity, never
coarser or finer. Annual's granularity *is* the year, so `YYYY` alone is the correct
analog, not a shortcut.
- `classifyPeriodFrequency(period)` — classifies an already-structurally-valid period
  string as `Quarterly`, `Monthly`, or `Annual`, or `null` for the legacy numeric
  `YYYY-MM`/`YYYY-MM-DD` form (predates this cross-check; not one of the three named
  shapes, so it is exempted rather than retroactively broken for any existing caller
  that may use it).
- `establishedFrequency(reportType)` — `Annual` when `isAnnual(reportType)`;
  `Quarterly` for `routineImmunization` or any name containing `"quarter"`; `Monthly`
  otherwise (the default, matching every other known type).
- The cross-check in `setPeriod()` is skipped for `genericImmunization` (consistent
  with its existing bypass of the ZIP eligibility check and its "accepts either
  cadence" precedent in the archived `ads-metadata-management` proposal) and for the
  legacy numeric form.
- Alternative considered: skip this cross-check entirely and only add the
  `PERIOD_PATTERN` branch — rejected because Keith specifically asked for it, and
  because without it a monthly or quarterly type could submit a bare year too, which
  the real precedent above shows is wrong for anything but an annual type.

## Risks / Trade-offs

- [Risk] Removing the single-literal ZIP filetype-match check could make it easy to
  forget updating ZIP eligibility if NDLP later adds a third zip-based cadence.
  → Mitigation: the replacement is one small, explicitly-named, explicitly-tested
  static check, not a removal of validation — adding a new zip-eligible type later is
  a one-line change plus a test, not a silent gap.
- [Risk] The new frequency cross-check could reject a legitimate existing caller that
  relies on the exempted legacy numeric `YYYY-MM`/`YYYY-MM-DD` period form for a
  monthly or quarterly type, if that form is ever tightened later.
  → Mitigation: deliberately exempted (returns `null` from `classifyPeriodFrequency`)
  rather than forced into one of the three shapes, so no existing behavior changes
  for callers using that form today.
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
3. Resubmit the NDLP test artifact (`XXA_20251231_20260131_Agg_Z.ZIP`) to dev
   end-to-end.
4. Confirm with NDLP that the delivered metadata — especially the `"2025"`-format
   `meta_ext_submissionperiod` — is accepted as-is.
5. Promote the `FileType` entry and code to production via the standard release
   process.

**Rollback:** pure code revert; no data migration to undo. A `riAnnualAggregate`
`FileType` entry added before a rollback is simply inert (unreachable) once the code
reverts, since nothing else depends on its existence.

