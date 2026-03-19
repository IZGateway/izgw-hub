# ADDENDUM: Actual Filename Patterns and Existing Integration

**Date:** March 16, 2026  
**Status:** Important Clarifications

---

## 🔍 Actual Production Filename Patterns

### Discovered Patterns

After reviewing production code, the **actual** filename pattern used is:

```
[Frequency][ReportType]_[Entity]_[Date].[extension]
```

**NOT the originally specified:**
```
[frequency-keywords]-[entity]-[date].[extension]  ❌ INCORRECT
```

### Real Production Examples

| Filename | Report Type | Period | Entity |
|----------|-------------|--------|--------|
| `MonthlyAllCOVID_XXA_2026FEB.csv` | covidAllMonthlyVaccination | 2026-FEB | XXA |
| `MonthlyFlu_XXA_2026FEB.csv` | influenzaVaccination | 2026-FEB | XXA |
| `MonthlyRSV_XXA_2026FEB.csv` | rsvPrevention | 2026-FEB | XXA |
| `MonthlyMeasles_XXA_2026FEB.csv` | measlesVaccination | 2026-FEB | XXA |
| `MonthlyFarmerFlu_XXA_2026FEB.csv` | farmerFlu | 2026-FEB | XXA |
| `QuarterlyRI_XXA_2026Q2.csv` | routineImmunization | 2026Q2 | XXA |

### Key Differences from Initial Spec

| Aspect | Initial Spec (WRONG) | Actual Pattern (CORRECT) |
|--------|---------------------|-------------------------|
| Separator | Hyphens (`-`) | Underscores (`_`) |
| Frequency Case | lowercase ("monthly") | PascalCase ("Monthly") |
| Report Type | Hyphenated keywords | Concatenated abbreviation |
| Structure | `monthly-flu-vaccination-MAA-2025JAN.csv` | `MonthlyFlu_XXA_2026FEB.csv` |

---

## 📦 Existing FileType Infrastructure

### What Already Exists

**IMPORTANT DISCOVERY:** FileType is already integrated into access control!

**File:** `gov.cdc.izgateway.hub.service.accesscontrol.NewModelHelper`

```java
@Slf4j
class NewModelHelper implements AccessControlModelHelper {
    private Map<String, FileType> fileTypeCache = Collections.emptyMap();
    
    @Override
    public void refresh() {
        // FileType is already cached!
        fileTypeCache = refreshCache(
            accessControlService.fileTypeRepository, 
            FileType::getFileTypeName
        );
    }
    
    @Override
    public Set<String> getEventTypes() {
        // Returns file type names for validation
        return fileTypeCache.keySet();
    }
}
```

**Used By:**
- `AccessControlService.getEventTypes()` - Returns valid event types from FileType cache
- `ADSController.normalizeReportType()` - Validates reportType against event types

### Integration Points

**Current Flow:**
```
ADSController.postADSFile()
    ↓
normalizeReportType(reportType)
    ↓
accessControls.getEventTypes()  ← Returns fileTypeCache.keySet()
    ↓
Validates reportType in list
```

**Enhanced Flow (This Feature):**
```
ADSController.postADSFile()
    ↓
normalizeReportType(reportType)
    ↓
FileTypeService.getFileTypeByReportType(reportType)
    ↓
Returns FileType with metadata fields
    ↓
Apply to MetadataBuilder
```

### Design Implications

**Option A: Extend NewModelHelper** (CHOSEN ✅)
- Add metadata-related methods to existing cache
- Reuse existing refresh mechanism (every 5 minutes)
- Single cache for both access control and metadata
- No new service infrastructure needed
- AccessControlService already injected into ADSController

**Option B: Create Separate FileTypeService** (Rejected)
- Dedicated service for ADS metadata operations
- Independent caching with Caffeine
- Clear separation of concerns
- More complexity and overhead

**Final Decision:** Use Option A (enhance AccessControlService) because:
- ✅ Reuses existing cache infrastructure (no overhead)
- ✅ Already refreshed every 5 minutes via scheduled executor
- ✅ Already injected into ADSController via config.getAccessControls()
- ✅ Simpler implementation (fewer files to create)
- ✅ Consistent with existing patterns
- ✅ Saves 6.5 hours of development effort

---

## 📝 Corrected Validation Algorithm

### Updated Regex Pattern

```regex
^(Monthly|Quarterly)([A-Za-z]+)_([A-Z]{2}A)_((\d{4})Q([1-4])|(\d{4})(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC))\.(csv|zip)$
```

### Updated Validation Steps

