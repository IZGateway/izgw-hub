# Spec: `meta_ext_event` and `meta_ext_event_type`

**Component:** `MetadataBuilder`  
**Implemented in:** `gov.cdc.izgateway.ads.MetadataBuilder` — `computeMetaExtEvent()` (private static) and `setReportType()`

---

## Purpose

Define how the two ADS metadata event fields are derived from the report type:

| ADS field | Derived by | Typical value |
|---|---|---|
| `meta_ext_event` | `computeMetaExtEvent(reportType)` | Same as `reportType`, with one special case |
| `meta_ext_event_type` | `setReportType()` stores the (normalized) `reportType` directly | Same as the canonical `reportType` |

These values appear in the ADS metadata envelope sent to CDC DEX/NDLP endpoints and
must match the values previously hardcoded in the legacy `Metadata.getMetaExtEvent()`
switch statement. `meta_ext_event` is also the input to
[`computeDataStreamId()`](../compute-data-stream-id/spec.md), so its casing determines
the `data_stream_id` folder path.

When a file-type registry is available, `setReportType()` first resolves the submitted
value to its canonical registry name (see
[`report-type-normalization`](../report-type-normalization/spec.md)) and both fields are
derived from that canonical name. The rules below describe the computation applied to
whatever value reaches it — the canonical name on the registry path, or the raw submitted
value on the no-registry path.

---

## Rules for `computeMetaExtEvent(fileTypeName)`

| Priority | Condition | Return value |
|---|---|---|
| 1 | `fileTypeName` is `null` or empty (`""`) | `"genericImmunization"` |
| 2 | `fileTypeName` equals `"farmerFlu"` (case-insensitive) | `"farmerFluVaccination"` |
| 3 | _(none of the above)_ | `fileTypeName` unchanged |

### Special Case: `farmerFlu` → `farmerFluVaccination`

The legacy hardcoded mapping sent `"farmerFluVaccination"` as `meta_ext_event` even when
the file type was named `"farmerFlu"`. NDLP's mapping table expects
`farmer-flu-vaccination` as the folder, so this special case **must be preserved**. On the
registry path the alias `farmerFlu` is already normalized to `farmerFluVaccination` before
this function runs; the special case still guards the no-registry path.

### Pseudocode

```
function computeMetaExtEvent(fileTypeName):
    if fileTypeName is null or empty:
        return "genericImmunization"

    if equalsIgnoreCase(fileTypeName, "farmerFlu"):
        return "farmerFluVaccination"

    return fileTypeName
```

> `setReportType()` rejects blank input with the error
> `"Report Type must be present and not empty"` before calling this function, so the
> null/empty branch is reached only by direct callers.

---

## Requirements

### Requirement: computeMetaExtEvent null or empty returns genericImmunization
`computeMetaExtEvent` SHALL return `"genericImmunization"` for a `null` or empty `fileTypeName`.

#### Scenario: Null input returns genericImmunization
- **GIVEN** a `null` fileTypeName
- **WHEN** `computeMetaExtEvent` is called
- **THEN** the return value is `"genericImmunization"`

#### Scenario: Empty string returns genericImmunization
- **GIVEN** a fileTypeName of `""`
- **WHEN** `computeMetaExtEvent` is called
- **THEN** the return value is `"genericImmunization"`

### Requirement: computeMetaExtEvent farmerFlu special case
`computeMetaExtEvent` SHALL return `"farmerFluVaccination"` for any case-insensitive variant of `"farmerFlu"`.

#### Scenario: farmerFlu returns farmerFluVaccination
- **GIVEN** a fileTypeName of `"farmerFlu"`
- **WHEN** `computeMetaExtEvent` is called
- **THEN** the return value is `"farmerFluVaccination"`

#### Scenario: Case variants of farmerFlu return farmerFluVaccination
- **GIVEN** a fileTypeName of `"FarmerFlu"` or `"FARMERFLU"`
- **WHEN** `computeMetaExtEvent` is called
- **THEN** the return value is `"farmerFluVaccination"`

#### Scenario: farmerFluVaccination returns itself
- **GIVEN** a fileTypeName of `"farmerFluVaccination"` (already the full name; not equal to `"farmerFlu"`)
- **WHEN** `computeMetaExtEvent` is called
- **THEN** the return value is `"farmerFluVaccination"`

### Requirement: computeMetaExtEvent identity for all other types
`computeMetaExtEvent` SHALL return `fileTypeName` unchanged for every non-null, non-empty value that is not a case-insensitive variant of `"farmerFlu"`.

