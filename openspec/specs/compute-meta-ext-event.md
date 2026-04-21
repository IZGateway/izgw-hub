# Spec: `computeMetaExtEvent()` and `computeMetaExtEventType()`

**Component:** `FileTypeMetadataUtil` (static methods)  
**Implemented in:** `gov.cdc.izgateway.ads.util.FileTypeMetadataUtil`  
**Related task:** Task 1 (MetadataBuilder utilities), Task 7 (Tests)

---

## Purpose

Compute the two ADS metadata event fields from a `fileTypeName` string:

| ADS field | Computed by | Typical value |
|---|---|---|
| `meta_ext_event` | `computeMetaExtEvent(fileTypeName)` | Same as `fileTypeName`, with one special case |
| `meta_ext_event_type` | `computeMetaExtEventType(fileTypeName)` | Always identical to `fileTypeName` |

These values appear in the ADS metadata envelope sent to CDC DEX endpoints and
must match the values previously hardcoded in the legacy `Metadata.getMetaExtEvent()`
switch statement.

---

## `computeMetaExtEvent(fileTypeName)`

### Rules

| Priority | Condition | Return value |
|---|---|---|
| 1 | `fileTypeName` is `null` or blank | `null` |
| 2 | `fileTypeName` equals `"farmerFlu"` (case-insensitive) | `"farmerFluVaccination"` |
| 3 | _(none of the above)_ | `fileTypeName` unchanged |

### Special Case: `farmerFlu` → `farmerFluVaccination`

The legacy hardcoded mapping sent `"farmerFluVaccination"` as `meta_ext_event`
even when the file type was named `"farmerFlu"`.  This discrepancy exists in
the upstream CDC system and **must be preserved** for backward compatibility.
Changing it would cause the submitted metadata to be rejected or misrouted.

### Pseudocode

```
function computeMetaExtEvent(fileTypeName):
    if fileTypeName is null or blank:
        return null

    if equalsIgnoreCase(fileTypeName, "farmerFlu"):
        return "farmerFluVaccination"

    return fileTypeName
```

### Examples

| Input (`fileTypeName`) | Output (`meta_ext_event`) | Notes |
|---|---|---|
| `routineImmunization` | `routineImmunization` | Identity (Rule 3) |
| `influenzaVaccination` | `influenzaVaccination` | Identity (Rule 3) |
| `farmerFlu` | `farmerFluVaccination` | **Special case** (Rule 2) |
| `farmerFluVaccination` | `farmerFluVaccination` | Identity — variant name maps to itself |
| `covidAllMonthlyVaccination` | `covidAllMonthlyVaccination` | Identity |
| `covidBridgeVaccination` | `covidBridgeVaccination` | Identity |
| `rsvPrevention` | `rsvPrevention` | Identity |
| `measlesVaccination` | `measlesVaccination` | Identity |
| `genericImmunization` | `genericImmunization` | Identity |
| `null` | `null` | Edge case |
| `""` | `null` | Edge case |

---

## `computeMetaExtEventType(fileTypeName)`

### Rules

| Priority | Condition | Return value |
|---|---|---|
| 1 | `fileTypeName` is `null` or blank | `null` |
| 2 | _(any non-blank value)_ | `fileTypeName` unchanged |

This function is intentionally simple: it always returns the raw `fileTypeName`.
It exists as a named method (rather than a direct assignment) so that the
computation is symmetric with `computeMetaExtEvent()` and can be overridden
consistently in future versions.

### Pseudocode

```
function computeMetaExtEventType(fileTypeName):
    if fileTypeName is null or blank:
        return null

    return fileTypeName
```

### Examples

| Input (`fileTypeName`) | Output (`meta_ext_event_type`) |
|---|---|
| `routineImmunization` | `routineImmunization` |
| `farmerFlu` | `farmerFlu` |  
| `genericImmunization` | `genericImmunization` |
| `null` | `null` |
| `""` | `null` |

> **Note:** `computeMetaExtEventType("farmerFlu")` returns `"farmerFlu"`, NOT
> `"farmerFluVaccination"`.  Only `computeMetaExtEvent` applies the special case.

---

## How These Fields Map to ADS Metadata

```
FileType.fileTypeName = "farmerFlu"
         │
         ├── computeMetaExtEvent()     → meta.metaExtEvent      = "farmerFluVaccination"
         └── computeMetaExtEventType() → meta.metaExtEventType  = "farmerFlu"
```

The `metaExtEvent` is the CDC-facing event name used for routing.  The
`metaExtEventType` is the internal classification name used for audit and
reporting.

---

## Test Cases

### Requirement: computeMetaExtEvent Null/Blank Returns Null

`computeMetaExtEvent` SHALL return `null` for null or blank fileTypeName.

#### Scenario: Null input returns null
- GIVEN a null fileTypeName
- WHEN `computeMetaExtEvent` is called
- THEN the return value is `null`

