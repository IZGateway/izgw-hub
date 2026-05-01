# Spec: `computeDataStreamId(fileTypeName)`

**Component:** `IAccessControlService` (static method, `izgw-core`)  
**Implemented in:** `gov.cdc.izgateway.service.IAccessControlService`  
**Related task:** Task 2 (AccessControlService), Task 8 (Tests)

---

## Purpose

Convert a camelCase or PascalCase `fileTypeName` string into a kebab-case
`data_stream_id` suitable for use in ADS metadata submitted to CDC endpoints.
The algorithm must handle both regular camelCase words **and** acronym runs
(consecutive uppercase letters) without splitting each uppercase letter into its
own segment.

---

## Algorithm

### Step-by-Step Processing

Iterate over each character at index `i`.  Determine whether to insert a hyphen
**before** the current character using the following rules:

| Condition | Insert hyphen? |
|---|---|
| `i == 0` | No — never prefix the first character |
| `c` is uppercase AND `prev` is lowercase or digit | **Yes** — lowercase→uppercase word boundary |
| `c` is uppercase AND `prev` is uppercase AND `next` is lowercase | **Yes** — end of acronym run; `c` starts the next word |
| Any other case | No |

After the hyphen decision, append `Character.toLowerCase(c)` to the result.

### Pseudocode

```
function computeDataStreamId(fileTypeName):
    if fileTypeName is null or empty:
        return "generic-immunization"

    result = ""
    for i = 0 to len(fileTypeName) - 1:
        c    = fileTypeName[i]
        prev = fileTypeName[i-1]  (or NUL if i == 0)
        next = fileTypeName[i+1]  (or NUL if i == last)

        if i > 0 AND isUpperCase(c):
            prevLowerOrDigit = isLowerCase(prev) OR isDigit(prev)
            nextLower        = isLowerCase(next)
            if prevLowerOrDigit OR (isUpperCase(prev) AND nextLower):
                result += '-'

        result += toLowerCase(c)

    return result
```

### Why These Two Rules?

**Rule 1 — `prevLowerOrDigit`:**  
Handles ordinary camelCase transitions, e.g. `farmerFlu`:

```
farmer → 'F' at i=6: prev='r' (lower) → insert '-'
       → result: "farmer-flu"
```

**Rule 2 — `prevIsUpper AND nextIsLower`:**  
Handles the word boundary at the end of an acronym run, e.g. `ABCReport`:

```
A → i=0: no hyphen       → "a"
B → i=1: prev='A'(upper), next='C'(upper) → next not lower → no hyphen → "ab"
C → i=2: prev='B'(upper), next='R'(upper) → next not lower → no hyphen → "abc"
R → i=3: prev='C'(upper), next='e'(lower) → Rule 2 → insert '-' → "abc-r"
e → i=4: lower → no action → "abc-re"
...
Final: "abc-report"
```

Without Rule 2 (old behaviour) the result would be `a-b-c-report`, which is
incorrect.

---

## Production Examples

| Input (`fileTypeName`) | Processing notes | Output (`data_stream_id`) |
|---|---|---|
| `routineImmunization` | `I` at 7: prev=`e`(lower) → hyphen | `routine-immunization` |
| `influenzaVaccination` | `V` at 9: prev=`a`(lower) → hyphen | `influenza-vaccination` |
| `farmerFlu` | `F` at 6: prev=`r`(lower) → hyphen | `farmer-flu` |
| `farmerFluVaccination` | two transitions | `farmer-flu-vaccination` |
| `covidAllMonthlyVaccination` | three transitions | `covid-all-monthly-vaccination` |
| `covidBridgeVaccination` | two transitions | `covid-bridge-vaccination` |
| `rsvPrevention` | `P` at 3: prev=`v`(lower) → hyphen | `rsv-prevention` |
| `measlesVaccination` | `V` at 7: prev=`s`(lower) → hyphen | `measles-vaccination` |
| `genericImmunization` | `I` at 7: prev=`c`(lower) → hyphen | `generic-immunization` |

