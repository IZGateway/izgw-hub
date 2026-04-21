# Spec: `CsvFilenameValidator`

**Component:** `CsvFilenameValidator` utility class  
**Implemented in:** `gov.cdc.izgateway.ads.util.CsvFilenameValidator`  
**Related DTOs:** `CsvFilenameComponents`  
**Related task:** Task 3 (FilenameValidator), Task 9 (Tests)

---

## Purpose

Parse and validate an uploaded ADS CSV (or ZIP) file's name against the standard
ADS filename convention.  The frequency keyword found in the filename (`Monthly`
or `Quarterly`) determines the submission cadence; no external period-type lookup
is performed.  Monthly is the default assumption: if no frequency keyword is present
the filename is rejected, but `isMonthly()` defaults to `true` when no `Quarterly`
keyword is found.

ZIP filename validation is handled separately by `ZipFilenameComponents`.

---

## Filename Structure

```
{Prefix}_{Entity}_{Date}.{extension}
```

where `{Prefix}` is the first underscore-delimited segment and **must contain**
either the keyword `Monthly` or `Quarterly` (detected case-insensitively at any
position within the segment).  After the keyword is removed, whatever remains
is the `reportTypeAbbrev`.

| Component | Format | Examples |
|---|---|---|
| `Prefix` | Letters only; contains `Monthly` or `Quarterly` at any position (case-insensitive) | `MonthlyFlu`, `riQuarterlyAggregate`, `QuarterlyRI` |
| `reportTypeAbbrev` | Prefix with frequency keyword stripped | `Flu`, `riAggregate`, `RI` |
| `_` | Literal underscore separator | |
| `Entity` | Exactly 3 letters ending in `A` or `a` (case-insensitive) | `XXA`, `MAA`, `NYA`, `CVA` |
| `_` | Literal underscore separator | |
| `Date` | `YYYYMMM` (monthly) or `YYYYQ#` (quarterly); case-insensitive | `2026FEB`, `2026Q2` |
| `.` | Literal dot | |
| `extension` | `csv` or `zip` (case-insensitive; stored lowercase) | `csv`, `zip` |

### Date Formats

**Monthly:** `YYYY` + 3-letter month abbreviation (case-insensitive; stored uppercase)

```
Valid months: JAN FEB MAR APR MAY JUN JUL AUG SEP OCT NOV DEC
Examples:     2026JAN  2026FEB  2025DEC
```

**Quarterly:** `YYYY` + `Q` + quarter digit (1–4)

```
Valid quarters: Q1 Q2 Q3 Q4
Examples:       2026Q1  2026Q2  2025Q4
```

### Regex Pattern

The structural regex (compiled with `Pattern.CASE_INSENSITIVE`) is:

```
^([A-Za-z]+)_([A-Z]{2}A)_((?:(\d{4})Q([1-4]))|(?:(\d{4})(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)))\.(csv|zip)$
```

| Group | Content |
|---|---|
| 1 | Full prefix segment (frequency keyword + report type abbreviation) |
| 2 | Entity ID (3 letters ending in A/a) |
| 3 | Full date string |
| 4 | Year (quarterly only) |
| 5 | Quarter digit (quarterly only) |
| 6 | Year (monthly only) |
| 7 | Month abbreviation (monthly only) |
| 8 | Extension |

> **Key difference from a naïve pattern:** the frequency keyword is **not** anchored
> to the start of the prefix.  Group 1 captures all letters before the first `_`, and
> frequency detection is performed as a separate post-match step using a
> case-insensitive `contains` check.  This allows filenames like
> `riQuarterlyAggregate_XXA_2025Q4.csv` where `Quarterly` appears mid-prefix.

### Two-Phase Parse Algorithm

```
function parseFilename(filename):
    if filename is blank:
        return null

    # Phase 0 – strip "test" marker (case-insensitive) anywhere in filename
    isTestFile = contains(filename, "test")  // case-insensitive
    if isTestFile:
        filename = remove(filename, "test")  // case-insensitive remove

    # Phase 1 – structural regex match
    if not FILENAME_PATTERN.matches(filename):
        return null

    prefix = group(1)   // e.g. "MonthlyFlu", "riQuarterlyAggregate"

    # Phase 2 – detect and strip frequency keyword from prefix
    if contains(prefix, "Quarterly"):  // case-insensitive
        frequency    = "Quarterly"
        reportAbbrev = remove(prefix, "Quarterly")  // case-insensitive
    else if contains(prefix, "Monthly"):  // case-insensitive
        frequency    = "Monthly"
        reportAbbrev = remove(prefix, "Monthly")    // case-insensitive
    else:
        return null   // no frequency keyword found

    return CsvFilenameComponents(frequency, reportAbbrev, entityId, ...)
```

---

## Validation Rules

`validate()` applies three checks.  **Check 1 is fail-fast**: if parsing fails,
an error is returned immediately and checks 2–3 are skipped.  Checks 2–3 both run
and accumulate errors (non-fail-fast), so the caller receives a complete list of
problems when multiple rules are violated.

