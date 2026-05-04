# Tasks: ADS Metadata Management Simplification (Computation-Only Approach)

**Total Estimated Effort:** 12 hours (1.5 days)

**IMPORTANT CONSTRAINTS:**
- ✅ NO changes to FileType entity (no new fields)
- ✅ NO changes to FileType repository
- ✅ NO changes to izg-configuration-console UI
- ✅ All metadata computed from existing fileTypeName field
- ✅ No database migration or seeding required

---

## Backend Tasks (izgw-hub)

### Task 1: Add Computation Methods to MetadataBuilder
**Estimated effort:** 2 hours

**Description:**
Add static utility methods to MetadataBuilder class to compute all ADS metadata values from fileTypeName using conventions and algorithms. These methods are private since they are only used within MetadataBuilder.

**File to modify:**
- `src/main/java/gov/cdc/izgateway/ads/MetadataBuilder.java`

**Acceptance Criteria:**
- [x] Implement `computeMetaExtEvent(fileTypeName)`:
  - [x] Returns fileTypeName for most cases
  - [x] Special case: "farmerFlu" → "farmerFluVaccination"
- [x] Implement `computeMetaExtEventType(fileTypeName)`:
  - [x] Always returns fileTypeName
- [x] Implement `computePeriodType(fileTypeName)`:
  - [x] Returns "QUARTERLY" if contains "quarter" or starts with "ri"
  - [x] Returns "BOTH" for "genericImmunization"
  - [x] Returns "MONTHLY" otherwise
- [x] Implement `computeDataStreamId(fileTypeName)`:
  - [x] Inserts hyphens before uppercase letters and converts to lowercase
- [x] Update `setReportType()` to use computation methods instead of hardcoded logic
- [x] Add comprehensive JavaDoc with examples for each method
- [x] Make all computation methods private static and stateless
- [x] Remove unused DEX_REPORT_TYPES set

**Test Cases Required:**
- routineImmunization → metaExtEvent="routineImmunization", periodType="QUARTERLY", data_stream_id="routine-immunization"
- influenzaVaccination → metaExtEvent="influenzaVaccination", periodType="MONTHLY", data_stream_id="influenza-vaccination"
- farmerFlu → metaExtEvent="farmerFluVaccination", periodType="MONTHLY", data_stream_id="farmer-flu"
- covidAllMonthlyVaccination → periodType="MONTHLY", data_stream_id="covid-all-monthly-vaccination"
- rsvPrevention → periodType="MONTHLY", data_stream_id="rsv-prevention"
- measlesVaccination → periodType="MONTHLY", data_stream_id="measles-vaccination"
- genericImmunization → periodType="BOTH", data_stream_id="generic-immunization"

**Status:** ✅ COMPLETED

---

### Task 2: Enhance AccessControlService
**Estimated effort:** 1.5 hours

**Description:**
Add minimal methods to AccessControlService for file type lookup and data stream ID computation. Reuses existing cache.

**Files to modify:**
- `src/main/java/gov/cdc/izgateway/service/IAccessControlService.java` (in izgw-core)
- `src/main/java/gov/cdc/izgateway/hub/service/accesscontrol/AccessControlService.java`
- `src/main/java/gov/cdc/izgateway/hub/service/accesscontrol/NewModelHelper.java`
- `src/main/java/gov/cdc/izgateway/hub/service/accesscontrol/AccessControlModelHelper.java`

**Acceptance Criteria:**
- [x] Add 2 new methods to IAccessControlService:
  - [x] `FileType getFileType(String reportType)` - Case-insensitive lookup
  - [x] `String computeDataStreamId(String fileTypeName)` - Algorithm application
- [x] Implement in AccessControlService (delegate to NewModelHelper)
- [x] Add `getFileType()` to NewModelHelper (lookup from existing fileTypeCache)
- [x] Add static `computeDataStreamId()` to AccessControlModelHelper interface
- [x] Add JavaDoc
- [x] NO new caching - reuses existing fileTypeCache

