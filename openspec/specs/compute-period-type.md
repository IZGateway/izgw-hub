# Spec: `computePeriodType(fileTypeName)`

**Component:** `FileTypeMetadataUtil` (static method)  
**Implemented in:** `gov.cdc.izgateway.ads.util.FileTypeMetadataUtil`  
**Related task:** Task 1 (MetadataBuilder utilities), Task 7 (Tests)

---

## Purpose

Derive the ADS submission period type (`MONTHLY`, `QUARTERLY`, or `BOTH`) from a
`fileTypeName` string.  The period type is used during filename validation to
verify that the frequency keyword in the uploaded filename matches the expected
cadence of the report type.

---

## Decision Rules (evaluated in order)

| Priority | Condition on `fileTypeName` | Return value |
|---|---|---|
| 1 | `null` or blank | `"MONTHLY"` (safe default) |
| 2 | lowercase value contains `"quarter"` | `"QUARTERLY"` |
| 3 | lowercase value starts with `"ri"` | `"QUARTERLY"` |
| 4 | case-sensitive value equals `"routineImmunization"` | `"QUARTERLY"` |
| 5 | case-sensitive value equals `"genericImmunization"` | `"BOTH"` |
| 6 | _(none of the above)_ | `"MONTHLY"` |

> **Note:** Rules 2–3 cover future file types that follow the naming convention.
> Rule 4 explicitly covers the canonical spelling used in production today.
> Rule 5 is a documented special case: generic immunization data may be
> submitted either monthly or quarterly.

### Rationale for "RI" prefix rule

`RI` is the standard abbreviation for **Routine Immunization**.  The CDC ADS
pipeline expects routine immunization data on a quarterly basis.  Any file type
whose name begins with `ri` (case-insensitive) is therefore QUARTERLY.  This
means future names such as `riAnnualSupplement` would automatically inherit the
correct period without code changes.

---

## Pseudocode

```
function computePeriodType(fileTypeName):
    if fileTypeName is null or blank:
        return "MONTHLY"

    lower = toLowerCase(fileTypeName)

    if lower contains "quarter":
        return "QUARTERLY"

    if lower startsWith "ri":
        return "QUARTERLY"

    if fileTypeName == "routineImmunization":
        return "QUARTERLY"

    if fileTypeName == "genericImmunization":
        return "BOTH"

    return "MONTHLY"
```

---

## Production Examples

| `fileTypeName` | Rule matched | `periodType` |
|---|---|---|
| `routineImmunization` | Rule 3 (`startsWith "ri"`) | `QUARTERLY` |
| `influenzaVaccination` | Rule 6 (default) | `MONTHLY` |
| `farmerFlu` | Rule 6 (default) | `MONTHLY` |
| `covidAllMonthlyVaccination` | Rule 6 (default) | `MONTHLY` |
| `covidBridgeVaccination` | Rule 6 (default) | `MONTHLY` |
| `rsvPrevention` | Rule 6 (default) | `MONTHLY` |
| `measlesVaccination` | Rule 6 (default) | `MONTHLY` |
| `genericImmunization` | Rule 5 | `BOTH` |

---

## Additional Examples (including future/hypothetical names)

| `fileTypeName` | Rule matched | `periodType` | Notes |
|---|---|---|---|
| `RIQuarterlyAggregate` | Rule 3 (`startsWith "ri"`) | `QUARTERLY` | PascalCase; lower is `"riquarterlyaggregate"` |
| `riAnnualData` | Rule 3 | `QUARTERLY` | hypothetical future type |
| `quarterlyFluSummary` | Rule 2 (`contains "quarter"`) | `QUARTERLY` | hypothetical |
| `annualQuarterReport` | Rule 2 | `QUARTERLY` | "quarter" embedded in word |
| `GENERIC_IMMUNIZATION` | Rule 6 (default) | `MONTHLY` | does not match exact string `"genericImmunization"` |
| `""` (empty) | Rule 1 | `MONTHLY` | blank input → safe default |
| `null` | Rule 1 | `MONTHLY` | null input → safe default |

---

## Edge Cases and Gotchas

### Case Sensitivity on Rules 4 and 5

Rules 4 and 5 are **case-sensitive** exact matches to preserve backward
compatibility with the existing hardcoded values.  The strings
`"routineImmunization"` and `"genericImmunization"` are the canonical
`fileTypeName` values as stored in DynamoDB.

However, Rules 2 and 3 use `toLowerCase()` comparisons, so variants like
`RoutineImmunization` or `RIData` are also captured.

### Rule 4 vs Rule 3

`"routineImmunization"` starts with `"ri"`, so it is captured by Rule 3
before it reaches Rule 4.  Rule 4 exists as documentation and a safety net
for any future refactoring that might remove or reorder the rules.

### "BOTH" means either frequency is acceptable

When `periodType = "BOTH"`, the `FilenameValidator` accepts filenames that
start with either `Monthly` or `Quarterly`.  The date format in the filename
must still be consistent with whichever frequency keyword is used.

---

## Test Cases

### Requirement: Null or Blank Input Default

`computePeriodType` SHALL return `"MONTHLY"` for null or blank input.

