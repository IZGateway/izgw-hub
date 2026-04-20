## Context

IGDD-2775: CDC NDLP reported that two ADS test file submissions landed in wrong folders
in the onboarding container, preventing pickup by their mapping table.

The blob storage path for DEX v2 uploads is:
```
{container_base_path}/{dataStreamId}/{extEntity}/{YYYY}/{MM}/{DD}/{filename}
```

The `dataStreamId` segment is produced by a **two-step chain**:

1. **`MetadataBuilder.computeMetaExtEvent(reportType)`** — maps `reportType` to a
   camelCase `extEvent`. Special case: `"farmerFlu"` → `"farmerFluVaccination"`.
2. **`MetadataImpl.getDataStreamId()`** — returns the stored `dataStreamId` field if
   non-null; otherwise falls through to `Metadata.getDataStreamId()` which maps
   `extEvent` via a `switch` to the correct kebab-case string.

### Root Cause

**`MetadataBuilder.setReportType()` (line 137) calls `computeDataStreamId(reportType)` and
explicitly stores the result**, bypassing the switch entirely:

```java
// Line 137 — BUG: uses reportType, not metaExtEvent
meta.setDataStreamId(computeDataStreamId(reportType));
```

`computeDataStreamId()` is a pure camelCase→kebab converter with no special cases. For
`"farmerFlu"` it produces `"farmer-flu"`, not `"farmer-flu-vaccination"`. Because
`MetadataImpl.getDataStreamId()` returns the stored field first (non-null), the switch
mapping for `"farmerFluVaccination"` → `"farmer-flu-vaccination"` is never reached.

For `"covidAllMonthlyVaccination"`: `computeDataStreamId("covidAllMonthlyVaccination")`
produces the correct `"covid-all-monthly-vaccination"` — but only if the `reportType`
passed is the full name. If a shorter alias was submitted, the wrong path would result
for that too.

## Goals / Non-Goals

**Goals:**
- Fix `MetadataBuilder.setReportType()` to compute `dataStreamId` from `metaExtEvent`
  (post-special-case) rather than the raw `reportType`.
- Add unit tests asserting the correct `dataStreamId` for all known file type names,
  including `farmerFlu` and `covidAllMonthlyVaccination`.
- Resubmit the two affected test files to NDLP with correct paths after the fix is deployed.

**Non-Goals:**
- No changes to `ADSUtils.getPath()` or `AzureBlobStorageSender`.
- No changes to the switch table in `Metadata.getDataStreamId()`.
- No new file type names beyond those already registered.

## Decisions

### Decision 1: Compute `dataStreamId` from `metaExtEvent`, not `reportType`

**Chosen:** Change line 137 of `MetadataBuilder.setReportType()` from:
```java
meta.setDataStreamId(computeDataStreamId(reportType));
```
to:
```java
meta.setDataStreamId(computeDataStreamId(metaExtEvent));
```

**Rationale:** `metaExtEvent` is already the post-special-case value (e.g.,
`"farmerFluVaccination"` for `farmerFlu`). Running it through `computeDataStreamId()`
then yields the correct kebab path segment (`"farmer-flu-vaccination"`) without needing
any additional special cases.

**Verified outcomes after fix:**
| `reportType` | `metaExtEvent` | `computeDataStreamId(metaExtEvent)` |
|---|---|---|
| `farmerFlu` | `farmerFluVaccination` | `farmer-flu-vaccination` ✅ |
| `covidAllMonthlyVaccination` | `covidAllMonthlyVaccination` | `covid-all-monthly-vaccination` ✅ |
| `routineImmunization` | `routineImmunization` | `routine-immunization` ✅ |
| `influenzaVaccination` | `influenzaVaccination` | `influenza-vaccination` ✅ |

**Alternative considered:** Remove the explicit `setDataStreamId()` call and rely solely
on the switch in `Metadata.getDataStreamId()` — rejected because it would break
serialization paths where `dataStreamId` is read from the stored field directly.

### Decision 2: Unit tests covering all known file types end-to-end

**Chosen:** Add tests in `MetadataBuilderTests` that call `setReportType()` and assert
the resulting `getDataStreamId()` value for every file type in the switch table.

**Rationale:** The bug existed because `computeDataStreamId(reportType)` and
`computeDataStreamId(metaExtEvent)` give different results only for `farmerFlu` (the
one special-cased name). A test for each file type would have caught this immediately.

### Decision 3: Manual resubmission to NDLP onboarding container

No automated redelivery mechanism exists. The two affected test files are resubmitted
manually to CDC contact (Juan Alvarado, wok1@cdc.gov) after deployment.

## Risks / Trade-offs

- **Risk:** `computeDataStreamId(metaExtEvent)` must produce the same value as the
  switch table for all non-special-case types. If any discrepancy exists, the switch
  table wins (since it was the intended mapping). Tests will catch this.
  → **Mitigation:** New tests compare against the switch table values directly.

- **Risk:** The `ads-metadata-management` change (27/46 tasks) introduced the `switch`
  table. If that change is not yet deployed, the switch may not exist.
  → **Mitigation:** This fix targets the same `setReportType()` method and does not
  depend on the switch — `computeDataStreamId(metaExtEvent)` is self-sufficient.

## Migration Plan

1. Apply one-line fix in `MetadataBuilder.setReportType()` (line 137).
2. Add unit tests; confirm all green.
3. Deploy to the onboarding environment.
4. Resubmit `farmerFlu` test file to `ext-immunization-izgw/farmer-flu-vaccination/`.
5. Resubmit `covidAllMonthly` test file to `ext-immunization-izgw/covid-all-monthly-vaccination/`.
6. Confirm with Juan Alvarado (wok1@cdc.gov).

**Rollback:** Revert the one-line change. No schema or data migration involved.

## Open Questions

1. Was the `covidAllMonthlyVaccination` submission made with `reportType = "covidAllMonthly"` 
   (shorter alias) or the full name? If an alias, the fix above still resolves it since
   `computeMetaExtEvent("covidAllMonthly")` returns `"covidAllMonthly"` and
   `computeDataStreamId("covidAllMonthly")` = `"covid-all-monthly"` — which would still
   be wrong. Confirm the exact `reportType` string used in the failing submission.

