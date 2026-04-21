## Context

IGDD-2775: CDC NDLP reported that two ADS test file submissions landed in wrong folders
in the onboarding container, preventing pickup by their mapping table.

The blob storage path for DEX v2 uploads is:
```
{container_base_path}/{dataStreamId}/{extEntity}/{YYYY}/{MM}/{DD}/{filename}
```

The `dataStreamId` segment flows through three layers:

1. **`MetadataBuilder.computeMetaExtEvent(reportType)`** — maps `reportType` to a
   camelCase `extEvent`. Has special cases, e.g. `"farmerFlu"` → `"farmerFluVaccination"`.
2. **`MetadataBuilder.computeDataStreamId(fileTypeName)`** — pure camelCase→kebab
   algorithm, no special cases. `"farmerFlu"` → `"farmer-flu"`.
3. **`MetadataImpl.getDataStreamId()`** — returns the stored `dataStreamId` field if
   non-null; otherwise delegates to `Metadata.getDataStreamId()` which currently uses a
   hardcoded switch over `extEvent` values.

### Root Cause

**`MetadataBuilder.setReportType()` (line 137) calls `computeDataStreamId(reportType)` and
explicitly stores the result in `MetadataImpl.dataStreamId`**, causing two problems:

```java
// Line 137 — BUG
meta.setDataStreamId(computeDataStreamId(reportType));
```

1. `computeDataStreamId()` has no special cases. For `"farmerFlu"` it produces
   `"farmer-flu"`, not `"farmer-flu-vaccination"`.
2. Once the field is stored non-null, `MetadataImpl.getDataStreamId()` returns it
   directly — the `Metadata.getDataStreamId()` switch is never reached.

### Why the Switch Is Also Redundant

Examining `Metadata.getDataStreamId()`, every explicit `case` produces exactly the same
value that `computeDataStreamId()` would compute for the same camelCase input. The
`default:` branch returns `value.toLowerCase()` — all-lowercase with no hyphens —
which is wrong for any new or unrecognized type. The switch therefore adds no value for
known types and gives wrong output for unknown types.

## Goals / Non-Goals

**Goals:**
- Remove `meta.setDataStreamId(computeDataStreamId(reportType))` from `setReportType()`;
  `dataStreamId` should never be pre-computed from the raw `reportType`.
- Replace the hardcoded switch in `Metadata.getDataStreamId()` with a single call to
  `MetadataBuilder.computeDataStreamId(getExtEvent())`, eliminating all special cases.
- Normalize `reportType` to its canonical casing from the file-type registry before
  any computation, so `"covidall"`, `"covidAll"`, and `"covidAllMonthlyVaccination"` all
  produce the same `extEvent` and `dataStreamId`.
- Add end-to-end unit tests asserting `setReportType(x)` → `getDataStreamId()` produces
  the correct kebab value for every known file type name, including case variants.
- Resubmit the two affected test files to NDLP after the fix is deployed.

**Non-Goals:**
- No changes to `ADSUtils.getPath()`, `AzureBlobStorageSender`, or `MetadataImpl`.
- No changes to `NewModelHelper.getFileType()` — it already does case-insensitive lookup.
- No new file type names.

## Decisions

### Decision 1: Remove eager `setDataStreamId()` from `setReportType()`

**Chosen:** Delete line 137 from `MetadataBuilder.setReportType()`:
```java
// Remove this line entirely:
meta.setDataStreamId(computeDataStreamId(reportType));
```

**Rationale:** `dataStreamId` should be computed lazily from `extEvent` (which is already
set correctly by `computeMetaExtEvent()`). Pre-computing it from the raw `reportType`
bypasses the special-case logic and stores a wrong value that shadows everything else.

`MetadataImpl.getDataStreamId()` already falls through to `Metadata.getDataStreamId()`
when the field is null — no other changes to `MetadataImpl` are needed.

### Decision 2: Replace the switch in `Metadata.getDataStreamId()` with the algorithm

**Chosen:** Replace the entire switch body with:
```java
default String getDataStreamId() {
    return MetadataBuilder.computeDataStreamId(getExtEvent());
}
```

**Rationale:** Every explicit case in the switch produces the same value that
`computeDataStreamId()` produces for the same camelCase `extEvent` input. The switch is
pure duplication. Worse, the `default:` branch returns `value.toLowerCase()` (no hyphens),
which is wrong for any new or unrecognised type. The algorithm handles all cases correctly
including future file types without requiring a code change.

**Verified outcomes:**
| `reportType` | `extEvent` (via `computeMetaExtEvent`) | `computeDataStreamId(extEvent)` |
|---|---|---|
| `farmerFlu` | `farmerFluVaccination` | `farmer-flu-vaccination` ✅ |
| `covidAllMonthlyVaccination` | `covidAllMonthlyVaccination` | `covid-all-monthly-vaccination` ✅ |
| `routineImmunization` | `routineImmunization` | `routine-immunization` ✅ |
| `influenzaVaccination` | `influenzaVaccination` | `influenza-vaccination` ✅ |
| `rsvPrevention` | `rsvPrevention` | `rsv-prevention` ✅ |
| `covidBridgeVaccination` | `covidBridgeVaccination` | `covid-bridge-vaccination` ✅ |
| `genericImmunization` | `genericImmunization` | `generic-immunization` ✅ |
| `measlesVaccination` | `measlesVaccination` | `measles-vaccination` ✅ |

