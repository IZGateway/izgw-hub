# Spec: ADS folder path computation (`data_stream_id`)

**Component:** `MetadataBuilder`, `Metadata`, `MetadataImpl`  
**Implemented in:** `gov.cdc.izgateway.ads.MetadataBuilder` — `computeDataStreamId()`, `setReportType()`; `gov.cdc.izgateway.ads.Metadata` / `MetadataImpl` — `getDataStreamId()`  
**Related specs:** [`computeDataStreamId`](../compute-data-stream-id/spec.md), [`meta_ext_event`](../compute-meta-ext-event/spec.md)  

---

## Purpose

Define how `data_stream_id` — the folder segment of the DEX v2 blob storage path
`{container_base_path}/{data_stream_id}/{meta_ext_entity}/{YYYY}/{MM}/{DD}/{filename}` —
is derived for an ADS submission. `data_stream_id` is computed lazily at read time from
the stored `meta_ext_event` (not from the raw submitted `reportType`) using the
camelCase-to-kebab algorithm in `MetadataBuilder.computeDataStreamId()`, so that new file
types land in the folder NDLP's mapping table expects without a code change (IGDD-2775).
A `data_stream_id` already present on a deserialized metadata record is preserved rather
than recomputed, keeping serialized metadata round-trip safe.

---

## Requirements

### Requirement: data_stream_id is derived from meta_ext_event
`data_stream_id` SHALL be computed from `meta_ext_event` (not from the raw submitted `reportType`) using the camelCase→kebab algorithm in `MetadataBuilder.computeDataStreamId()`. `Metadata.getDataStreamId()` SHALL delegate entirely to `MetadataBuilder.computeDataStreamId(getExtEvent())` with no hardcoded switch cases, so that new file types are handled without a code change.

The DEX v2 blob storage path is `{container_base_path}/{data_stream_id}/{meta_ext_entity}/{YYYY}/{MM}/{DD}/{filename}`; a wrong `data_stream_id` places the file in a folder NDLP's mapping table does not recognise.

#### Scenario: farmerFlu yields farmer-flu-vaccination
- **GIVEN** a `MetadataBuilder` with `setReportType("farmerFlu")`
- **WHEN** `getDataStreamId()` is called on the built metadata
- **THEN** `meta_ext_event` is `farmerFluVaccination`
- **AND** `data_stream_id` is `farmer-flu-vaccination` (not `farmer-flu`)

#### Scenario: covidAllMonthlyVaccination yields covid-all-monthly-vaccination
- **GIVEN** a `MetadataBuilder` with `setReportType("covidAllMonthlyVaccination")`
- **WHEN** `getDataStreamId()` is called on the built metadata
- **THEN** `data_stream_id` is `covid-all-monthly-vaccination`

#### Scenario: Standard file types yield their kebab-case form
- **GIVEN** a `MetadataBuilder` with `setReportType(x)` for each of `routineImmunization`, `influenzaVaccination`, `rsvPrevention`, `covidBridgeVaccination`, `genericImmunization`, `measlesVaccination`, `riQuarterlyAggregate`
- **WHEN** `getDataStreamId()` is called on the built metadata
- **THEN** `data_stream_id` is, respectively, `routine-immunization`, `influenza-vaccination`, `rsv-prevention`, `covid-bridge-vaccination`, `generic-immunization`, `measles-vaccination`, `ri-quarterly-aggregate`

#### Scenario: Unregistered type is still hyphenated from its extEvent
- **GIVEN** a `MetadataBuilder` with a registry service that does not know `"someNewType"`
- **WHEN** `setReportType("someNewType")` is called and `getDataStreamId()` is read
- **THEN** `data_stream_id` is `some-new-type` (computed, not an all-lowercase fallback)

### Requirement: data_stream_id is not pre-computed during setReportType
`MetadataBuilder.setReportType()` SHALL NOT store a `data_stream_id` value on the metadata. `data_stream_id` SHALL be computed lazily by `Metadata.getDataStreamId()` from the stored `meta_ext_event` at read time.

#### Scenario: setReportType leaves the stored dataStreamId null
- **GIVEN** a `MetadataBuilder` with `setReportType("farmerFlu")`
- **WHEN** the built `MetadataImpl` is inspected
- **THEN** the `dataStreamId` field is `null`
- **AND** `getDataStreamId()` still returns `farmer-flu-vaccination` via the default computation

### Requirement: Explicitly stored data_stream_id is preserved
WHEN the `MetadataImpl.dataStreamId` field is non-null (for example, set during JSON deserialization of a previously submitted metadata record), `MetadataImpl.getDataStreamId()` SHALL return the stored value without recomputing it, preserving round-trip fidelity of serialized metadata.

#### Scenario: Deserialized value wins over computation
- **GIVEN** a `MetadataImpl` deserialized from JSON containing `"data_stream_id": "farmer-flu"` and `"meta_ext_event": "farmerFluVaccination"`
- **WHEN** `getDataStreamId()` is called
- **THEN** the return value is `farmer-flu` (the stored value)

#### Scenario: Null stored value falls through to computation
- **GIVEN** a `MetadataImpl` whose `dataStreamId` field is `null` and whose `meta_ext_event` is `rsvPrevention`
- **WHEN** `getDataStreamId()` is called
- **THEN** the return value is `rsv-prevention`