---

## Acronym Examples

| Input | Output | Notes |
|---|---|---|
| `RIQuarterlyAggregate` | `ri-quarterly-aggregate` | `RI` stays together; `-` before `Q` (Rule 2: prev=`I`/upper, next=`u`/lower) |
| `ABCReport` | `abc-report` | `ABC` stays together; `-` before `R` (Rule 2) |
| `COVID19Vaccine` | `covid19-vaccine` | `D` prev=`I`/upper, next=`1`/digit → no hyphen; `V` prev=`9`/digit (Rule 1) → hyphen |
| `ABC` | `abc` | All uppercase, no following lowercase → no hyphens |
| `A` | `a` | Single character |
| `a` | `a` | Single lowercase character |

---

## Edge Cases

| Input | Output | Explanation |
|---|---|---|
| `null` | `generic-immunization` | Documented default for null input |
| `""` (empty) | `generic-immunization` | Documented default for empty input |
| `allowercase` | `allowercase` | No uppercase characters → no hyphens |
| `COVID19Vaccine` | `covid19-vaccine` | Digit acts like lowercase for Rule 1 (digits trigger hyphen before next upper) |
| `v2Report` | `v2-report` | Digit→uppercase triggers Rule 1 |

---

## Test Cases

The following requirements are the normative acceptance criteria.  All scenarios must pass.

### Requirement: Null or Empty Input Default

`computeDataStreamId` SHALL return `"generic-immunization"` for null or empty fileTypeName.

#### Scenario: Null input returns generic-immunization
- GIVEN a null fileTypeName
- WHEN `computeDataStreamId` is called
- THEN the return value is `"generic-immunization"`

#### Scenario: Empty string returns generic-immunization
- GIVEN a fileTypeName of `""`
- WHEN `computeDataStreamId` is called
- THEN the return value is `"generic-immunization"`

---

### Requirement: camelCase Word Boundary Hyphenation

The system SHALL insert a hyphen before an uppercase letter that follows a lowercase letter or digit (Rule 1).

#### Scenario: routineImmunization produces routine-immunization
- GIVEN a fileTypeName of `"routineImmunization"` (`I` at index 7: prev=`e`, lower)
- WHEN `computeDataStreamId` is called
- THEN the return value is `"routine-immunization"`

#### Scenario: influenzaVaccination produces influenza-vaccination
- GIVEN a fileTypeName of `"influenzaVaccination"` (`V` at index 9: prev=`a`, lower)
- WHEN `computeDataStreamId` is called
- THEN the return value is `"influenza-vaccination"`

#### Scenario: farmerFlu produces farmer-flu
- GIVEN a fileTypeName of `"farmerFlu"` (`F` at index 6: prev=`r`, lower)
- WHEN `computeDataStreamId` is called
- THEN the return value is `"farmer-flu"`

#### Scenario: farmerFluVaccination produces farmer-flu-vaccination
- GIVEN a fileTypeName of `"farmerFluVaccination"` (two camelCase transitions)
- WHEN `computeDataStreamId` is called
- THEN the return value is `"farmer-flu-vaccination"`

#### Scenario: covidAllMonthlyVaccination produces covid-all-monthly-vaccination
- GIVEN a fileTypeName of `"covidAllMonthlyVaccination"` (three transitions)
- WHEN `computeDataStreamId` is called
- THEN the return value is `"covid-all-monthly-vaccination"`

#### Scenario: covidBridgeVaccination produces covid-bridge-vaccination
- GIVEN a fileTypeName of `"covidBridgeVaccination"` (two transitions)
- WHEN `computeDataStreamId` is called
- THEN the return value is `"covid-bridge-vaccination"`

#### Scenario: rsvPrevention produces rsv-prevention
- GIVEN a fileTypeName of `"rsvPrevention"` (`P` at index 3: prev=`v`, lower)
- WHEN `computeDataStreamId` is called
- THEN the return value is `"rsv-prevention"`

