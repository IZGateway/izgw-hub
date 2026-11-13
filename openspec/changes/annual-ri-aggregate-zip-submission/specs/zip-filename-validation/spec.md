---
schema_version: '1.0'
created:
  date: '2026-11-13T06:57:59.323Z'
  user: boonek
  agent:
    name: claude-code
    version: 1.0.0
  llm:
    name: claude-sonnet-5
    version: '5'
  prompt_uri: >-
    prompt:/claude-code/6489d253-64a8-47f7-9d36-4bd4d59d6e03/~dd641be6-9c76-48ec-9370-9243b651be97
  summary: >-
    New zip-filename-validation capability spec, generalized ZIP eligibility and
    divisor
updated:
  - date: '2026-11-13T16:57:34.825Z'
    user: boonek
    agent:
      name: claude-code
      version: 1.0.0
    llm:
      name: claude-sonnet-5
      version: '5'
    prompt_uri: >-
      prompt:/claude-code/6489d253-64a8-47f7-9d36-4bd4d59d6e03/~e19f6c61-cc21-402b-8034-d685cbb7dd17
    summary: >-
      Sync example entity from NIH to XXA (established testing destination)
      after Keith's rename; Sync mismatched-entity example from NIH to XXA; Fix
      mistaken edit that made both sides of the mismatched-entity scenario read
      XXA
  - date: '2026-11-13T07:04:23.205Z'
    user: boonek
    agent:
      name: claude-code
      version: 1.0.0
    llm:
      name: claude-sonnet-5
      version: '5'
    prompt_uri: >-
      prompt:/claude-code/6489d253-64a8-47f7-9d36-4bd4d59d6e03/~dd641be6-9c76-48ec-9370-9243b651be97
    summary: >-
      Make date-tolerance requirement self-contained; remove non-existent
      MONTHLY ZIP case and compute-period-type reference; Shorten requirement
      description to satisfy strict 500-char validation; Add back the one-day
      boundary tolerance as a scenario
change_request: annual-ri-aggregate-zip-submission
ticket: IGDD-3743
---
# Spec Delta

## Purpose

Defines which ADS report types may be submitted as a `.zip` file, how the ZIP
filename's entity and date fields are validated, and how the filename date is
matched against the submission period using a cadence-driven tolerance.

## ADDED Requirements

### Requirement: ZIP Filename Structure
The system SHALL parse an uploaded `.zip` filename's first three underscore-delimited
segments as `{Entity}_{Period}_{SubmissionDate}`, where `Entity` identifies the
submitting jurisdiction/facility, `Period` is the reporting period end date in
`YYYYMMDD` format, and `SubmissionDate` is the file submission date in `YYYYMMDD`
format. Any additional segments between `SubmissionDate` and the extension SHALL be
ignored by parsing.

#### Scenario: Standard filename parses
- GIVEN a filename `XXA_20251231_20260131_Z.zip`
- WHEN the filename is parsed
- THEN entity is `XXA`, period is `20251231`, and submission date is `20260131`

#### Scenario: Trailing segment before the zone marker is tolerated
- GIVEN a filename `XXA_20251231_20260131_Agg_Z.zip`
- WHEN the filename is parsed
- THEN entity is `XXA`, period is `20251231`, and submission date is `20260131`

### Requirement: ZIP Eligibility by Report Type
The system SHALL accept a `.zip` upload only when the submitted report type is
`routineImmunization` or its canonical `fileTypeName` contains `"annual"`
(case-insensitive). The system SHALL reject a `.zip` upload for any other report
type, recording an error that identifies the mismatch.

#### Scenario: routineImmunization may submit ZIP
- GIVEN a submission with report type `routineImmunization`
- WHEN a `.zip` file is submitted
- THEN the file type check passes

#### Scenario: Annual aggregate type may submit ZIP
- GIVEN a submission with report type `riAnnualAggregate`
- WHEN a `.zip` file is submitted
- THEN the file type check passes

#### Scenario: Quarterly aggregate type is rejected for ZIP
- GIVEN a submission with report type `riQuarterlyAggregate`
- WHEN a `.zip` file is submitted
- THEN the file type check fails
- AND an error is recorded indicating the file type does not match

#### Scenario: Generic immunization type skips the file type check
- GIVEN a submission with report type resolved to `genericImmunization`
- WHEN a `.zip` file is submitted
- THEN the file type check is skipped and no file-type error is recorded

### Requirement: Entity ID Must Match Submission Facility
The system SHALL reject a `.zip` submission when the entity ID parsed from the
filename does not case-insensitively match the facility entity supplied with the
submission.

#### Scenario: Matching entity accepted
- GIVEN a filename with entity `XXA`
- AND a submission facility entity of `XXA`
- WHEN the file is validated
- THEN no entity error is recorded

#### Scenario: Mismatched entity rejected
- GIVEN a filename with entity `XXA`
- AND a submission facility entity of `MAA`
- WHEN the file is validated
- THEN an error is recorded indicating the entity does not match

### Requirement: Filename Date Matches Period Using Cadence-Driven Tolerance
The system SHALL compare the filename's period date against the submission's period
value using a month-bucket tolerance that depends on whether the report type's
canonical name contains `"annual"` (case-insensitive), as shown in the scenarios
below.

#### Scenario: Quarterly (default) match accepts any month in the same quarter
- GIVEN a submission with report type `routineImmunization`
- AND a filename period date in February
- AND a submission period date in March of the same year
- WHEN the dates are compared
- THEN the match succeeds because both months fall in the same calendar quarter

#### Scenario: Quarterly (default) match rejects a different quarter
- GIVEN a submission with report type `routineImmunization`
- AND a filename period date in February
- AND a submission period date in June of the same year
- WHEN the dates are compared
- THEN the match fails

#### Scenario: Annual match ignores month and compares year only
- GIVEN a submission with report type `riAnnualAggregate`
- AND a filename period date of `2025-12-31`
- AND a submission period of `2025-12-31` (a valid value under the existing
  `YYYY-MM-DD` period format; no new period format is required)
- WHEN the dates are compared
- THEN the match succeeds because both dates fall in year `2025`, regardless of month

#### Scenario: One-day boundary tolerance is accepted
- GIVEN a filename period date that is exactly one calendar day earlier than the
  submission period date
- WHEN the initial comparison fails
- THEN the system retries with the filename date advanced by one day
- AND the match succeeds if that retry falls within the applicable tolerance