#### Scenario: Production file types return unchanged
- **GIVEN** a fileTypeName of `routineImmunization`, `influenzaVaccination`, `covidAllMonthlyVaccination`, `covidBridgeVaccination`, `rsvPrevention`, `measlesVaccination`, `genericImmunization`, or `riQuarterlyAggregate`
- **WHEN** `computeMetaExtEvent` is called
- **THEN** the return value equals the input

### Requirement: meta_ext_event_type is the report type as received by setReportType
`setReportType(reportType)` SHALL store `reportType` — after registry normalization when a registry is available — as `meta_ext_event_type` without applying the `farmerFlu` special case or any other transformation.

#### Scenario: Without a registry the raw value is stored
- **GIVEN** a `MetadataBuilder` constructed with no `IAccessControlService`
- **WHEN** `setReportType("farmerFlu")` is called
- **THEN** `meta_ext_event_type` is `"farmerFlu"`
- **AND** `meta_ext_event` is `"farmerFluVaccination"`

#### Scenario: With a registry the canonical value is stored
- **GIVEN** a `MetadataBuilder` constructed with a registry service whose lookup of `farmerFlu` returns the `farmerFluVaccination` entry
- **WHEN** `setReportType("farmerFlu")` is called
- **THEN** `meta_ext_event_type` is `"farmerFluVaccination"`
- **AND** `meta_ext_event` is `"farmerFluVaccination"`

#### Scenario: Non-special types store identity on either path
- **GIVEN** a `MetadataBuilder` with or without a registry service
- **WHEN** `setReportType("routineImmunization")` is called
- **THEN** `meta_ext_event_type` is `"routineImmunization"`
- **AND** `meta_ext_event` is `"routineImmunization"`

### Requirement: genericImmunization forces DEX version 2
WHEN the computed `meta_ext_event` is `"genericImmunization"`, `setReportType()` SHALL set `meta_ext_sourceversion` to `Metadata.DEX_VERSION2`.

#### Scenario: Generic report type forces V2
- **GIVEN** a `MetadataBuilder`
- **WHEN** `setReportType("genericImmunization")` is called
- **THEN** `meta_ext_sourceversion` is `Metadata.DEX_VERSION2`

#### Scenario: Non-generic report type leaves version untouched
- **GIVEN** a `MetadataBuilder` whose `meta_ext_sourceversion` has not been set
- **WHEN** `setReportType("influenzaVaccination")` is called
- **THEN** `setReportType()` does not change `meta_ext_sourceversion`

---

## How These Fields Map to ADS Metadata

```
submitted reportType = "farmerFlu"
         │
         ├── registry path (IAccessControlService present):
         │       normalize → "farmerFluVaccination"
         │       ├── setExtEventType()        → meta_ext_event_type = "farmerFluVaccination"
         │       └── computeMetaExtEvent()    → meta_ext_event      = "farmerFluVaccination"
         │
         └── no-registry path:
                 ├── setExtEventType()        → meta_ext_event_type = "farmerFlu"
                 └── computeMetaExtEvent()    → meta_ext_event      = "farmerFluVaccination"

meta_ext_event ── computeDataStreamId() ──▶ data_stream_id = "farmer-flu-vaccination"
```

`meta_ext_event` is the CDC-facing event name used for routing and folder placement.
`meta_ext_event_type` is the classification name used for audit and reporting.

---

## Implementation Location

```
izgw-hub/src/main/java/gov/cdc/izgateway/ads/MetadataBuilder.java
  ├── public MetadataBuilder setReportType(String reportType)
  └── private static String computeMetaExtEvent(String fileTypeName)

izgw-hub/src/test/java/gov/cdc/izgateway/ads/MetadataBuilderComputationTests.java
  └── setReportType_setsCorrectExtEvent, setReportType_extEventTypeIsAlwaysRawInput,
      setReportType_genericImmunization_forcesVersion2
izgw-hub/src/test/java/gov/cdc/izgateway/ads/MetadataBuilderSetReportTypeTests.java
  └── registry-path and case-variant scenarios
```

---

## Change History

| Date | Change |
|---|---|
| 2026-03-27 | Initial spec (`FileTypeMetadataUtil.computeMetaExtEvent` / `computeMetaExtEventType`) |
| 2026-09-21 | IGDD-2775: aligned to current code — implementation is `MetadataBuilder`; null/empty returns `genericImmunization` (not `null`); `computeMetaExtEventType()` does not exist, `meta_ext_event_type` is the normalized report type; documented registry vs no-registry paths |
