# Spec: Report type normalization

**Component:** `NewModelHelper`, `ADSUtils`, `MetadataBuilder`, `ADSController`  
**Implemented in:** `gov.cdc.izgateway.ads.NewModelHelper.getFileType()`, `gov.cdc.izgateway.ads.ADSUtils` (`NOISE_WORDS`, `stripNoiseWords()`, `matchReportType()`), `gov.cdc.izgateway.ads.MetadataBuilder.setReportType()`, `gov.cdc.izgateway.ads.ADSController`  
**Related specs:** [`ads-folder-path-computation`](../ads-folder-path-computation/spec.md), [`meta_ext_event`](../compute-meta-ext-event/spec.md)  

---

## Purpose

Define how a submitted ADS `reportType` is resolved to its canonical name in the file-type
registry using three tiers evaluated in order: exact match, case-insensitive match, and
noise-word-stripped match (removing `vaccination`, `immunization`, `prevention`, `monthly`,
`quarterly`). The canonical name drives every derived metadata field (`meta_ext_event_type`,
`meta_ext_event`, `data_stream_id`), so legacy aliases such as `farmerFlu` or `covidall`
produce the same values as their registered form. `MetadataBuilder` warns and continues
when no registry entry matches, whereas `ADSController` rejects unregistered report types
at the REST boundary with a metadata fault. `GET /rest/ads/reportTypes` exposes the
currently registered names so API clients can discover valid values at runtime.

---

## Requirements

### Requirement: Three-tier registry lookup
`NewModelHelper.getFileType(reportType)` SHALL resolve a submitted `reportType` to a registered `IFileType` using three tiers, evaluated in order and stopping at the first match:

1. **Exact match** — case-sensitive equality with a registered `fileTypeName`.
2. **Case-insensitive match** — equality ignoring case.
3. **Noise-word stripped match** — the words `vaccination`, `immunization`, `prevention`, `monthly`, and `quarterly` are removed (case-insensitively) from both the submitted value and each registry key, the results are lower-cased, and compared for equality.

The noise-word list and comparison live in `ADSUtils.NOISE_WORDS`, `ADSUtils.stripNoiseWords()`, and `ADSUtils.matchReportType()` so the same algorithm can be applied to a plain list of event-type names.

#### Scenario: Exact match
- **GIVEN** a registry containing `farmerFluVaccination`
- **WHEN** `getFileType("farmerFluVaccination")` is called
- **THEN** the `farmerFluVaccination` entry is returned

#### Scenario: Case-insensitive match
- **GIVEN** a registry containing `routineImmunization` and `farmerFluVaccination`
- **WHEN** `getFileType("ROUTINEIMMUNIZATION")` and `getFileType("FARMERFLUVACCINATION")` are called
- **THEN** the `routineImmunization` and `farmerFluVaccination` entries are returned, respectively

#### Scenario: Noise-word stripped match resolves legacy aliases
- **GIVEN** a registry containing `farmerFluVaccination`, `covidAllMonthlyVaccination`, `rsvPrevention`, `influenzaVaccination`, `measlesVaccination`
- **WHEN** `getFileType(x)` is called for `farmerFlu`, `FARMERFLU`, `covidall`, `rsv`, `influenza`, `measles`
- **THEN** the returned entries are `farmerFluVaccination`, `farmerFluVaccination`, `covidAllMonthlyVaccination`, `rsvPrevention`, `influenzaVaccination`, `measlesVaccination`, respectively

#### Scenario: No match returns null
- **GIVEN** a registry that contains no entry resembling `"unknownType"`
- **WHEN** `getFileType("unknownType")` is called
- **THEN** the return value is `null`

#### Scenario: Blank or null input returns null
- **GIVEN** any registry
- **WHEN** `getFileType("")` or `getFileType(null)` is called
- **THEN** the return value is `null` without consulting the registry

### Requirement: Canonical name drives all derived fields
WHEN `MetadataBuilder.setReportType(reportType)` is called with an `IAccessControlService` available and the registry lookup succeeds, the canonical `IFileType.getFileTypeName()` SHALL replace the submitted value before any computation. `meta_ext_event_type` SHALL be the canonical name, and `meta_ext_event` and `data_stream_id` SHALL be computed from the canonical name.

#### Scenario: Legacy alias produces canonical fields
- **GIVEN** a `MetadataBuilder` constructed with a registry service whose lookup of `farmerFlu` returns the `farmerFluVaccination` entry
- **WHEN** `setReportType("farmerFlu")` is called
- **THEN** `meta_ext_event_type` is `farmerFluVaccination`
- **AND** `meta_ext_event` is `farmerFluVaccination`
- **AND** `data_stream_id` is `farmer-flu-vaccination`

