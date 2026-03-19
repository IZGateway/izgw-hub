# Filename Validation Specification

## Overview

This specification defines the validation rules for ADS (Automated Data Submission) filenames. The filename structure encodes metadata that must match the report type configuration and submission parameters.

## Filename Structure

### Standard Format

```
[Frequency][ReportType]_[Entity]_[Date].[extension]
```

**Components:**
1. **Frequency + ReportType**: Frequency indicator concatenated with report type abbreviation (no separator)
2. **Entity**: 3-character jurisdiction/organization code (separated by underscore)
3. **Date**: Period identifier (year + month/quarter) (separated by underscore)
4. **Extension**: File type extension

**Examples:**
- `MonthlyFlu_XXA_2026FEB.csv`
- `QuarterlyRI_XXA_2026Q2.csv`
- `MonthlyAllCOVID_XXA_2026FEB.csv`

### Detailed Component Specifications

#### 1. Frequency + Report Type Prefix

**Required:** Must start with "Monthly" or "Quarterly" followed immediately by report type abbreviation

**Purpose:** Indicates submission frequency and report type in single prefix component

**Rules:**
- Must start with "Monthly" or "Quarterly" (case-sensitive, capital first letter)
- Followed immediately by report type abbreviation (no separator)
- Report type portion should be recognizable abbreviation or full name
- No hyphens or underscores between frequency and report type
- Separated from entity by underscore

**Valid Examples:**
```
MonthlyFlu_...              # Monthly + Flu
QuarterlyRI_...             # Quarterly + RI (Routine Immunization)
MonthlyAllCOVID_...         # Monthly + AllCOVID
MonthlyRSV_...              # Monthly + RSV
MonthlyMeasles_...          # Monthly + Measles
MonthlyFarmerFlu_...        # Monthly + FarmerFlu
```

**Invalid Examples:**
```
Flu_...                     # missing frequency indicator
monthly-flu_...             # lowercase frequency
Monthly-Flu_...             # hyphen between frequency and type (should be no separator)
WeeklyFlu_...               # invalid frequency (must be Monthly or Quarterly)
```

#### 2. Entity ID

**Required:** 3 uppercase letters ending in 'A'

**Purpose:** Identifies submitting jurisdiction or organization

**Format:** `[A-Z]{2}A`

**Separator:** Underscore before entity, underscore after entity

**Rules:**
- Exactly 3 characters
- All uppercase letters
- MUST end with letter 'A'
- Must match facilityId parameter from API request

**Valid Examples:**
```
MAA  # Massachusetts
NYA  # New York
CVA  # CVS Health
CAA  # California
TXA  # Texas
```

**Invalid Examples:**
```
XYZ  # Doesn't end in A
MA   # Too short
MAAA # Too long
maa  # Lowercase not allowed
123A # Numbers not allowed
```

**Special Cases:**
- `IZG` - IZ Gateway internal (for system operations)
- `NIH` - National Institutes of Health (for testing)
- `XXA` - Test entity code (for development/testing)

#### 3. Date Component

**Required:** Year and period identifier

**Purpose:** Encodes submission period, must match period parameter

**Separator:** Underscore before date

**Formats:**

**Monthly Format:** `YYYYMMM`
- YYYY: 4-digit year (e.g., 2025, 2026)
- MMM: 3-letter month abbreviation (uppercase)

**Valid Months:**
```
JAN, FEB, MAR, APR, MAY, JUN, JUL, AUG, SEP, OCT, NOV, DEC
```

**Quarterly Format:** `YYYYQ#`
- YYYY: 4-digit year
- Q: Literal letter 'Q'
- #: Quarter number (1-4)

**Validation Rules:**
- Year must be 4 digits
- Year must be reasonable (e.g., 2020-2030 range)
- Month must be valid 3-letter abbreviation
- Quarter must be 1, 2, 3, or 4
- Date format must match frequency keyword:
  - "monthly" requires YYYYMMM format
  - "quarterly" requires YYYYQ# format

**Valid Examples:**
```
2025JAN     # January 2025 (monthly)
2025Q1      # Q1 2025 (quarterly)
2024DEC     # December 2024 (monthly)
2026Q4      # Q4 2026 (quarterly)
```

**Invalid Examples:**
```
25FEB       # 2-digit year
2026XYZ     # Invalid month abbreviation
2026Q5      # Invalid quarter (must be 1-4)
2026Q0      # Invalid quarter (must be 1-4)
2026-FEB    # Hyphen not allowed in date component
202602      # Numeric month not allowed
```

#### 4. Extension

**Required:** File extension indicating file type

**Purpose:** Indicates file format for processing

**Valid Extensions:**
- `.csv` - CSV format (typically for RVR monthly submissions)
- `.zip` - ZIP archive (typically for RI quarterly submissions)

**Rules:**
- Extension must be lowercase in comparison
- No other extensions accepted

## Validation Algorithm

### Step 1: Parse Filename

**Regex Pattern:**
```regex
^(Monthly|Quarterly)([A-Za-z]+)_([A-Z]{2}A)_((\d{4})Q([1-4])|(\d{4})(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC))\.(csv|zip)$
```

