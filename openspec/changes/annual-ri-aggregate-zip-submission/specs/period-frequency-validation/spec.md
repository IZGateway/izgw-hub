---
schema_version: '1.0'
created:
  date: '2026-11-13T17:29:06.334Z'
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
    New period-frequency-validation capability: bare YYYY support and the
    period-shape-vs-report-type cross-check
updated:
  - date: '2026-11-13T17:29:36.814Z'
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
      Shorten requirement description to satisfy strict 500-char validation; Add
      dedicated requirement stating the established-frequency mapping explicitly
change_request: annual-ri-aggregate-zip-submission
ticket: IGDD-3743
---
# Spec Delta

## Purpose

Defines the structural formats a `period` submission value may take (monthly,
quarterly, or annual), and requires that the format actually submitted matches the
established submission frequency for the report type it accompanies.

## ADDED Requirements

### Requirement: Period Structural Formats
The system SHALL accept a `period` value in exactly one of four structural forms
(after trimming and uppercasing): `YYYYQ#` (quarterly, `#` is `1`-`4`), `YYYY-MON`
(monthly, `MON` is a 3-letter month abbreviation), `YYYY` alone (annual), or the
legacy numeric `YYYY-MM` / `YYYY-MM-DD` form. The system SHALL reject any other
value with a validation error.

#### Scenario: Quarterly format accepted
- GIVEN a period value `"2025Q4"`
- WHEN the period is validated
- THEN it is accepted as structurally valid

#### Scenario: Monthly format accepted
- GIVEN a period value `"2025-FEB"`
- WHEN the period is validated
- THEN it is accepted as structurally valid

#### Scenario: Annual (bare year) format accepted
- GIVEN a period value `"2025"`
- WHEN the period is validated
- THEN it is accepted as structurally valid

#### Scenario: Legacy numeric date format still accepted
- GIVEN a period value `"2025-12-31"`
- WHEN the period is validated
- THEN it is accepted as structurally valid

#### Scenario: Malformed value rejected
- GIVEN a period value `"2025Q5"` (no such quarter)
- WHEN the period is validated
- THEN a validation error is recorded

### Requirement: Established Frequency Mapping
The system SHALL determine a report type's established frequency as: `Annual` when
its canonical name contains `"annual"` (case-insensitive); otherwise `Quarterly` when
it is `routineImmunization` or its name contains `"quarter"`; otherwise `Monthly`.

#### Scenario: Annual name maps to Annual
- GIVEN a report type `riAnnualAggregate`
- WHEN its established frequency is determined
- THEN the result is `Annual`

#### Scenario: routineImmunization maps to Quarterly
- GIVEN a report type `routineImmunization`
- WHEN its established frequency is determined
- THEN the result is `Quarterly`

#### Scenario: Name containing "quarter" maps to Quarterly
- GIVEN a report type `riQuarterlyAggregate`
- WHEN its established frequency is determined
- THEN the result is `Quarterly`

#### Scenario: Unrecognized name defaults to Monthly
- GIVEN a report type `influenzaVaccination`
- WHEN its established frequency is determined
- THEN the result is `Monthly`

### Requirement: Period Shape Must Match Report Type's Established Frequency
The system SHALL cross-check a structurally-valid period's shape against the
established frequency of the report type already set on the submission, and SHALL
record a validation error when they do not match. The system SHALL skip this check
for `genericImmunization` and for the legacy numeric `YYYY-MM`/`YYYY-MM-DD` form (see
scenarios for the established-frequency mapping and both skip cases).

#### Scenario: Annual report type with annual period passes
- GIVEN a submission with report type `riAnnualAggregate`
- AND a period value `"2025"`
- WHEN the period is validated
- THEN no frequency-mismatch error is recorded

#### Scenario: Annual report type with quarterly period fails
- GIVEN a submission with report type `riAnnualAggregate`
- AND a period value `"2025Q4"`
- WHEN the period is validated
- THEN a frequency-mismatch error is recorded

#### Scenario: Quarterly report type with monthly period fails
- GIVEN a submission with report type `routineImmunization`
- AND a period value `"2025-FEB"`
- WHEN the period is validated
- THEN a frequency-mismatch error is recorded

#### Scenario: Monthly report type with annual period fails
- GIVEN a submission with report type `influenzaVaccination`
- AND a period value `"2025"`
- WHEN the period is validated
- THEN a frequency-mismatch error is recorded

#### Scenario: genericImmunization skips the cross-check
- GIVEN a submission with report type `genericImmunization`
- AND a period value of any structurally-valid shape
- WHEN the period is validated
- THEN no frequency-mismatch error is recorded

#### Scenario: Legacy numeric date form skips the cross-check
- GIVEN a submission with report type `influenzaVaccination` (established frequency
  `Monthly`)
- AND a period value `"2025-12-31"` (legacy numeric form)
- WHEN the period is validated
- THEN no frequency-mismatch error is recorded, even though the report type's
  established frequency is `Monthly` and this value is not `YYYY-MON`-shaped
