# Spec: ADS Folder Path Computation

## Purpose

Define how the DEX v2 blob storage folder path segment (`data_stream_id`) is computed
from a submitted `reportType`, ensuring the correct path is produced for all known file
types including those with special-case names like `farmerFlu`.

## Background

The DEX v2 blob storage path is:
```
{container_base_path}/{data_stream_id}/{ext_entity}/{YYYY}/{MM}/{DD}/{filename}
```

`data_stream_id` must be a correctly-formed kebab-case string derived from the canonical
camelCase file type name (e.g., `farmerFluVaccination` → `farmer-flu-vaccination`).

## Requirements

### Canonical name normalization

- WHEN `setReportType(reportType)` is called AND a file-type registry is available,
  the system SHALL resolve `reportType` to its canonical name via a case-insensitive
  registry lookup before any computation.
- WHEN the registry lookup succeeds, the canonical name from `IFileType.getFileTypeName()`
  SHALL be used for all downstream field computation (`meta_ext_event`, `data_stream_id`).
- WHEN the registry lookup fails (type not registered), the system SHALL log a warning
  and continue using the submitted value as-is.
- WHEN no registry is available, the system SHALL compute fields directly from the
  submitted value without any lookup.

### `data_stream_id` computation

- `data_stream_id` SHALL be derived from `meta_ext_event` (not from `reportType`) using
  the camelCase→kebab algorithm in `MetadataBuilder.computeDataStreamId()`.
- `data_stream_id` SHALL NOT be pre-computed and stored during `setReportType()`. It
  SHALL be computed lazily by `Metadata.getDataStreamId()` from the stored `meta_ext_event`.
- `Metadata.getDataStreamId()` SHALL delegate entirely to
  `MetadataBuilder.computeDataStreamId(getExtEvent())` with no hardcoded switch cases.

### Algorithm correctness

- `computeDataStreamId(input)` SHALL convert camelCase/PascalCase to kebab-case by
  inserting a hyphen before each uppercase letter that follows a lowercase letter or digit,
  or that begins a transition from an acronym to a word.
- `computeDataStreamId(null)` and `computeDataStreamId("")` SHALL return
  `"generic-immunization"`.

### Expected mappings (normative)

| Submitted `reportType` (examples) | Canonical name | `meta_ext_event` | `data_stream_id` |
|---|---|---|---|
| `farmerFlu`, `farmerflu`, `FARMERFLU` | `farmerFlu` | `farmerFluVaccination` | `farmer-flu-vaccination` |
| `covidAllMonthlyVaccination`, `covidall` | `covidAllMonthlyVaccination` | `covidAllMonthlyVaccination` | `covid-all-monthly-vaccination` |
| `routineImmunization` | `routineImmunization` | `routineImmunization` | `routine-immunization` |
| `influenzaVaccination` | `influenzaVaccination` | `influenzaVaccination` | `influenza-vaccination` |
| `rsvPrevention` | `rsvPrevention` | `rsvPrevention` | `rsv-prevention` |
| `covidBridgeVaccination` | `covidBridgeVaccination` | `covidBridgeVaccination` | `covid-bridge-vaccination` |
| `genericImmunization` | `genericImmunization` | `genericImmunization` | `generic-immunization` |
| `measlesVaccination` | `measlesVaccination` | `measlesVaccination` | `measles-vaccination` |
| `RIQuarterlyAggregate` | `RIQuarterlyAggregate` | `RIQuarterlyAggregate` | `ri-quarterly-aggregate` |

### `MetadataImpl` stored field

- WHEN `MetadataImpl.dataStreamId` field is non-null (e.g., set during deserialization),
  `getDataStreamId()` SHALL return the stored value without recomputing.
- This preserves round-trip fidelity for serialized metadata.