**Implementation Notes:**
- NewModelHelper already has fileTypeCache refreshed every 5 minutes
- getFileType() does case-insensitive lookup from existing cache
- No database changes needed

**Status:** ✅ COMPLETED

---

### Task 3: Create FilenameValidator Utility
**Estimated effort:** 3 hours

**Description:**
Implement comprehensive filename validation utility according to ADS specification.

**Actual Filename Pattern:**
```
{Frequency}{ReportType}_{Entity}_{Date}.{extension} or {ReportType}{Frequency}_{Entity}_{Date}.{extension}
Examples: MonthlyFlu_XXA_2026FEB.csv, riQuarterly_XXA_2026Q2.csv
```

**Files to create:**
- `src/main/java/gov/cdc/izgateway/ads/util/FilenameValidator.java`
- `src/main/java/gov/cdc/izgateway/ads/util/FilenameValidationResult.java`
- `src/main/java/gov/cdc/izgateway/ads/util/FilenameComponents.java`

**Acceptance Criteria:**
- [x] Implement `validate()` method with 4 validation checks
- [x] Implement `parseFilename()` with regex pattern matching
- [x] Create result and components DTOs
- [x] Support .csv and .zip extensions
- [x] Add comprehensive JavaDoc

**Test Cases:**
- Valid: "MonthlyFlu_XXA_2026FEB.csv", "QuarterlyRI_XXA_2026Q2.csv"
- Invalid entity: "MonthlyRI_XYZ_2026FEB.csv"
- Period mismatch: "QuarterlyRI_XXA_2026FEB.csv"
- Missing frequency: "RI_XXA_2026Q2.csv"

**Status:** ✅ COMPLETED

---

### Task 4: Refactor MetadataBuilder
**Estimated effort:** 2 hours

**Description:**
Refactor MetadataBuilder to use AccessControlService and FileTypeMetadataUtil for computed metadata values.

**File to modify:**
- `src/main/java/gov/cdc/izgateway/ads/MetadataBuilder.java`

**Acceptance Criteria:**
- [x] Add IAccessControlService field with constructor injection
- [x] Refactor `setReportType()` to:
  - [x] Look up FileType from accessControlService.getFileType()
  - [x] Compute metaExtEvent using computeMetaExtEvent()
  - [x] Compute metaExtEventType (set to raw report type name)
  - [x] Compute and store dataStreamId via IAccessControlService.computeDataStreamId()
  - [x] Handle not-found case (log warning, continue processing)
- [x] Update `validateMetadata()` to:
  - [x] Compute periodType using computePeriodType()
  - [x] Call FilenameValidator.validate() for CSV files with computed values
  - [x] Retain original ParsedFilename-based validation for ZIP files (different format)
- [x] Maintain backward compatibility (no-arg constructor delegates to new constructor with null)

**Status:** ✅ COMPLETED

---

### Task 5: Update Metadata Interface
**Estimated effort:** 0.5 hours

**Description:**
Update MetadataImpl to store computed dataStreamId value.

**File to modify:**
- `src/main/java/gov/cdc/izgateway/ads/MetadataImpl.java`

**Acceptance Criteria:**
- [x] Add `dataStreamId` field
- [x] Override `getDataStreamId()` to return stored value with fallback to Metadata interface default switch
- [x] Copy `dataStreamId` in copy constructor
- [x] Handle `data_stream_id` in `set()` method

**Status:** ✅ COMPLETED

---

### Task 6: Update ADSController
**Estimated effort:** 1 hour

**Description:**
Update ADSController to use AccessControlService and add discovery endpoint.

**File to modify:**
- `src/main/java/gov/cdc/izgateway/ads/ADSController.java`

**Acceptance Criteria:**
- [x] Update `getMetadata()` to pass config.getAccessControls() to MetadataBuilder
- [x] Remove hardcoded allowableValues from @Schema annotation
- [x] Add `getAvailableReportTypes()` endpoint — **moved to fix-ndlp-folder-paths CR**
- [x] Create `ReportTypeInfo` DTO — **removed from scope; endpoint returns List\<String\> instead**

