---
schema_version: '1.0'
created:
  date: '2026-11-13T20:34:45.415Z'
  user: boonek
  agent:
    name: claude-code
    version: 1.0.0
  llm:
    name: claude-sonnet-5
    version: '5'
  prompt_uri: >-
    prompt:/claude-code/6489d253-64a8-47f7-9d36-4bd4d59d6e03/~db3b975b-82fb-46c0-b844-9bda262bcec2
  summary: >-
    MODIFIED delta: trim fileTypeName when refreshing the registry cache, fixing
    a real dev incident
updated: []
change_request: annual-ri-aggregate-zip-submission
ticket: IGDD-3743
---
# Spec Delta

## MODIFIED Requirements

### Requirement: Three-tier registry lookup
`NewModelHelper.getFileType(reportType)` SHALL resolve a submitted `reportType` to a registered `IFileType` using three tiers, evaluated in order and stopping at the first match:

1. **Exact match** — case-sensitive equality with a registered `fileTypeName`.
2. **Case-insensitive match** — equality ignoring case.
3. **Noise-word stripped match** — the words `vaccination`, `immunization`, `prevention`, `monthly`, and `quarterly` are removed (case-insensitively) from both the submitted value and each registry key, the results are lower-cased, and compared for equality.

The noise-word list and comparison live in `ADSUtils.NOISE_WORDS`, `ADSUtils.stripNoiseWords()`, and `ADSUtils.matchReportType()` so the same algorithm can be applied to a plain list of event-type names.

`NewModelHelper.refresh()` SHALL trim each registered `fileTypeName` (leading and
trailing whitespace) when rebuilding the cache, and SHALL write the trimmed value
back onto the `FileType` entity itself, not only onto the cache key — so that a
caller reading `fileType.getFileTypeName()` after a successful lookup receives the
trimmed value, not whatever was stored verbatim in DynamoDB.

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

#### Scenario: Registry value with incidental whitespace still exact-matches
- **GIVEN** a DynamoDB-backed registry entry whose `fileTypeName` was hand-entered as
  `"riAnnualAggregate "` (trailing space)
- **WHEN** `getFileType("riAnnualAggregate")` is called (no trailing space)
- **THEN** the entry is returned via an exact match, not merely a looser tier
- **AND** the returned entry's own `getFileTypeName()` is `"riAnnualAggregate"`, not
  `"riAnnualAggregate "`
