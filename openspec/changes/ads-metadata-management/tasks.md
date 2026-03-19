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
- [ ] Add 2 new methods to IAccessControlService:
  - [ ] `FileType getFileType(String reportType)` - Case-insensitive lookup
  - [ ] `String computeDataStreamId(String fileTypeName)` - Algorithm application
- [ ] Implement in AccessControlService (delegate to NewModelHelper)
- [ ] Add `getFileType()` to NewModelHelper (lookup from existing fileTypeCache)
- [ ] Add static `computeDataStreamId()` to AccessControlModelHelper interface
- [ ] Add JavaDoc
- [ ] NO new caching - reuses existing fileTypeCache

**Implementation Notes:**
- NewModelHelper already has fileTypeCache refreshed every 5 minutes
- getFileType() does case-insensitive lookup from existing cache
- No database changes needed

---

### Task 3: Create FilenameValidator Utility
**Estimated effort:** 3 hours

**Description:**
Implement comprehensive filename validation utility according to ADS specification.

**Actual Filename Pattern:**
```
[Frequency][ReportType]_[Entity]_[Date].[extension]
Examples: MonthlyFlu_XXA_2026FEB.csv, QuarterlyRI_XXA_2026Q2.csv
```

**Files to create:**
- `src/main/java/gov/cdc/izgateway/ads/util/FilenameValidator.java`
- `src/main/java/gov/cdc/izgateway/ads/util/FilenameValidationResult.java`
- `src/main/java/gov/cdc/izgateway/ads/util/FilenameComponents.java`

**Acceptance Criteria:**
- [ ] Implement `validate()` method with 4 validation checks
- [ ] Implement `parseFilename()` with regex pattern matching
- [ ] Create result and components DTOs
- [ ] Support .csv and .zip extensions
- [ ] Add comprehensive JavaDoc

**Test Cases:**
- Valid: "MonthlyFlu_XXA_2026FEB.csv", "QuarterlyRI_XXA_2026Q2.csv"
- Invalid entity: "MonthlyRI_XYZ_2026FEB.csv"
- Period mismatch: "QuarterlyRI_XXA_2026FEB.csv"
- Missing frequency: "RI_XXA_2026Q2.csv"

---

### Task 4: Refactor MetadataBuilder
**Estimated effort:** 2 hours

**Description:**
Refactor MetadataBuilder to use AccessControlService and FileTypeMetadataUtil for computed metadata values.

**File to modify:**
- `src/main/java/gov/cdc/izgateway/ads/MetadataBuilder.java`

**Acceptance Criteria:**
- [ ] Add IAccessControlService field with constructor injection
- [ ] Refactor `setReportType()` to:
  - [ ] Look up FileType from accessControlService.getFileType()
  - [ ] Compute metaExtEvent using FileTypeMetadataUtil
  - [ ] Compute metaExtEventType using FileTypeMetadataUtil
  - [ ] Compute and store dataStreamId
  - [ ] Handle not-found case
- [ ] Update `validateMetadataWithFileType()` to:
  - [ ] Compute periodType using FileTypeMetadataUtil
  - [ ] Call FilenameValidator.validate() with computed values
- [ ] Maintain backward compatibility

---

### Task 5: Update Metadata Interface
**Estimated effort:** 0.5 hours

**Description:**
Update MetadataImpl to store computed dataStreamId value.

**File to modify:**
- `src/main/java/gov/cdc/izgateway/ads/MetadataImpl.java`

**Acceptance Criteria:**
- [ ] Add `dataStreamId` field
- [ ] Update `getDataStreamId()` to return stored value with fallback

---

### Task 6: Update ADSController
**Estimated effort:** 1 hour

**Description:**
Update ADSController to use AccessControlService and add discovery endpoint.

**File to modify:**
- `src/main/java/gov/cdc/izgateway/ads/ADSController.java`

**Acceptance Criteria:**
- [ ] Update `getMetadata()` to pass config.getAccessControls() to MetadataBuilder
- [ ] Remove hardcoded allowableValues from @Schema annotation
- [ ] Add `getAvailableReportTypes()` endpoint
- [ ] Create `ReportTypeInfo` DTO

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
