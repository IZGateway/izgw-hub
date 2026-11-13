---
schema_version: '1.0'
created:
  date: '2026-11-13T20:34:29.809Z'
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
    MODIFIED delta: CsvFilenameValidator now recognizes Annual as a third
    frequency keyword
updated:
  - date: '2026-11-13T21:06:24.007Z'
    user: boonek
    agent:
      name: claude-code
      version: 1.0.0
    llm:
      name: claude-sonnet-5
      version: '5'
    prompt_uri: >-
      prompt:/claude-code/6489d253-64a8-47f7-9d36-4bd4d59d6e03/~0f4963bc-d59c-4f0d-921d-a60f0932a8ba
    summary: >-
      Add ADDED requirement for the frequency-keyword-vs-date-shape cross-check,
      per Keith's review triage; Shorten requirement description to satisfy
      strict 500-char validation
change_request: annual-ri-aggregate-zip-submission
ticket: IGDD-3743
---
# Spec Delta

## MODIFIED Requirements

### Requirement: Filename Parses Successfully

The validator SHALL reject any filename that is blank, does not match the
structural regex, or whose prefix contains none of `"Monthly"`, `"Quarterly"`, or
`"Annual"` (case-insensitive).  The validator SHALL skip checks 2–3 and return a
single error immediately if this check fails.

The error message SHALL follow the format:
```
Filename '<name>' does not match the expected pattern
{Monthly|Quarterly|Annual}{ReportType}_{XXA}_{YYYY(MMM|Q#)|YYYY}.(csv|zip).
Examples: MonthlyFlu_XXA_2026FEB.csv, QuarterlyRI_XXA_2026Q2.csv, AnnualRI_XXA_2026.csv
```

#### Scenario: Standard monthly filename parses
- GIVEN a filename `"MonthlyFlu_XXA_2026FEB.csv"`
- WHEN `parseFilename` is called
- THEN the result is non-null
- AND `frequency` is `"Monthly"`, `reportTypeAbbrev` is `"Flu"`, `entityId` is `"XXA"`, `dateString` is `"2026FEB"`

#### Scenario: Standard quarterly filename parses
- GIVEN a filename `"QuarterlyRI_XXA_2026Q1.zip"`
- WHEN `parseFilename` is called
- THEN the result is non-null
- AND `frequency` is `"Quarterly"`, `reportTypeAbbrev` is `"RI"`, `extension` is `"zip"`

#### Scenario: Standard annual filename parses
- GIVEN a filename `"AnnualRI_XXA_2026.csv"`
- WHEN `parseFilename` is called
- THEN the result is non-null
- AND `frequency` is `"Annual"`, `reportTypeAbbrev` is `"RI"`, `dateString` is `"2026"`, `year` is `"2026"`
- AND `month` and `quarter` are both `null`

#### Scenario: Frequency keyword mid-prefix is accepted
- GIVEN a filename `"riQuarterlyAggregate_XXA_2025Q4.csv"` where `"Quarterly"` appears after `"ri"`
- WHEN `parseFilename` is called
- THEN the result is non-null
- AND `frequency` is `"Quarterly"` and `reportTypeAbbrev` is `"riAggregate"`

#### Scenario: Annual keyword mid-prefix is accepted
- GIVEN a filename `"riAnnualAggregate_XXA_2026.csv"` where `"Annual"` appears after `"ri"`
- WHEN `parseFilename` is called
- THEN the result is non-null
- AND `frequency` is `"Annual"` and `reportTypeAbbrev` is `"riAggregate"`

#### Scenario: "test" prefix stripped before parsing
- GIVEN a filename `"testMonthlyRSV_XXA_2022SEP.csv"` containing `"test"`
- WHEN `parseFilename` is called
- THEN the result is non-null
- AND `isTestFile` is `true`, `frequency` is `"Monthly"`, `reportTypeAbbrev` is `"RSV"`

#### Scenario: No frequency keyword in prefix rejected
- GIVEN a filename `"Flu_XXA_2026FEB.csv"` whose prefix `"Flu"` contains none of the three keywords
- WHEN `validate` is called
- THEN the result is invalid
- AND the error message matches the pattern-mismatch format above

#### Scenario: Digits in prefix rejected
- GIVEN a filename `"MonthlyFlu123_XXA_2026FEB.csv"` whose prefix contains digits
- WHEN `validate` is called
- THEN the result is invalid

#### Scenario: Disallowed extension rejected
- GIVEN a filename `"MonthlyFlu_XXA_2026FEB.txt"`
- WHEN `validate` is called
- THEN the result is invalid

#### Scenario: Blank filename rejected
- GIVEN a blank or null filename
- WHEN `validate` is called
- THEN the result is invalid

## ADDED Requirements

### Requirement: Frequency Keyword Matches Date Shape

The validator SHALL reject a filename whose detected frequency keyword does not
match the date shape actually present: `"Quarterly"` requires a `YYYYQ#` date,
`"Monthly"` requires a `YYYYMMM` date, and `"Annual"` requires a bare `YYYY` date
(see scenarios for why this matters).

#### Scenario: Quarterly keyword with a bare-year date rejected
- GIVEN a filename `"QuarterlyRI_XXA_2026.csv"`
- WHEN `parseFilename` is called
- THEN the result is `null`

#### Scenario: Monthly keyword with a quarterly-shaped date rejected
- GIVEN a filename `"MonthlyFlu_XXA_2026Q2.csv"`
- WHEN `parseFilename` is called
- THEN the result is `null`

#### Scenario: Annual keyword with a monthly- or quarterly-shaped date rejected
- GIVEN a filename `"AnnualRI_XXA_2026FEB.csv"` or `"AnnualRI_XXA_2026Q2.csv"`
- WHEN `parseFilename` is called
- THEN the result is `null`