#### Scenario: Empty string returns null
- GIVEN a fileTypeName of `""`
- WHEN `computeMetaExtEvent` is called
- THEN the return value is `null`

---

### Requirement: computeMetaExtEvent farmerFlu Special Case

`computeMetaExtEvent` SHALL return `"farmerFluVaccination"` for any case-insensitive variant of `"farmerFlu"`.

#### Scenario: farmerFlu returns farmerFluVaccination
- GIVEN a fileTypeName of `"farmerFlu"`
- WHEN `computeMetaExtEvent` is called
- THEN the return value is `"farmerFluVaccination"`

#### Scenario: FarmerFlu returns farmerFluVaccination
- GIVEN a fileTypeName of `"FarmerFlu"` (mixed case)
- WHEN `computeMetaExtEvent` is called
- THEN the return value is `"farmerFluVaccination"`

#### Scenario: FARMERFLU returns farmerFluVaccination
- GIVEN a fileTypeName of `"FARMERFLU"` (all uppercase)
- WHEN `computeMetaExtEvent` is called
- THEN the return value is `"farmerFluVaccination"`

#### Scenario: farmerFluVaccination returns farmerFluVaccination
- GIVEN a fileTypeName of `"farmerFluVaccination"` (already the full name; not equal to `"farmerFlu"`)
- WHEN `computeMetaExtEvent` is called
- THEN the return value is `"farmerFluVaccination"`

---

### Requirement: computeMetaExtEvent Identity for All Other Types

`computeMetaExtEvent` SHALL return fileTypeName unchanged for all non-null, non-blank values that are not a case-insensitive variant of `"farmerFlu"`.

#### Scenario: routineImmunization returns unchanged
- GIVEN a fileTypeName of `"routineImmunization"`
- WHEN `computeMetaExtEvent` is called
- THEN the return value is `"routineImmunization"`

#### Scenario: influenzaVaccination returns unchanged
- GIVEN a fileTypeName of `"influenzaVaccination"`
- WHEN `computeMetaExtEvent` is called
- THEN the return value is `"influenzaVaccination"`

#### Scenario: covidAllMonthlyVaccination returns unchanged
- GIVEN a fileTypeName of `"covidAllMonthlyVaccination"`
- WHEN `computeMetaExtEvent` is called
- THEN the return value is `"covidAllMonthlyVaccination"`

#### Scenario: covidBridgeVaccination returns unchanged
- GIVEN a fileTypeName of `"covidBridgeVaccination"`
- WHEN `computeMetaExtEvent` is called
- THEN the return value is `"covidBridgeVaccination"`

#### Scenario: rsvPrevention returns unchanged
- GIVEN a fileTypeName of `"rsvPrevention"`
- WHEN `computeMetaExtEvent` is called
- THEN the return value is `"rsvPrevention"`

#### Scenario: measlesVaccination returns unchanged
- GIVEN a fileTypeName of `"measlesVaccination"`
- WHEN `computeMetaExtEvent` is called
- THEN the return value is `"measlesVaccination"`

#### Scenario: genericImmunization returns unchanged
- GIVEN a fileTypeName of `"genericImmunization"`
- WHEN `computeMetaExtEvent` is called
- THEN the return value is `"genericImmunization"`

---

### Requirement: computeMetaExtEventType Always Returns Identity

`computeMetaExtEventType` SHALL return fileTypeName unchanged for non-null, non-blank input.
`computeMetaExtEventType` SHALL return `null` for null or blank input.

#### Scenario: routineImmunization returns unchanged
- GIVEN a fileTypeName of `"routineImmunization"`
- WHEN `computeMetaExtEventType` is called
- THEN the return value is `"routineImmunization"`

#### Scenario: farmerFlu returns farmerFlu not farmerFluVaccination
- GIVEN a fileTypeName of `"farmerFlu"`
- WHEN `computeMetaExtEventType` is called
- THEN the return value is `"farmerFlu"`
- AND the return value is NOT `"farmerFluVaccination"`

#### Scenario: genericImmunization returns unchanged
- GIVEN a fileTypeName of `"genericImmunization"`
- WHEN `computeMetaExtEventType` is called
- THEN the return value is `"genericImmunization"`

#### Scenario: Null input returns null
- GIVEN a null fileTypeName
- WHEN `computeMetaExtEventType` is called
- THEN the return value is `null`

#### Scenario: Empty string returns null
- GIVEN a fileTypeName of `""`
- WHEN `computeMetaExtEventType` is called
- THEN the return value is `null`

---

## Implementation Location

```
izgw-hub/src/main/java/gov/cdc/izgateway/ads/util/FileTypeMetadataUtil.java
  ├── public static String computeMetaExtEvent(String fileTypeName)
  └── public static String computeMetaExtEventType(String fileTypeName)

izgw-hub/src/test/java/test/gov/cdc/izgateway/ads/util/FileTypeMetadataUtilTests.java
  └── computeMetaExtEvent_* and computeMetaExtEventType_* test methods
```