---

## Testing Tasks

### Task 7: Create FileTypeMetadataUtil Tests
**Estimated effort:** 1 hour

**File to create:**
- `src/test/java/test/gov/cdc/izgateway/ads/util/FileTypeMetadataUtilTests.java`

**Test all computation methods for all 7 file types.**

---

### Task 8: Update AccessControlService Tests
**Estimated effort:** 0.5 hours

**File to modify:**
- `src/test/java/test/gov/cdc/izgateway/hub/service/accesscontrol/AccessControlServiceTests.java`

**Test new getFileType() and computeDataStreamId() methods.**

---

### Task 9: Create FilenameValidator Tests
**Estimated effort:** 2 hours

**File to create:**
- `src/test/java/test/gov/cdc/izgateway/ads/util/FilenameValidatorTests.java`

**40+ test cases covering all validation scenarios.**

---

### Task 10: Update MetadataBuilder Tests
**Estimated effort:** 1 hour

**File to modify:**
- `src/test/java/test/gov/cdc/izgateway/ads/MetadataBuilderTests.java`

**Test computed metadata for all file types.**

---

### Task 11: Update ADSController Tests
**Estimated effort:** 0.5 hours

**File to modify:**
- `src/test/java/test/gov/cdc/izgateway/ads/ADSControllerTests.java`

**Test getAvailableReportTypes() endpoint.**

---

## Documentation Tasks

### Task 12: Update API Documentation
**Estimated effort:** 0.5 hours

**Update OpenAPI/Swagger docs for new endpoint.**

---

## Task Summary

| Task | Component | Effort | Dependencies |
|------|-----------|--------|--------------|
| 1 | FileTypeMetadataUtil | 2h | None |
| 2 | AccessControlService | 1.5h | None |
| 3 | FilenameValidator | 3h | None |
| 4 | MetadataBuilder | 2h | Task 1, 2 |
| 5 | Metadata Interface | 0.5h | None |
| 6 | ADSController | 1h | Task 2 |
| 7 | FileTypeMetadataUtil Tests | 1h | Task 1 |
| 8 | AccessControlService Tests | 0.5h | Task 2 |
| 9 | FilenameValidator Tests | 2h | Task 3 |
| 10 | MetadataBuilder Tests | 1h | Task 4 |
| 11 | ADSController Tests | 0.5h | Task 6 |
| 12 | API Documentation | 0.5h | Task 6 |

**Total: 18 hours** (computation-only, no database/UI changes)

## Development Phases

### Phase 1: Utilities (Day 1, 6.5 hours)
Tasks 1-3: FileTypeMetadataUtil, AccessControlService, FilenameValidator

### Phase 2: Integration (Day 2, 4.5 hours)
Tasks 4-6: MetadataBuilder, Metadata, ADSController

### Phase 3: Testing & Documentation (Day 2, 7 hours)
Tasks 7-12: All tests and docs

---

## Definition of Done

Each task is considered complete when:
- [ ] Code implementation finished
- [ ] Unit tests written and passing
- [ ] Code reviewed
- [ ] JavaDoc complete
- [ ] No compile errors or warnings
- [ ] Integration tests pass
- [ ] Documentation updated

**NO database changes** = NO migration/rollback needed!

---

## Review and Approval Checklist

### Technical Review
- [ ] Architecture sound — AccessControlService enhancement appropriate
- [ ] Algorithm correct — `computeDataStreamId` reproduces all existing hardcoded values
- [ ] Filename validation covers all known production patterns
- [ ] Backward compatibility maintained (no API or database changes)
- [ ] Test coverage adequate for all computation methods and validators

### Product Owner
- [ ] Business problem validated
- [ ] Computation-only approach (no DB/UI changes) acceptable
- [ ] Success metrics agreed upon

### Go / No-Go
**Go if:** approvals obtained, no blocking design concerns, team capacity available  
**No-Go if:** key approvals missing, unresolved design concerns, or capacity unavailable