#### Scenario: Lower-cased alias produces correct hyphenation
- **GIVEN** a `MetadataBuilder` constructed with a registry service whose lookup of `covidall` returns the `covidAllMonthlyVaccination` entry
- **WHEN** `setReportType("covidall")` is called
- **THEN** `meta_ext_event_type` is `covidAllMonthlyVaccination`
- **AND** `data_stream_id` is `covid-all-monthly-vaccination` (not `covidall`)

#### Scenario: Casing variants converge on one value
- **GIVEN** a `MetadataBuilder` constructed with a registry service
- **WHEN** `setReportType(x)` is called for each of `farmerflu`, `FARMERFLU`, `ROUTINEIMMUNIZATION`
- **THEN** `data_stream_id` is `farmer-flu-vaccination`, `farmer-flu-vaccination`, `routine-immunization`, respectively

### Requirement: Unregistered type warns and continues in the builder
WHEN `MetadataBuilder.setReportType(reportType)` is called with an `IAccessControlService` available and no registry entry matches at any tier, the builder SHALL log a message at WARN level and SHALL continue computing fields from the submitted value unchanged. The builder SHALL NOT add a validation error for this condition.

#### Scenario: Unknown type logs a warning but builds
- **GIVEN** a `MetadataBuilder` constructed with a registry service whose lookup of `unknownType` returns `null`
- **WHEN** `setReportType("unknownType")` is called
- **THEN** a WARN-level message naming `unknownType` is logged
- **AND** the builder's error list is empty
- **AND** `meta_ext_event_type` is `unknownType`

### Requirement: No-service path skips normalization
WHEN a `MetadataBuilder` is constructed without an `IAccessControlService`, `setReportType()` SHALL NOT attempt a registry lookup and SHALL compute all fields directly from the submitted value.

#### Scenario: Raw value drives computation without a service
- **GIVEN** a `MetadataBuilder` constructed with no registry service
- **WHEN** `setReportType("farmerFlu")` is called
- **THEN** `meta_ext_event_type` is `farmerFlu`
- **AND** `meta_ext_event` is `farmerFluVaccination` (via the `computeMetaExtEvent` special case)
- **AND** `data_stream_id` is `farmer-flu-vaccination`

#### Scenario: Blank input is rejected before any lookup
- **GIVEN** a `MetadataBuilder` with or without a registry service
- **WHEN** `setReportType("")` or `setReportType(null)` is called
- **THEN** the error `"Report Type must be present and not empty"` is added
- **AND** no registry lookup is attempted

### Requirement: REST boundary rejects unregistered report types
WHEN a file is submitted through `ADSController`, the controller SHALL first match the submitted `reportType` against the registered event types (`config.getAccessControls().getEventTypes()`) using `ADSUtils.matchReportType()` — the same three-tier algorithm — before calling `setReportType()`. On a match the canonical value SHALL be passed to `setReportType()`. On no match the controller SHALL add the validation error `"<reportType> is not a valid reportType value. This must be one of [...]"` to the builder, causing the submission to be rejected with a metadata fault. The warn-and-continue behaviour of the builder therefore applies only to direct callers of `MetadataBuilder`, not to REST submissions.

#### Scenario: REST submission with a legacy alias is accepted
- **GIVEN** the registered event types include `farmerFluVaccination`
- **WHEN** a file is submitted to `/rest/ads/...` with `reportType=farmerFlu`
- **THEN** the submission is accepted
- **AND** the response metadata has `meta_ext_event_type = farmerFluVaccination` and `data_stream_id = farmer-flu-vaccination`

#### Scenario: REST submission with an unregistered type is rejected
- **GIVEN** the registered event types do not include anything resembling `bogusType`
- **WHEN** a file is submitted with `reportType=bogusType`
- **THEN** the request fails with a metadata fault
- **AND** the error message contains `"bogusType is not a valid reportType value"`

### Requirement: Report type discovery endpoint
The system SHALL expose `GET /rest/ads/reportTypes` returning the currently registered report type names as a JSON array of strings, so that API clients can discover valid `reportType` values at runtime. The `@Schema` description of the `reportType` upload parameter SHALL reference this endpoint.

#### Scenario: Endpoint returns the registered names
- **GIVEN** the file-type registry contains `routineImmunization`, `influenzaVaccination`, `farmerFluVaccination`
- **WHEN** `GET /rest/ads/reportTypes` is called
- **THEN** the response is HTTP 200
- **AND** the body is a JSON array containing `"routineImmunization"`, `"influenzaVaccination"`, `"farmerFluVaccination"`