### Decision 3: Normalize `reportType` to canonical casing from the registry

**Chosen:** In `MetadataBuilder.setReportType()`, when the registry lookup succeeds,
replace the caller-supplied `reportType` with `fileType.getFileTypeName()` before any
computation:

```java
if (accessControlService != null) {
    IFileType fileType = accessControlService.getFileType(reportType);
    if (fileType == null) {
        log.warn("Report type '{}' is not registered", reportType);
    } else {
        reportType = fileType.getFileTypeName(); // normalize to canonical casing
    }
}
```

**Rationale:** `NewModelHelper.getFileType()` already performs a case-insensitive scan,
so `"covidall"`, `"COVIDALL"`, and `"covidAllMonthlyVaccination"` all resolve to the same
`IFileType`. Using the canonical name from `getFileTypeName()` ensures all downstream
computation (`computeMetaExtEvent`, `computeDataStreamId`) operates on the correctly-cased
camelCase string that the algorithms expect. Without this, a submitter who passes
`"covidall"` would get `extEvent = "covidall"` and `dataStreamId = "covidall"` (wrong).

**Note:** When no service is injected (or the type is unrecognised), the raw submitted
value is used as before — computation proceeds with a warning.

### Decision 4: Unit tests covering all known file types end-to-end

**Chosen:** Add tests in `MetadataBuilderTests` that call `setReportType()` and assert
the resulting `getDataStreamId()` for every known file type, with `farmerFlu` as the
primary regression case.

**Rationale:** The bug existed because the only tests for `computeDataStreamId()` tested
the algorithm in isolation. End-to-end tests through `setReportType()` would have caught
the short-circuit immediately.

### Decision 4: Manual resubmission to NDLP onboarding container

No automated redelivery mechanism exists. The two affected test files are resubmitted
manually to CDC contact (Juan Alvarado, wok1@cdc.gov) after deployment.

## Risks / Trade-offs

- **Risk:** `computeDataStreamId()` is currently package-private (`static`). `Metadata`
  is in the same package so the call compiles, but it creates a dependency from an
  interface to a builder class. This is acceptable short-term; the algorithm can be
  moved to a utility class if it becomes a concern.

- **Risk:** Removing the switch removes an explicit allowlist of valid `dataStreamId`
  values. Any typo in a `reportType` will now silently produce a wrong-but-plausible
  kebab string instead of a validation error. The existing warn-on-unregistered-type log
  in `setReportType()` provides partial mitigation.
  → **Mitigation:** The file-type registry validation (already present) is the correct
  place to enforce valid types, not `getDataStreamId()`.

- **Risk:** `MetadataImpl.getDataStreamId()` returns the stored field first. If any
  existing caller sets `dataStreamId` explicitly to a legacy or incorrect value, that
  value will still win. This is intentional — deserialized data is preserved.

## Migration Plan

1. Delete `meta.setDataStreamId(computeDataStreamId(reportType))` from `setReportType()`.
2. Replace switch body in `Metadata.getDataStreamId()` with `return MetadataBuilder.computeDataStreamId(getExtEvent())`.
3. Add end-to-end unit tests; confirm all green.
4. Deploy to the onboarding environment.
5. Resubmit `farmerFlu` test file to `ext-immunization-izgw/farmer-flu-vaccination/`.
6. Resubmit `covidAllMonthly` test file to `ext-immunization-izgw/covid-all-monthly-vaccination/`.
7. Confirm with Juan Alvarado (wok1@cdc.gov).

**Rollback:** Revert two files. No schema or data migration involved.

### Decision 5: Add `GET /rest/ads/reportTypes` discovery endpoint

**Moved from:** `ads-metadata-management` CR (implemented that CR's core work; this endpoint was
deferred and `ReportTypeInfo` DTO was subsequently removed as unnecessary).

**Chosen:** Add a simple endpoint to `ADSController` that returns the currently registered
report type names as a plain list of strings:

```java
@GetMapping("/ads/reportTypes")
@Operation(summary = "List valid report types")
public List<String> getAvailableReportTypes() {
    return config.getAccessControls().getEventTypes();
}
```

Update the existing `@Schema` description on the `reportType` parameter (which already
references this URL) so callers can discover valid values at runtime.

**Rationale:** The `reportType` `@Schema` description already says
*"See GET /rest/ads/reportTypes for the current list of valid values."* but the endpoint
does not exist — API clients following that hint get a 404. A one-line implementation
using the existing `getEventTypes()` method closes the gap with no new DTO.

`ReportTypeInfo` (originally designed with `fileTypeName`, `dataStreamId`, and `periodType`
fields) was removed from scope — the plain string list is sufficient for the discovery use case.

## Open Questions

1. Was the `covidAllMonthlyVaccination` submission made with `reportType = "covidAllMonthly"`
   (shorter alias) or the full name? With this fix the answer doesn't change the
   implementation, but it determines whether resubmission of that file is necessary.