### Requirement: Filename Parses Successfully

The validator SHALL reject any filename that is blank, does not match the
structural regex, or whose prefix contains neither `"Monthly"` nor `"Quarterly"`
(case-insensitive).  The validator SHALL skip checks 2–3 and return a single
error immediately if this check fails.

The error message SHALL follow the format:
```
Filename '<name>' does not match the expected pattern
{Monthly|Quarterly}{ReportType}_{XXA}_{YYYY(MMM|Q#)}.(csv|zip).
Examples: MonthlyFlu_XXA_2026FEB.csv, QuarterlyRI_XXA_2026Q2.csv
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

#### Scenario: Frequency keyword mid-prefix is accepted
- GIVEN a filename `"riQuarterlyAggregate_XXA_2025Q4.csv"` where `"Quarterly"` appears after `"ri"`
- WHEN `parseFilename` is called
- THEN the result is non-null
- AND `frequency` is `"Quarterly"` and `reportTypeAbbrev` is `"riAggregate"`

#### Scenario: "test" prefix stripped before parsing
- GIVEN a filename `"testMonthlyRSV_XXA_2022SEP.csv"` containing `"test"`
- WHEN `parseFilename` is called
- THEN the result is non-null
- AND `isTestFile` is `true`, `frequency` is `"Monthly"`, `reportTypeAbbrev` is `"RSV"`

#### Scenario: No frequency keyword in prefix rejected
- GIVEN a filename `"Flu_XXA_2026FEB.csv"` whose prefix `"Flu"` contains neither keyword
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

---

### Requirement: Entity ID Matches Expected

The validator SHALL skip this check when `expectedEntity` is null or blank.
When `expectedEntity` is provided, the validator SHALL require a
**case-insensitive** match between the entity ID parsed from the filename and
`expectedEntity`.

> **Note:** The structural regex enforces the 3-letter-ending-in-A/a format
> (Group 2: `[A-Z]{2}A` with `CASE_INSENSITIVE`). There is no separate format
> check in this rule — format is validated by Check 1.

#### Scenario: Entity check skipped when expectedEntity is null
- GIVEN a filename `"MonthlyFlu_XXA_2026FEB.csv"`
- AND expectedEntity is `null`
- WHEN `validate` is called
- THEN no entity error is produced

#### Scenario: Matching entity accepted
- GIVEN a filename `"MonthlyFlu_XXA_2026FEB.csv"` with entity `"XXA"`
- AND expectedEntity is `"XXA"`
- WHEN `validate` is called
- THEN no entity error is produced

#### Scenario: Matching entity accepted case-insensitively
- GIVEN a filename `"MonthlyRSV_xxA_2022SEP.csv"` with entity `"xxA"`
- AND expectedEntity is `"XXA"`
- WHEN `validate` is called
- THEN no entity error is produced

#### Scenario: Mismatched entity rejected
- GIVEN a filename `"MonthlyFlu_XXA_2026FEB.csv"` with entity `"XXA"`
- AND expectedEntity is `"MAA"`
- WHEN `validate` is called
- THEN the result is invalid
- AND the error message contains `"Entity ID in filename"` and `"expected 'MAA'"`

---

### Requirement: Date Matches Submission Period Parameter

The validator SHALL skip this check when `period` is null or blank.
When `period` is provided, the validator SHALL require that the date string
parsed from the filename matches `period` after both sides are **uppercased**
and **hyphens removed**.

This normalisation allows the period parameter to arrive as `"2026-FEB"`,
`"2026FEB"`, `"2026Q4"`, etc. and still match the filename date component.

#### Scenario: Period check skipped when period is null
- GIVEN a filename `"MonthlyFlu_XXA_2026FEB.csv"`
- AND period is `null`
- WHEN `validate` is called
- THEN no period error is produced

#### Scenario: Matching period accepted (hyphenated format)
- GIVEN a filename `"MonthlyRSV_XXA_2022SEP.csv"` with date `"2022SEP"`
- AND period is `"2022-SEP"` (hyphenated)
- WHEN `validate` is called
- THEN no period error is produced (hyphen removed before comparison)

#### Scenario: Matching quarterly period accepted
- GIVEN a filename `"riQuarterlyAggregate_XXA_2025Q4.csv"` with date `"2025Q4"`
- AND period is `"2025Q4"`
- WHEN `validate` is called
- THEN no period error is produced

#### Scenario: Mismatched period rejected
- GIVEN a filename `"MonthlyFlu_XXA_2026FEB.csv"` with date `"2026FEB"`
- AND period is `"2026-MAR"`
- WHEN `validate` is called
- THEN the result is invalid
- AND the error message contains `"does not match period parameter"`

---

## `CsvFilenameComponents`

Errors are carried directly on `CsvFilenameComponents` — there is no separate
`CsvFilenameValidationResult` DTO.  `validate()` never returns `null`.

```
CsvFilenameComponents  (@Value @Builder — immutable)
  ├── String  frequency         — "Monthly" or "Quarterly" (canonical case); null on parse failure
  ├── String  reportTypeAbbrev  — prefix with frequency keyword stripped, e.g. "Flu", "riAggregate"
  ├── String  entityId          — as parsed from filename, e.g. "XXA", "xxA"
  ├── String  dateString        — uppercased full date portion, e.g. "2026FEB" or "2026Q2"
  ├── String  year              — 4-digit year string, e.g. "2026"
  ├── String  month             — uppercased month abbrev, e.g. "FEB" (null if quarterly)
  ├── String  quarter           — quarter digit string, e.g. "4" (null if monthly)
  ├── String  extension         — lowercased extension without dot, e.g. "csv" or "zip"
  ├── boolean testFile          — true if original filename contained "test" (case-insensitive)
  ├── List<String> errors       — unmodifiable; empty when valid
  ├── boolean isValid()         — true iff errors is empty
  ├── boolean isMonthly()       — true iff frequency is NOT "Quarterly" (default: true)
  └── boolean isQuarterly()     — true iff frequency equals "Quarterly"
