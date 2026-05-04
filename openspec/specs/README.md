# ADS Metadata Management — Implementation Specifications

This directory contains detailed algorithm and validation specifications for the
**ADS Metadata Management Simplification (Computation-Only)** change request.

## Overview

All ADS metadata values are computed deterministically from a single input: the
`fileTypeName` string stored in the `FileType` DynamoDB record.  No new database
fields, repository methods, or UI screens are required.

```
fileTypeName (e.g. "influenzaVaccination")
       │
       ├── computeDataStreamId()   →  data_stream_id   (e.g. "influenza-vaccination")
       ├── computePeriodType()     →  periodType        (e.g. "MONTHLY")
       ├── computeMetaExtEvent()   →  meta_ext_event    (e.g. "influenzaVaccination")
       └── computeMetaExtEventType()→ meta_ext_event_type (e.g. "influenzaVaccination")
```

## Specifications

| File | Algorithm / Component | Status |
|------|-----------------------|--------|
| [compute-data-stream-id.md](compute-data-stream-id.md) | `computeDataStreamId()` — camelCase/PascalCase → kebab-case with acronym support | ✅ Written |
| [compute-period-type.md](compute-period-type.md) | `computePeriodType()` — derives MONTHLY / QUARTERLY / BOTH from name | ✅ Written |
| [compute-meta-ext-event.md](compute-meta-ext-event.md) | `computeMetaExtEvent()` and `computeMetaExtEventType()` | ✅ Written |
| [filename-validation.md](filename-validation.md) | `FilenameValidator` — pattern matching and 4-rule validation | ✅ Written |

## Authoritative Computation Table

The table below records the expected output of every computation for each
production file type.  Implementations **must** reproduce these values exactly.

| fileTypeName | data_stream_id | periodType | meta_ext_event | meta_ext_event_type |
|---|---|---|---|---|
| `routineImmunization` | `routine-immunization` | `QUARTERLY` | `routineImmunization` | `routineImmunization` |
| `influenzaVaccination` | `influenza-vaccination` | `MONTHLY` | `influenzaVaccination` | `influenzaVaccination` |
| `farmerFlu` | `farmer-flu` | `MONTHLY` | `farmerFluVaccination` | `farmerFlu` |
| `covidAllMonthlyVaccination` | `covid-all-monthly-vaccination` | `MONTHLY` | `covidAllMonthlyVaccination` | `covidAllMonthlyVaccination` |
| `covidBridgeVaccination` | `covid-bridge-vaccination` | `MONTHLY` | `covidBridgeVaccination` | `covidBridgeVaccination` |
| `rsvPrevention` | `rsv-prevention` | `MONTHLY` | `rsvPrevention` | `rsvPrevention` |
| `measlesVaccination` | `measles-vaccination` | `MONTHLY` | `measlesVaccination` | `measlesVaccination` |
| `genericImmunization` | `generic-immunization` | `BOTH` | `genericImmunization` | `genericImmunization` |

## Related Documents

- [Proposal](../proposal.md) — business rationale and constraint summary
- [Design](../design.md) — component architecture and code structure
- [Tasks](../tasks.md) — implementation task breakdown and acceptance criteria
