## Context

IGDD-2775: CDC NDLP reported that two ADS test file submissions landed in wrong folders
in the onboarding container, preventing pickup by their mapping table.

The blob storage path for DEX v2 uploads is:
```
{container_base_path}/{dataStreamId}/{extEntity}/{YYYY}/{MM}/{DD}/{filename}
```

The `dataStreamId` segment is produced by a **two-step chain** in the current codebase:

1. **`MetadataBuilder.computeMetaExtEvent(fileTypeName)`** — maps `fileTypeName` to a
   camelCase `extEvent` string. Special case: `"farmerFlu"` → `"farmerFluVaccination"`.
2. **`Metadata.getDataStreamId()`** (default interface method) — maps `extEvent` values
   via a `switch` to kebab-case `dataStreamId` strings:
   - `"farmerFluVaccination"` → `"farmer-flu-vaccination"` ✅
   - `"covidAllMonthlyVaccination"` → `"covid-all-monthly-vaccination"` ✅

A separate `computeDataStreamId(fileTypeName)` method exists in `MetadataBuilder` and
produces a **raw camelCase→kebab conversion** (e.g., `"farmerFlu"` → `"farmer-flu"`) but
this method is **not used in the upload path** — it is only used as a utility for
initial value seeding. The upload path always goes through `Metadata.getDataStreamId()`.

**Conclusion from investigation:** The upload path code is correct today. The wrong
submissions to NDLP were made when the `ads-metadata-management` changes (which added the
`switch`-based mapping in `Metadata.getDataStreamId()`) had not yet been deployed, or
files were manually submitted with uncorrected paths.

## Goals / Non-Goals

**Goals:**
- Verify the existing `Metadata.getDataStreamId()` mapping for `farmerFlu` and
  `covidAllMonthly` is correct and covered by unit tests.
- Add or update unit tests to assert the correct `dataStreamId` for all file type names.
- Resubmit the two affected test files to the NDLP onboarding container with correct paths.
- Confirm with CDC/Juan Alvarado that the resubmission resolves the issue.

**Non-Goals:**
- No changes to `computeDataStreamId()` — it is not used in the upload path.
- No changes to `ADSUtils.getPath()` or blob storage URL construction.
- No new file type names or mappings beyond the two affected ones.

## Decisions

### Decision 1: No code change to path computation logic

**Chosen:** No code change needed to `MetadataBuilder`, `Metadata`, or `ADSUtils`.

**Rationale:** Investigation confirmed that `Metadata.getDataStreamId()` already maps
`"farmerFluVaccination"` → `"farmer-flu-vaccination"` and `"covidAllMonthlyVaccination"`
→ `"covid-all-monthly-vaccination"` correctly. The wrong submissions were a historical
artifact of pre-deployment state.

**Alternative considered:** Add an override/special case in `computeDataStreamId()` —
rejected because this method is not in the upload path and adding dead code would be
misleading.

### Decision 2: Add unit tests as the primary deliverable

**Chosen:** Add explicit unit test assertions in `MetadataBuilderTests` verifying the
full `fileTypeName` → `dataStreamId` chain for all known file types, including
`farmerFlu` and `covidAllMonthlyVaccination`.

**Rationale:** The bug was caused by lack of end-to-end test coverage for the path
computation chain. Tests prevent regression and document the expected behavior.

### Decision 3: Manual resubmission to NDLP onboarding container

**Chosen:** Resubmit the two test files manually to the corrected folder paths in the
NDLP onboarding container once the deployed code is confirmed correct.

**Rationale:** The files are test/onboarding artifacts, not production data. No automated
re-delivery mechanism exists. CDC contact (Juan Alvarado) will verify receipt.

## Risks / Trade-offs

- **Risk:** The `ads-metadata-management` change (27/46 tasks) may not yet be deployed,
  meaning `Metadata.getDataStreamId()` switch may not be in the deployed codebase.
  → **Mitigation:** Verify the fix is in the deployed version before resubmitting.
  If not deployed, this change depends on `ads-metadata-management` completing first.

- **Risk:** Other file types may have similar path mismatches not yet reported.
  → **Mitigation:** The new unit tests cover all known file types in the `switch` table,
  providing a regression safety net going forward.

## Migration Plan

1. Confirm `ads-metadata-management` changes (specifically `Metadata.getDataStreamId()`
   switch) are deployed to the onboarding environment.
2. Add unit tests asserting correct `dataStreamId` for all file type names.
3. Run tests; confirm green.
4. Resubmit `farmerFlu` test file to `ext-immunization-izgw/farmer-flu-vaccination/`.
5. Resubmit `covidAllMonthly` test file to `ext-immunization-izgw/covid-all-monthly-vaccination/`.
6. Reply to Juan Alvarado (wok1@cdc.gov) confirming resubmission; request verification.

**Rollback:** No code change means no rollback needed. Resubmitted files can be deleted
from the container if NDLP reports further issues.

## Open Questions

1. Is the `ads-metadata-management` change fully deployed to the NDLP onboarding
   environment, or is this fix blocked on that deployment?
2. Should the resubmission be automated (triggered by a CI job) or remain manual?
   Current volume is low (2 files); manual is appropriate for now.
