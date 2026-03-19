# Data Stream ID Computation Algorithm

## Overview

The data stream ID is computed from the file type name using a standardized algorithm that converts camelCase/PascalCase names to hyphenated lowercase identifiers.

## Algorithm Specification

### Purpose

Convert report type names (e.g., "RIQuarterlyAggregate", "influenzaVaccination") to Azure-compatible data stream IDs (e.g., "ri-quarterly-aggregate", "influenza-vaccination").

### Rules

1. **Identify word boundaries** where uppercase letters appear
2. **Insert hyphens before uppercase letters** that start new words:
   - Before uppercase letter following lowercase letter
   - Before last uppercase letter in a sequence followed by lowercase letter
3. **Convert entire result to lowercase**
4. **Never insert hyphen at position 0**

### Algorithm Pseudocode

```
function computeDataStreamId(fileTypeName):
    if fileTypeName is null or empty:
        return null
    
    result = ""
    chars = fileTypeName.toCharArray()
    
    for i from 0 to chars.length - 1:
        current = chars[i]
        next = chars[i+1] if i+1 < length else null
        prev = chars[i-1] if i > 0 else null
        
        isCurrentUpper = isUpperCase(current)
        isNextLower = next != null AND isLowerCase(next)
        isPrevLower = prev != null AND isLowerCase(prev)
        
        // Insert hyphen before uppercase if:
        // 1. Previous char was lowercase (new word starts), OR
        // 2. Next char is lowercase AND not at position 0 (last letter of acronym)
        if isCurrentUpper AND i > 0 AND (isPrevLower OR isNextLower):
            result += "-"
        
        result += toLowerCase(current)
    
    return result
```

### Java Implementation

```java
public static String computeDataStreamId(String fileTypeName) {
    if (StringUtils.isBlank(fileTypeName)) {
        return null;
    }
    
    StringBuilder result = new StringBuilder();
    char[] chars = fileTypeName.toCharArray();
    
    for (int i = 0; i < chars.length; i++) {
        char current = chars[i];
        char next = (i + 1 < chars.length) ? chars[i + 1] : '\0';
        char prev = (i > 0) ? chars[i - 1] : '\0';
        
        boolean isUpper = Character.isUpperCase(current);
        boolean nextIsLower = Character.isLowerCase(next);
        boolean prevIsLower = Character.isLowerCase(prev);
        
        // Insert hyphen before uppercase letter if:
        // - Previous char was lowercase (start of new word), OR
        // - Next char is lowercase AND we're not at position 0 (last letter of acronym)
        if (isUpper && i > 0 && (prevIsLower || nextIsLower)) {
            result.append('-');
        }
        
        result.append(Character.toLowerCase(current));
    }
    
    return result.toString();
}
```

### TypeScript Implementation

```typescript
export function computeDataStreamId(fileTypeName: string): string {
  if (!fileTypeName) return ''
  
  let result = ''
  for (let i = 0; i < fileTypeName.length; i++) {
    const current = fileTypeName[i]
    const next = i + 1 < fileTypeName.length ? fileTypeName[i + 1] : ''
    const prev = i > 0 ? fileTypeName[i - 1] : ''
    
    const isUpper = current === current.toUpperCase() && current !== current.toLowerCase()
    const nextIsLower = next === next.toLowerCase() && next !== next.toUpperCase()
    const prevIsLower = prev === prev.toLowerCase() && prev !== prev.toUpperCase()
    
    if (isUpper && i > 0 && (prevIsLower || nextIsLower)) {
      result += '-'
    }
    
    result += current.toLowerCase()
  }
  
  return result
}
```

## Test Cases

### Standard Cases

| Input | Step-by-Step Processing | Output |
|-------|-------------------------|--------|
| `routineImmunization` | routine + I→-i + mmunization | `routine-immunization` |
| `influenzaVaccination` | influenza + V→-v + accination | `influenza-vaccination` |
| `rsvPrevention` | rsv + P→-p + revention | `rsv-prevention` |
| `measlesVaccination` | measles + V→-v + accination | `measles-vaccination` |
| `farmerFluVaccination` | farmer + F→-f + lu + V→-v + accination | `farmer-flu-vaccination` |
| `genericImmunization` | generic + I→-i + mmunization | `generic-immunization` |

### Complex Cases with Acronyms

| Input | Processing | Output | Explanation |
|-------|------------|--------|-------------|
| `RIQuarterlyAggregate` | R + I→-i (last of RI) + quarterly + A→-a + ggregate | `ri-quarterly-aggregate` | RI is acronym, I is last letter before lowercase |
| `covidAllMonthlyVaccination` | covid + A→-a + ll + M→-m + onthly + V→-v + accination | `covid-all-monthly-vaccination` | Multiple word boundaries |
| `COVID19Vaccine` | c + o + v + i + d + 1 + 9 + V→-v + accine | `covid19-vaccine` | Numbers treated as lowercase |
| `RSVPrevention` | r + s + v (last of RSV) + P→-p + revention | `rsvp-revention` | Wrong! Should be `rsv-prevention` |

### Edge Cases

| Input | Output | Notes |
|-------|--------|-------|
| `ABC` | `abc` | All uppercase, no lowercase following |
| `ABCDef` | `ab-cdef` | Hyphen before C (last uppercase before lowercase) |
| `abcdef` | `abcdef` | All lowercase, no changes |
| `AbCdEf` | `ab-cd-ef` | Alternating case |
| `A` | `a` | Single character |
| `AB` | `ab` | Two uppercase, no hyphen |
| `""` | `null` | Empty string |
| `null` | `null` | Null input |