#### Scenario: measlesVaccination produces measles-vaccination
- GIVEN a fileTypeName of `"measlesVaccination"` (`V` at index 7: prev=`s`, lower)
- WHEN `computeDataStreamId` is called
- THEN the return value is `"measles-vaccination"`

#### Scenario: genericImmunization produces generic-immunization
- GIVEN a fileTypeName of `"genericImmunization"` (`I` at index 7: prev=`c`, lower)
- WHEN `computeDataStreamId` is called
- THEN the return value is `"generic-immunization"`

---

### Requirement: Acronym Run Boundary Hyphenation

The system SHALL insert a hyphen before an uppercase letter that follows an uppercase letter AND is itself followed by a lowercase letter, keeping the acronym intact (Rule 2).

#### Scenario: RIQuarterlyAggregate produces ri-quarterly-aggregate
- GIVEN a fileTypeName of `"RIQuarterlyAggregate"` (`RI` stays together; `-` before `Q`: prev=`I`/upper, next=`u`/lower)
- WHEN `computeDataStreamId` is called
- THEN the return value is `"ri-quarterly-aggregate"`

#### Scenario: ABCReport produces abc-report
- GIVEN a fileTypeName of `"ABCReport"` (`ABC` stays together; `-` before `R`: prev=`C`/upper, next=`e`/lower)
- WHEN `computeDataStreamId` is called
- THEN the return value is `"abc-report"`

---

### Requirement: All-Uppercase Strings Without Lowercase Successor

The system SHALL produce no hyphens for fully uppercase strings with no following lowercase character.

#### Scenario: ABC produces abc
- GIVEN a fileTypeName of `"ABC"` (all uppercase, no following lowercase)
- WHEN `computeDataStreamId` is called
- THEN the return value is `"abc"`

#### Scenario: A produces a
- GIVEN a fileTypeName of `"A"` (single uppercase character)
- WHEN `computeDataStreamId` is called
- THEN the return value is `"a"`

---

### Requirement: All-Lowercase Strings Pass Through

The system SHALL produce no hyphens for strings with no uppercase characters.

#### Scenario: allowercase passes through unchanged
- GIVEN a fileTypeName of `"allowercase"` (all lowercase, no uppercase)
- WHEN `computeDataStreamId` is called
- THEN the return value is `"allowercase"`

#### Scenario: Single lowercase character passes through
- GIVEN a fileTypeName of `"a"`
- WHEN `computeDataStreamId` is called
- THEN the return value is `"a"`

---

### Requirement: Digit Treated as Lowercase for Hyphenation Boundary

The system SHALL treat digit characters as lowercase when determining Rule 1 boundaries, so a digit followed by an uppercase letter triggers a hyphen insertion.

#### Scenario: COVID19Vaccine produces covid19-vaccine
- GIVEN a fileTypeName of `"COVID19Vaccine"` (`V` follows digit `9`: Rule 1 applies)
- WHEN `computeDataStreamId` is called
- THEN the return value is `"covid19-vaccine"`

#### Scenario: v2Report produces v2-report
- GIVEN a fileTypeName of `"v2Report"` (`R` follows digit `2`: Rule 1 applies)
- WHEN `computeDataStreamId` is called
- THEN the return value is `"v2-report"`

---

## Implementation Location

```
izgw-core/src/main/java/gov/cdc/izgateway/service/IAccessControlService.java
  └── static String computeDataStreamId(String fileTypeName)

izgw-hub/src/test/java/gov/cdc/izgateway/ads/ComputeDataStreamIdTests.java
  └── parameterised JUnit 5 tests covering all cases above
```

---

## Change History

| Date | Change |
|---|---|
| 2026-03-16 | Initial algorithm: hyphen before every uppercase (simple) |
| 2026-03-27 | Corrected to acronym-aware algorithm (Rule 2 added); `RIQuarterlyAggregate` → `ri-quarterly-aggregate`, `ABCReport` → `abc-report`, `COVID19Vaccine` → `covid19-vaccine` |