1. **Parse filename** using corrected regex
2. **Validate frequency** - Must be "Monthly" or "Quarterly" (capital M/Q)
3. **Extract report type** - Alphanumeric portion after frequency (informational only)
4. **Validate entity** - Must be 3 uppercase letters ending in 'A'
5. **Validate date format** - Must match frequency (YYYYMMM for Monthly, YYYYQ# for Quarterly)
6. **Validate period match** - Date in filename must match period parameter

### What Was Removed

**Keyword Validation (NOT NEEDED):**
- Original spec included validation of keywords against data_stream_id
- Actual pattern doesn't include data_stream_id keywords in filename
- Report type abbreviation is freeform (Flu, RI, AllCOVID, RSV, Measles, FarmerFlu)
- Validation happens via reportType API parameter, not filename parsing

---

## 🔄 Report Type Abbreviation Mapping

### Filename Prefix to FileTypeName

| Filename Prefix | fileTypeName | metaExtEvent | data_stream_id |
|-----------------|--------------|--------------|----------------|
| `MonthlyFlu` | influenzaVaccination | influenzaVaccination | influenza-vaccination |
| `QuarterlyRI` | routineImmunization | routineImmunization | routine-immunization |
| `MonthlyAllCOVID` | covidAllMonthlyVaccination | covidAllMonthlyVaccination | covid-all-monthly-vaccination |
| `MonthlyRSV` | rsvPrevention | rsvPrevention | rsv-prevention |
| `MonthlyMeasles` | measlesVaccination | measlesVaccination | measles-vaccination |
| `MonthlyFarmerFlu` | farmerFlu | farmerFluVaccination | farmer-flu-vaccination |

**Note:** The abbreviation in the filename (Flu, RI, RSV) is for human readability. The authoritative report type comes from the `reportType` API parameter, not the filename. The filename is validated for structure (frequency, entity, date, extension) but not for report type matching.

---

## ✅ Updated Document Status

### Documents Corrected ✅
- [x] **specs/filename-validation-spec.md** - Updated with actual pattern
- [x] **design.md** - Updated FilenameValidator implementation
- [x] **proposal.md** - Updated test case examples
- [x] **tasks.md** - Updated Task 4 and Task 13 with correct test cases
- [x] **QUICK_REFERENCE.md** - Updated filename format and examples
- [x] **IMPLEMENTATION_SUMMARY.md** - Updated example validation

### Key Corrections Made

1. **Pattern Structure:**
   - Changed from: `[frequency-keywords]-[entity]-[date].[ext]`
   - Changed to: `[Frequency][ReportType]_[Entity]_[Date].[ext]`

2. **Separators:**
   - Changed from: Hyphens throughout
   - Changed to: Underscores between components, no separator between Frequency and ReportType

3. **Case Sensitivity:**
   - Changed from: lowercase "monthly"
   - Changed to: PascalCase "Monthly"

4. **Keyword Validation:**
   - Removed: validateKeywords() method
   - Reason: Not applicable to actual pattern

5. **Test Cases:**
   - Updated all 50+ test cases with correct pattern
   - Examples now use MonthlyFlu_XXA_2026FEB.csv format

---

## 🎯 Impact on Implementation

### Simplified Validation

The actual pattern is **simpler** than originally specified:
- ✅ No keyword matching needed
- ✅ No data_stream_id comparison in filename
- ✅ Fewer validation rules to implement
- ✅ Faster validation (less regex complexity)

### Implementation Effort Reduction

| Component | Original | With AccessControlService | Savings |
|-----------|----------|---------------------------|---------|
| Task 3: Service Layer | 4h (new FileTypeService) | 3h (enhance existing) | -1h |
| Task 4: FilenameValidator | 4h | 3h (simplified pattern) | -1h |
| Task 5: MetadataBuilder | 3h | 2h (simpler injection) | -1h |
| Task 7: ADSController | 2h | 1h (no new injection) | -1h |
| Task 12: Service Tests | 2h | 2h (test enhancements) | 0h |
| Task 13: Validator Tests | 3h | 2.5h (fewer cases) | -0.5h |
| Task 15: Controller Tests | 1.5h | 1h (less mocking) | -0.5h |
| **Original Total** | **38.5 hours** | **32 hours** | **-6.5 hours** |

### Validation Logic

**Removed Complexity:**
```java
// NO LONGER NEEDED
private static void validateKeywords(FilenameComponents components, String dataStreamId, List<String> errors) {
    // Complex keyword matching logic
}
```

**Simplified Components Class:**
```java
public class FilenameComponents {
    private String frequency;      // "Monthly" or "Quarterly"
    private String reportType;     // "Flu", "RI", "AllCOVID", etc.
    private String entityId;       // "XXA", "MAA", "NYA"
    private String dateString;     // "2026FEB" or "2026Q2"
    private String extension;      // "csv" or "zip"
    private boolean isMonthly;
    private boolean isQuarterly;
    private int year;
    private String month;          // For monthly
    private int quarter;           // For quarterly
    
    // NO keywords field needed
}
```

---

## 📋 Action Items from This Addendum

### Already Completed ✅
- [x] Updated filename-validation-spec.md with correct pattern
- [x] Updated design.md FilenameValidator implementation
- [x] Updated proposal.md test cases
- [x] Updated tasks.md test specifications
- [x] Updated QUICK_REFERENCE.md examples
- [x] Updated IMPLEMENTATION_SUMMARY.md example
- [x] Added note about existing NewModelHelper integration

### No Further Changes Needed ✅
All documents have been corrected to reflect actual production patterns. The feature request is accurate and ready for implementation.

---

## 🎉 Final Status

**Specification Accuracy:** ✅ Corrected to match production  
**Existing Integration:** ✅ Documented  
**Implementation Ready:** ✅ Yes  

**The feature request is now complete and accurate!**

---

## 📞 Questions?

**About filename patterns:** See updated specs/filename-validation-spec.md  
**About existing integration:** See design.md section on NewModelHelper  
**About implementation:** Proceed with tasks.md as updated

**Contact:** izgateway@cdc.gov