**Capture Groups:**
1. Frequency: "Monthly" or "Quarterly"
2. Report Type: Alphanumeric report type abbreviation/name
3. Entity ID: 3-letter code ending in A
4. Full date string
5. Year (quarterly)
6. Quarter number (quarterly)
7. Year (monthly)
8. Month abbreviation (monthly)
9. Extension

**Example Parsing:**
```
Input:  MonthlyFlu_XXA_2026FEB.csv
Group 1: Monthly
Group 2: Flu
Group 3: XXA
Group 7: 2026
Group 8: FEB
Group 9: csv
```

### Step 2: Frequency Validation

**Check frequency keyword presence and case:**
```java
if (!filename.startsWith("Monthly") && !filename.startsWith("Quarterly")) {
    addError("Filename must start with 'Monthly' or 'Quarterly'");
}
```

**Check frequency matches FileType.periodType:**
```java
if ("MONTHLY".equals(periodType) && !filename.startsWith("Monthly")) {
    addError("Filename must start with 'Monthly' for monthly report types");
}
if ("QUARTERLY".equals(periodType) && !filename.startsWith("Quarterly")) {
    addError("Filename must start with 'Quarterly' for quarterly report types");
}
// "BOTH" allows either
```

### Step 3: Report Type Validation

**Extract and validate report type abbreviation:**
```java
String reportTypeAbbrev = extractReportType(filename); // e.g., "Flu", "RI", "AllCOVID"
// Optionally validate against expected report type
// This is primarily informational - validation happens via reportType parameter
```

### Step 4: Entity ID Validation

**Check format:**
```java
if (!entityId.matches("[A-Z]{2}A")) {
    addError("Entity ID must be 3 uppercase letters ending in 'A'");
}
```

**Check match with facilityId (optional):**
```java
if (expectedEntity != null && !entityId.equalsIgnoreCase(expectedEntity)) {
    addError("Entity ID in filename does not match facilityId parameter");
}
```

### Step 5: Date Format Validation

**Check format matches frequency:**
```java
if (filename.startsWith("Monthly") && !dateMatchesMonthlyFormat(date)) {
    addError("Date must be in YYYYMMM format for monthly submissions");
}
if (filename.startsWith("Quarterly") && !dateMatchesQuarterlyFormat(date)) {
    addError("Date must be in YYYYQ# format for quarterly submissions");
}
```

### Step 6: Period Match Validation

**Check date matches period parameter:**
```java
String normalizedPeriod = period.toUpperCase().replace("-", "");
String filenameDate = extractDate(filename);
if (!normalizedPeriod.equals(filenameDate)) {
    addError("Date in filename does not match period parameter");
}
```

## Test Cases

### Valid Filenames

| Filename | Report Type | Period | Entity | Valid? |
|----------|-------------|--------|--------|--------|
| MonthlyFlu_XXA_2026FEB.csv | influenzaVaccination | 2026-FEB | XXA | ✅ |
| QuarterlyRI_XXA_2026Q2.csv | routineImmunization | 2026Q2 | XXA | ✅ |
| MonthlyAllCOVID_XXA_2026FEB.csv | covidAllMonthlyVaccination | 2026-FEB | XXA | ✅ |
| MonthlyRSV_XXA_2026FEB.csv | rsvPrevention | 2026-FEB | XXA | ✅ |
| MonthlyMeasles_XXA_2026FEB.csv | measlesVaccination | 2026-FEB | XXA | ✅ |
| MonthlyFarmerFlu_XXA_2026FEB.csv | farmerFlu | 2026-FEB | XXA | ✅ |
| MonthlyFlu_MAA_2026JAN.csv | influenzaVaccination | 2026-JAN | MAA | ✅ |
| QuarterlyRI_NYA_2025Q1.zip | routineImmunization | 2025Q1 | NYA | ✅ |
| MonthlyRSV_TXA_2025MAR.csv | rsvPrevention | 2025-MAR | TXA | ✅ |

### Invalid Filenames - Missing Frequency

| Filename | Error |
|----------|-------|
| Flu_XXA_2026FEB.csv | Must start with "Monthly" or "Quarterly" |
| RI_XXA_2026Q2.csv | Must start with "Monthly" or "Quarterly" |
| monthly-flu_XXA_2026FEB.csv | Must start with "Monthly" (capital M) |

### Invalid Filenames - Entity ID

| Filename | Error |
|----------|-------|
| MonthlyRI_XYZ_2026FEB.csv | Entity ID 'XYZ' must end with 'A' |
| MonthlyRI_MA_2026FEB.csv | Entity ID must be exactly 3 characters |
| MonthlyRI_MAAA_2026FEB.csv | Entity ID must be exactly 3 characters |
| MonthlyRI_maa_2026FEB.csv | Entity ID must be uppercase |
| MonthlyRI_12A_2026FEB.csv | Entity ID must contain only letters |

### Invalid Filenames - Date Format