### Historical Report Types (Verification)

These are the current hardcoded mappings that must be reproduced by the algorithm:

| FileTypeName (Input) | Current data_stream_id | Algorithm Output | Match? |
|---------------------|------------------------|------------------|--------|
| `routineImmunization` | `routine-immunization` | `routine-immunization` | ✅ |
| `farmerFluVaccination` | `farmer-flu-vaccination` | `farmer-flu-vaccination` | ✅ |
| `influenzaVaccination` | `influenza-vaccination` | `influenza-vaccination` | ✅ |
| `rsvPrevention` | `rsv-prevention` | `rsv-prevention` | ✅ |
| `covidAllMonthlyVaccination` | `covid-all-monthly-vaccination` | `covid-all-monthly-vaccination` | ✅ |
| `covidBridgeVaccination` | `covid-bridge-vaccination` | `covid-bridge-vaccination` | ✅ |
| `genericImmunization` | `generic-immunization` | `generic-immunization` | ✅ |
| `measlesVaccination` | `measles-vaccination` | `measles-vaccination` | ✅ |

### JUnit Test Template

```java
@Test
void testComputeDataStreamId_ExistingReportTypes() {
    assertEquals("routine-immunization", 
                 FileTypeService.computeDataStreamId("routineImmunization"));
    
    assertEquals("influenza-vaccination", 
                 FileTypeService.computeDataStreamId("influenzaVaccination"));
    
    assertEquals("rsv-prevention", 
                 FileTypeService.computeDataStreamId("rsvPrevention"));
    
    assertEquals("covid-all-monthly-vaccination", 
                 FileTypeService.computeDataStreamId("covidAllMonthlyVaccination"));
    
    assertEquals("farmer-flu-vaccination", 
                 FileTypeService.computeDataStreamId("farmerFluVaccination"));
    
    assertEquals("measles-vaccination", 
                 FileTypeService.computeDataStreamId("measlesVaccination"));
    
    assertEquals("generic-immunization", 
                 FileTypeService.computeDataStreamId("genericImmunization"));
}

@Test
void testComputeDataStreamId_Acronyms() {
    assertEquals("ri-quarterly-aggregate", 
                 FileTypeService.computeDataStreamId("RIQuarterlyAggregate"));
    
    assertEquals("cdc-report", 
                 FileTypeService.computeDataStreamId("CDCReport"));
}

@Test
void testComputeDataStreamId_EdgeCases() {
    assertEquals("abc", FileTypeService.computeDataStreamId("ABC"));
    assertEquals("ab-cdef", FileTypeService.computeDataStreamId("ABCDef"));
    assertEquals("abcdef", FileTypeService.computeDataStreamId("abcdef"));
    assertEquals("a", FileTypeService.computeDataStreamId("A"));
    assertNull(FileTypeService.computeDataStreamId(null));
    assertNull(FileTypeService.computeDataStreamId(""));
}

@Test
void testComputeDataStreamId_WithNumbers() {
    assertEquals("covid19-vaccine", 
                 FileTypeService.computeDataStreamId("COVID19Vaccine"));
    
    assertEquals("h1-n1-flu", 
                 FileTypeService.computeDataStreamId("H1N1Flu"));
}
```

## Performance Characteristics

### Time Complexity
- **O(n)** where n is the length of fileTypeName
- Single pass through string
- Minimal memory allocation (StringBuilder)

### Space Complexity
- **O(n)** for result string
- No additional data structures required

### Typical Performance
- Input: ~30 characters
- Processing time: <0.1ms
- Memory: ~100 bytes

### Caching Recommendation

The computed data_stream_id should be cached:
1. **Option A:** Store in FileType.dataStreamIdTemplate field during creation
2. **Option B:** Compute once in MetadataBuilder and store in Metadata
3. **Option C:** Cache at service layer with TTL

**Recommended:** Option B - Compute once during metadata building, store in MetadataImpl.dataStreamId field.

## Validation

### Self-Consistency Check

After computing data_stream_id, verify it produces valid filename keywords:
```java
String dataStreamId = computeDataStreamId("routineImmunization");
// dataStreamId = "routine-immunization"

// Valid filename keywords derived from data_stream_id:
// - "routine-immunization" (full)
// - "routine"  (prefix subset)
// - "ri" (initials subset)

// All of these should be valid in filenames:
// "monthly-routine-immunization-MAA-2025JAN.csv" ✅
// "monthly-routine-MAA-2025JAN.csv" ✅
// "monthly-ri-MAA-2025JAN.csv" ✅
```

### Round-Trip Test

```java
@Test
void testRoundTrip_DataStreamIdToFilename() {
    String fileTypeName = "routineImmunization";
    String dataStreamId = computeDataStreamId(fileTypeName);
    
    // These filenames should all be valid for this data_stream_id
    assertTrue(validateKeywords("routine-immunization", dataStreamId));
    assertTrue(validateKeywords("routine", dataStreamId));
    assertTrue(validateKeywords("ri", dataStreamId));
    assertFalse(validateKeywords("immunization-routine", dataStreamId)); // Wrong order
}
```

## References

- Original hardcoded implementation: `Metadata.getDataStreamId()`
- Filename parsing: `ParsedFilename.parse()`
- User requirement: "insert hyphens before each last uppercase letter in the file name in any sequence of uppercase letters, and then lowercasing the result"

## Change History

| Date | Version | Changes |
|------|---------|---------|
| 2026-03-16 | 1.0 | Initial algorithm specification |