#### Scenario: Null input returns MONTHLY
- GIVEN a null fileTypeName
- WHEN `computePeriodType` is called
- THEN the return value is `"MONTHLY"`

#### Scenario: Empty string returns MONTHLY
- GIVEN a fileTypeName of `""`
- WHEN `computePeriodType` is called
- THEN the return value is `"MONTHLY"`

#### Scenario: Whitespace-only string returns MONTHLY
- GIVEN a fileTypeName of `"   "` (whitespace only)
- WHEN `computePeriodType` is called
- THEN the return value is `"MONTHLY"`

---

### Requirement: "Quarter" Substring Maps to Quarterly

`computePeriodType` SHALL return `"QUARTERLY"` for any fileTypeName whose lowercase form contains `"quarter"`.

#### Scenario: quarterlyFluSummary returns QUARTERLY
- GIVEN a fileTypeName of `"quarterlyFluSummary"`
- WHEN `computePeriodType` is called
- THEN the return value is `"QUARTERLY"`

#### Scenario: annualQuarterReport returns QUARTERLY
- GIVEN a fileTypeName of `"annualQuarterReport"` (contains `"quarter"` embedded in word)
- WHEN `computePeriodType` is called
- THEN the return value is `"QUARTERLY"`

---

### Requirement: "RI" Prefix Maps to Quarterly

`computePeriodType` SHALL return `"QUARTERLY"` for any fileTypeName whose lowercase form starts with `"ri"`.

#### Scenario: routineImmunization returns QUARTERLY via RI prefix
- GIVEN a fileTypeName of `"routineImmunization"` (lowercase starts with `"ri"`)
- WHEN `computePeriodType` is called
- THEN the return value is `"QUARTERLY"`

#### Scenario: RIQuarterlyAggregate returns QUARTERLY
- GIVEN a fileTypeName of `"RIQuarterlyAggregate"` (PascalCase; lowercase starts with `"ri"`)
- WHEN `computePeriodType` is called
- THEN the return value is `"QUARTERLY"`

#### Scenario: riAnnualData returns QUARTERLY
- GIVEN a fileTypeName of `"riAnnualData"` (hypothetical future type starting with `"ri"`)
- WHEN `computePeriodType` is called
- THEN the return value is `"QUARTERLY"`

---

### Requirement: routineImmunization Maps to Quarterly

`computePeriodType` SHALL return `"QUARTERLY"` for the exact value `"routineImmunization"`.

> **Note:** This requirement is already satisfied by the "RI" Prefix rule above; this serves as an explicit safety net in case rule ordering changes.

#### Scenario: routineImmunization returns QUARTERLY
- GIVEN a fileTypeName of `"routineImmunization"`
- WHEN `computePeriodType` is called
- THEN the return value is `"QUARTERLY"`

---

### Requirement: genericImmunization Maps to Both

`computePeriodType` SHALL return `"BOTH"` for the exact value `"genericImmunization"`.

#### Scenario: genericImmunization returns BOTH
- GIVEN a fileTypeName of `"genericImmunization"`
- WHEN `computePeriodType` is called
- THEN the return value is `"BOTH"`

#### Scenario: GENERIC_IMMUNIZATION does not match and returns MONTHLY
- GIVEN a fileTypeName of `"GENERIC_IMMUNIZATION"` (uppercase with underscore; does not match exact string `"genericImmunization"`)
- WHEN `computePeriodType` is called
- THEN the return value is `"MONTHLY"`

---

### Requirement: All Other File Types Default to Monthly

`computePeriodType` SHALL return `"MONTHLY"` for all other non-null, non-blank inputs that do not match any prior rule.

#### Scenario: influenzaVaccination returns MONTHLY
- GIVEN a fileTypeName of `"influenzaVaccination"`
- WHEN `computePeriodType` is called
- THEN the return value is `"MONTHLY"`

#### Scenario: farmerFlu returns MONTHLY
- GIVEN a fileTypeName of `"farmerFlu"`
- WHEN `computePeriodType` is called
- THEN the return value is `"MONTHLY"`

#### Scenario: covidAllMonthlyVaccination returns MONTHLY
- GIVEN a fileTypeName of `"covidAllMonthlyVaccination"`
- WHEN `computePeriodType` is called
- THEN the return value is `"MONTHLY"`

#### Scenario: covidBridgeVaccination returns MONTHLY
- GIVEN a fileTypeName of `"covidBridgeVaccination"`
- WHEN `computePeriodType` is called
- THEN the return value is `"MONTHLY"`

#### Scenario: rsvPrevention returns MONTHLY
- GIVEN a fileTypeName of `"rsvPrevention"`
- WHEN `computePeriodType` is called
- THEN the return value is `"MONTHLY"`

#### Scenario: measlesVaccination returns MONTHLY
- GIVEN a fileTypeName of `"measlesVaccination"`
- WHEN `computePeriodType` is called
- THEN the return value is `"MONTHLY"`

---

## Implementation Location

```
izgw-hub/src/main/java/gov/cdc/izgateway/ads/util/FileTypeMetadataUtil.java
  └── public static String computePeriodType(String fileTypeName)

izgw-hub/src/test/java/test/gov/cdc/izgateway/ads/util/FileTypeMetadataUtilTests.java
  └── computePeriodType_* test methods
```