```

> **isMonthly() default:** returns `true` whenever `frequency` is not `"Quarterly"` —
> meaning monthly is the default assumption even when no frequency keyword is detected.
> In practice, `parseFilename()` returns `null` when no keyword is found, so the
> caller only receives a `CsvFilenameComponents` with a valid `frequency` value.

---

## Positive Examples (valid filenames)

| Filename | frequency | reportTypeAbbrev | entityId | dateString | testFile | Notes |
|---|---|---|---|---|---|---|
| `MonthlyFlu_XXA_2026FEB.csv` | Monthly | Flu | XXA | 2026FEB | false | Standard monthly |
| `MonthlyFarmerFlu_NYA_2025DEC.csv` | Monthly | FarmerFlu | NYA | 2025DEC | false | Multi-word report type |
| `MonthlyRSV_MAA_2026MAR.csv` | Monthly | RSV | MAA | 2026MAR | false | 3-letter abbreviation |
| `QuarterlyRI_XXA_2026Q2.csv` | Quarterly | RI | XXA | 2026Q2 | false | Standard quarterly |
| `QuarterlyRI_XXA_2026Q1.zip` | Quarterly | RI | XXA | 2026Q1 | false | ZIP extension |
| `riQuarterlyAggregate_XXA_2025Q4.csv` | Quarterly | riAggregate | XXA | 2025Q4 | false | Keyword mid-prefix |
| `testMonthlyRSV_XXA_2022SEP.csv` | Monthly | RSV | XXA | 2022SEP | **true** | "test" stripped |

---

## Negative Examples (invalid filenames)

| Filename | Failing Check | Reason |
|---|---|---|
| `Flu_XXA_2026FEB.csv` | Check 1 | Prefix `"Flu"` contains no frequency keyword |
| `RIA_16M.csv` | Check 1 | Does not match structural regex |
| `MonthlyFlu123_XXA_2026FEB.csv` | Check 1 | Digits in prefix (`[A-Za-z]+` disallows digits) |
| `MonthlyFlu_XXA_2026FEB.txt` | Check 1 | `.txt` not in `csv\|zip` |
| `MonthlyFlu_XXA_2026FEB` | Check 1 | Missing extension |
| `MonthlyFlu_XXA_2026FEB.csv` | Check 2 | entity `XXA` ≠ expectedEntity `MAA` |
| `MonthlyFlu_XXA_2026FEB.csv` | Check 3 | date `2026FEB` ≠ period `2026-MAR` |

---

## Method Signatures

```java
/**
 * Parse a CSV ADS submission filename into its structural components.
 * Returns null if the filename is blank, does not match the structural regex,
 * or contains no recognisable frequency keyword.
 *
 * @param filename the raw filename (with extension); may be null
 * @return parsed {@link CsvFilenameComponents}, or null on parse failure
 */
public static CsvFilenameComponents parseFilename(String filename)

/**
 * Validate a CSV ADS submission filename against three business rules.
 * The frequency keyword in the filename drives the submission cadence;
 * no external period-type parameter is required.
 *
 * @param filename       the raw filename to validate (with extension)
 * @param expectedEntity the expected entity/facility code (e.g. "XXA"); null skips entity check
 * @param period         the submission period parameter (e.g. "2026-FEB" or "2026Q2");
 *                       null skips the period check
 * @return {@link CsvFilenameComponents} — never null; call isValid() to check success
 */
public static CsvFilenameComponents validate(
        String filename,
        String expectedEntity,
        String period)
```

---

## Implementation Location

```
izgw-hub/src/main/java/gov/cdc/izgateway/ads/util/CsvFilenameValidator.java
izgw-hub/src/main/java/gov/cdc/izgateway/ads/util/CsvFilenameComponents.java

izgw-hub/src/test/java/gov/cdc/izgateway/ads/util/CsvFilenameValidatorTests.java
```