| Filename | Error |
|----------|-------|
| MonthlyRI_XXA_26FEB.csv | Year must be 4 digits |
| MonthlyRI_XXA_2026XYZ.csv | Invalid month abbreviation |
| QuarterlyRI_XXA_2026Q5.csv | Quarter must be 1-4 |
| QuarterlyRI_XXA_2026Q0.csv | Quarter must be 1-4 |
| MonthlyRI_XXA_2026-FEB.csv | Date format should be YYYYMMM (no hyphens) |

### Invalid Filenames - Frequency/Date Mismatch

| Filename | Error |
|----------|-------|
| MonthlyRI_XXA_2026Q1.csv | Date must be YYYYMMM format for monthly |
| QuarterlyRI_XXA_2026FEB.csv | Date must be YYYYQ# format for quarterly |
| QuarterlyFlu_XXA_2026DEC.csv | Quarterly keyword but monthly date format |

### Invalid Filenames - Period Mismatch

| Filename | Period Parameter | Error |
|----------|------------------|-------|
| MonthlyRI_XXA_2026JAN.csv | 2026-FEB | Date doesn't match period |
| QuarterlyRI_XXA_2026Q1.csv | 2026Q2 | Date doesn't match period |
| MonthlyFlu_XXA_2025DEC.csv | 2026-DEC | Year doesn't match |

### Invalid Filenames - Extension

| Filename | Error |
|----------|-------|
| MonthlyRI_XXA_2026FEB.txt | Extension must be .csv or .zip |
| MonthlyRI_XXA_2026FEB | Missing file extension |
| MonthlyRI_XXA_2026FEB.pdf | Extension must be .csv or .zip |

## Implementation Notes

### Regex Pattern Components

```regex
^                                    # Start of string
(Monthly|Quarterly)                  # Frequency keyword (capture group 1) - capital first letter
([A-Za-z]+)                         # Report type abbreviation (capture group 2)
_                                    # Underscore separator
([A-Z]{2}A)                         # Entity ID (capture group 3)
_                                    # Underscore separator
(                                    # Date (capture group 4)
  (\d{4})Q([1-4])                   # Quarterly: YYYYQ# (groups 5, 6)
  |                                  # OR
  (\d{4})(JAN|FEB|...|DEC)          # Monthly: YYYYMMM (groups 7, 8)
)
\.                                   # Literal dot
(csv|zip)                           # Extension (capture group 9)
$                                    # End of string
```

### Error Message Guidelines

**Be specific and actionable:**
```
✅ "Entity ID 'XYZ' must be 3 uppercase letters ending in 'A' (e.g., MAA, NYA, CVA)"
❌ "Invalid entity ID"

✅ "Filename must start with 'Monthly' for monthly report types. Current: 'QuarterlyRI_XXA_2026FEB.csv'"
❌ "Invalid frequency"

✅ "Date in filename '2026JAN' does not match period parameter '2026-FEB'"
❌ "Date mismatch"
```

### Case Sensitivity

**Case-sensitive components:**
- Frequency keyword: MUST be "Monthly" or "Quarterly" (capital first letter)
- Entity ID: MUST be uppercase (MAA, not maa)
- Month abbreviations: Uppercase in comparison

**Case-insensitive components:**
- Report type abbreviation: "Flu", "flu", "FLU" all accepted in parsing
- Extension: ".csv", ".CSV", ".Csv" all accepted

### Period Normalization

**API period parameter formats:**
```
YYYY-MMM    → 2026-FEB
YYYYQ#      → 2026Q2
YYYY-MM-DD  → 2026-02-15 (future support)
```

**Filename date formats:**
```
YYYYMMM     → 2026FEB
YYYYQ#      → 2026Q2
```

**Comparison algorithm:**
```java
// Normalize both to same format for comparison
String normalizedPeriod = period.toUpperCase().replace("-", "");
String filenameDate = extractDate(filename).toUpperCase();
return normalizedPeriod.equals(filenameDate);
```

## Edge Cases

### Report Type Abbreviation Mapping

The report type abbreviation in filenames should map to configured fileTypeName:

| Filename Prefix | Maps to fileTypeName | Notes |
|-----------------|---------------------|-------|
| MonthlyFlu | influenzaVaccination | "Flu" abbreviation |
| QuarterlyRI | routineImmunization | "RI" abbreviation |
| MonthlyAllCOVID | covidAllMonthlyVaccination | Full name with "AllCOVID" |
| MonthlyRSV | rsvPrevention | "RSV" abbreviation |
| MonthlyMeasles | measlesVaccination | "Measles" short form |
| MonthlyFarmerFlu | farmerFlu | "FarmerFlu" compound name |

**Validation Approach:**
The report type portion is informational. Primary validation occurs via the `reportType` API parameter, not by parsing the filename prefix. This allows flexibility in abbreviations while maintaining strict validation of entity, date, and period matching.

### Backward Compatibility

**During migration:**
- Validation can be bypassed with `force=true` parameter
- Warnings logged but processing continues
- Allows legacy filenames to be processed

## References

- CDC Data Exchange (DEX) Specification v2.0
- Azure Blob Storage Metadata Requirements
- IZ Gateway ADS Implementation Guide
- HL7 V2 to FHIR Mapping Specification

## Change History

| Date | Version | Changes |
|------|---------|---------|
| 2026-03-16 | 1.0 | Initial specification |
