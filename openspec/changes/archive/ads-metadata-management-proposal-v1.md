# Proposal: ADS Metadata Management Simplification

## Why

### Current Problems

**Hardcoded Metadata Mappings**
The ADS metadata generation logic is currently spread across multiple classes with hardcoded switch statements and static sets:
- `MetadataBuilder` contains hardcoded `DEX_REPORT_TYPES` set and multiple switch statements
- `Metadata.getDataStreamId()` has hardcoded mappings from report type to data stream ID (13+ case statements)
- `ADSController` has hardcoded allowable values in OpenAPI annotations
- Adding a new report type (e.g., measlesVaccination, farmerFlu) requires code changes in 4+ locations
- Risk of inconsistency when mappings get out of sync across classes

**Maintenance Burden**
Every new report type requires:
- Code changes in multiple Java classes
- Maven rebuild and redeployment
- Risk of introducing bugs in production
- Coordination between development and operations teams
- Extended lead time for new data submission programs

**Lack of Configuration Flexibility**
- Cannot quickly add new report types for pilot programs or emerging needs
- Cannot update metadata field mappings without code deployment
- No self-service capability for administrators
- Configuration changes require developer intervention

**Incomplete Database Integration**
The FileType entity and repository exist but are underutilized:
- FileType only stores name and description
- **EXISTING:** FileType is already cached in `NewModelHelper.fileTypeCache` and used for event type validation via `getEventTypes()`
- **EXISTING:** UI screens already exist for managing File Type List
- **MISSING:** No connection between FileType and metadata generation (meta_ext_event, data_stream_id)
- **MISSING:** No filename validation configuration
- Missed opportunity to extend existing infrastructure for metadata configuration
- UI screens exist but provide limited value

### Business Need

CDC frequently introduces new data submission programs (e.g., RSV Prevention, Measles Vaccination, Farmer Flu) requiring new report types. The current implementation creates bottlenecks:
- Lead time measured in weeks (code → test → deploy) instead of minutes (UI update)
- Dependencies on developer availability for simple configuration
- Risk of production issues from rushed code changes
- Inability to quickly respond to public health emergencies requiring new data streams

**Real-World Example:**
When RSV Prevention reporting was added, changes were required in:
1. MetadataBuilder.DEX_REPORT_TYPES (add "rsvprevention")
2. MetadataBuilder.setReportType() (add case for rsvPrevention)
3. Metadata.getDataStreamId() (add case mapping to "rsv-prevention")
4. ADSController.postADSFile() @Schema annotation (add "rsvPrevention" to allowableValues)
5. Access Control configuration (add to event types list)

This same pattern repeats for every new report type, creating maintenance burden and deployment risk.

## What Changes

### 1. Enhance FileType Entity and Repository

**Add metadata configuration fields to FileType entity:**

New attributes in `gov.cdc.izgateway.dynamodb.model.FileType`:
```java
private String metaExtEvent;          // Value for meta_ext_event field (e.g., "routineImmunization")
private String metaExtEventType;      // Value for meta_ext_event_type field (usually same as fileTypeName)
private String dataStreamIdTemplate;  // Optional: explicit data_stream_id, null = auto-compute
private String filenamePattern;       // Optional: regex pattern for filename validation
private String periodType;            // "MONTHLY" or "QUARTERLY" or "BOTH"
private boolean active;               // Whether this file type is currently accepted
```

**Enhance FileTypeRepository interface:**
```java
// Add to gov.cdc.izgateway.hub.repository.IFileTypeRepository
Optional<FileType> findByFileTypeName(String fileTypeName);
List<FileType> findAllActive();  // WHERE active = true
```

**Implementation in DynamoDB repository:**
- Add query methods using DynamoDB Enhanced Client
- Support case-insensitive lookup (normalize to match existing patterns)

### 2. Enhance AccessControlService

**Extend existing service:** `gov.cdc.izgateway.service.IAccessControlService`

AccessControlService already manages FileType caching through NewModelHelper and refreshes every 5 minutes. We enhance it with ADS metadata operations rather than creating a new service.

**New methods to add to IAccessControlService:**
```java
public interface IAccessControlService {
    // ...existing methods...
    
    /**
     * Get file type by report type name (case-insensitive).
     * Uses existing fileTypeCache from NewModelHelper.
     * @param reportType The report type from API request
     * @return FileType if found, null otherwise
     */
    FileType getFileType(String reportType);
    
    /**
     * Get all active file types for validation and API documentation.
     * @return List of active file types from cache
     */
    List<FileType> getActiveFileTypes();
    
    /**
     * Compute data_stream_id from file type name using algorithm.
     * Algorithm: Insert hyphen before last uppercase letter in any sequence of uppercase letters, then lowercase.
     * Example: "RIQuarterlyAggregate" → "ri-quarterly-aggregate"
     * @param fileTypeName The file type name
     * @return Computed data stream ID
     */
    String computeDataStreamId(String fileTypeName);
    
    /**
     * Validate filename against file type pattern and period type.
     * @param filename The filename to validate
     * @param fileType The file type configuration
     * @param period The submission period
     * @return Validation result with pass/fail and error messages
     */
    FilenameValidationResult validateFilename(String filename, FileType fileType, String period);
}
```

**Implementation Notes:**
- Methods delegate to NewModelHelper which has existing fileTypeCache
- No new caching overhead - reuses existing 5-minute refresh cycle
- AccessControlService already injected into ADSController via config.getAccessControls()
- computeDataStreamId() added as static method in AccessControlModelHelper interface

### 3. Create Filename Validation Utility

**New utility class:** `gov.cdc.izgateway.ads.util.FilenameValidator`

```java
public class FilenameValidator {
    /**
     * Validate filename structure according to ADS specification:
     * - Must start with keyword(s) from data_stream_id (separated by hyphens)
     * - Followed by entity ID: 3 uppercase letters ending in 'A'
     * - Followed by date: YYYYMMM (monthly) or YYYYQ# (quarterly)
     * - Must contain "monthly" or "quarterly" matching period type
     * - Extension: .csv or .zip
     * 
     * Example valid filenames:
     * - "monthly-routine-immunization-MAA-2025JAN.zip"
     * - "quarterly-ri-aggregate-NYA-2025Q1.zip"
     * - "influenza-vaccination-CVA-2025DEC.csv"
     */
    public static ValidationResult validate(
        String filename, 
        String dataStreamId, 
        String periodType, 
        String expectedPeriod,
        String expectedEntity
    );
    
    /**
     * Extract components from filename for validation.
     */
    public static FilenameComponents parseFilename(String filename);
}

public class ValidationResult {
    private boolean valid;
    private List<String> errors;
    private FilenameComponents components;
}

public class FilenameComponents {
    private String keywords;      // Leading keyword portion
    private String entityId;      // 3-letter entity code (e.g., MAA, NYA)
    private String dateString;    // Date portion (e.g., 2025JAN, 2025Q1)
    private String extension;     // File extension
    private boolean isMonthly;    // True if filename contains "monthly"
    private boolean isQuarterly;  // True if filename contains "quarterly"
}
```

### 4. Refactor MetadataBuilder

**Replace hardcoded logic with database-driven approach:**

Changes to `gov.cdc.izgateway.ads.MetadataBuilder`:
```java
public class MetadataBuilder {
    // Remove static DEX_REPORT_TYPES set
    // Remove hardcoded switch statements
    
    private IAccessControlService accessControlService; // Injected
    
    /**
     * Set the report type using FileType configuration from cache.
     */
    public MetadataBuilder setReportType(String reportType) {
        FileType fileType = accessControlService.getFileType(reportType);
        
        if (fileType == null || !fileType.isActive()) {
            errors.add(reportType + " is not a valid reportType value. " +
                      "Available types: " + getActiveReportTypeNames());
            meta.setExtEventType(reportType);
            return this;
        }
        
        // Apply metadata from FileType configuration
        meta.setExtEvent(fileType.getMetaExtEvent());
        meta.setExtEventType(fileType.getMetaExtEventType());
        
        return this;
    }
    
    /**
     * Enhanced filename validation using FileType configuration.
     */
    private void validateMetadata(ParsedFilename pf) {
        FileType fileType = fileTypeService.getFileTypeByReportType(meta.getExtEventType());
        if (fileType != null) {
            FilenameValidationResult result = fileTypeService.validateFilename(
                meta.getFilename(), fileType, meta.getPeriod()
            );
            if (!result.isValid()) {
                errors.addAll(result.getErrors());
            }
        }
        // ... existing validation logic ...
    }
}
```

### 5. Update Metadata Interface

**Refactor data_stream_id computation:**

Changes to `gov.cdc.izgateway.ads.Metadata`:
```java
@JsonProperty("data_stream_id")
default String getDataStreamId() {
    // Option 1: Store computed value in MetadataImpl (RECOMMENDED)
    // Option 2: Compute dynamically using AccessControlModelHelper.computeDataStreamId()
    
    // For backward compatibility, keep fallback to switch statement
    // but prefer database-driven computation
}
```

### 6. Enhance izg-configuration-console UI

**Update File Type management screens:**

Changes to `src/lib/type/AdsFileType.ts`:
```typescript
export interface AdsFileTypeItem extends DbAudit {
  sortKey: string
  fileTypeName: string
  description?: string
  metaExtEvent?: string              // NEW
  metaExtEventType?: string          // NEW (defaults to fileTypeName)
  dataStreamIdTemplate?: string      // NEW (nullable - auto-computed if null)
  filenamePattern?: string           // NEW (optional regex)
  periodType?: 'MONTHLY' | 'QUARTERLY' | 'BOTH'  // NEW
  active?: boolean                   // NEW (defaults to true)
}
```

Changes to `src/components/AccessControl/AddFileTypeList.tsx`:
- Add TextField for `metaExtEvent` (required)
- Add TextField for `metaExtEventType` (optional, defaults to fileTypeName)
- Add Select/Dropdown for `periodType` (MONTHLY/QUARTERLY/BOTH)
- Add Switch for `active` status (default true)
- Add TextField for `filenamePattern` (optional, with help text)
- Add read-only display of computed `dataStreamId` (preview)
- Add validation for required fields
- Add help text explaining each field's purpose

Changes to `src/components/AccessControl/FileTypeList.tsx`:
- Add columns for new metadata fields
- Add filter for active/inactive file types
- Add tooltips for truncated values
- Add visual indicator for inactive file types

### 7. Update API Documentation

**Make OpenAPI specs dynamic:**

Changes to `gov.cdc.izgateway.ads.ADSController`:
```java
// Remove hardcoded allowableValues from @Schema annotation
// Add dynamic documentation generation method

/**
 * Get available report types for OpenAPI documentation.
 */
private String[] getAvailableReportTypes() {
    return fileTypeService.getActiveFileTypes().stream()
        .map(FileType::getFileTypeName)
        .toArray(String[]::new);
}
```

Consider using Spring's `@DynamicProperty` or runtime schema generation for truly dynamic OpenAPI specs.

## Capabilities

### New Capabilities
- `database-driven-metadata-generation`: Generate metadata fields (meta_ext_event, meta_ext_event_type, data_stream_id) from FileType configuration
- `dynamic-report-type-management`: Add/remove report types through configuration console without code changes
- `comprehensive-filename-validation`: Full filename validation against report type patterns and period types
- `data-stream-id-computation`: Automatic computation of data_stream_id from fileTypeName using standardized algorithm
- `access-control-metadata-integration`: Extended AccessControlService with ADS metadata operations

### Modified Capabilities
- `ads-file-upload`: Enhanced to use database-driven metadata generation via AccessControlService
- `metadata-validation`: Enhanced with comprehensive filename pattern matching and period type validation
- `report-type-validation`: Now validates against active FileTypes from existing cache instead of hardcoded list
- `file-type-management-ui`: Enhanced with metadata configuration fields
- `access-control-service`: Extended with 4 new methods for ADS metadata operations

### Deprecated Capabilities
- Hardcoded DEX_REPORT_TYPES set (will maintain for backward compatibility during migration)
- Hardcoded getDataStreamId() switch statement (will maintain as fallback)

## Impact

### Affected Code

**Backend (izgw-hub):**
- `gov.cdc.izgateway.dynamodb.model.FileType` - Add 6 new fields
- `gov.cdc.izgateway.dynamodb.repository.FileTypeRepository` - Add query methods
- `gov.cdc.izgateway.hub.repository.IFileTypeRepository` - Add interface methods
- `gov.cdc.izgateway.service.IAccessControlService` - Add 4 new methods for ADS metadata
- `gov.cdc.izgateway.hub.service.accesscontrol.AccessControlService` - Implement new methods
- `gov.cdc.izgateway.hub.service.accesscontrol.NewModelHelper` - Add metadata operations
- `gov.cdc.izgateway.hub.service.accesscontrol.AccessControlModelHelper` - Add static computeDataStreamId()
- `gov.cdc.izgateway.ads.util.FilenameValidator` (NEW) - Validation utility
- `gov.cdc.izgateway.ads.util.FilenameValidationResult` (NEW) - Result DTO
- `gov.cdc.izgateway.ads.util.FilenameComponents` (NEW) - Parsed components DTO
- `gov.cdc.izgateway.ads.MetadataBuilder` - Refactor to use AccessControlService
- `gov.cdc.izgateway.ads.Metadata` - Update getDataStreamId() method
- `gov.cdc.izgateway.ads.MetadataImpl` - Add dataStreamId field
- `gov.cdc.izgateway.ads.ADSController` - Use AccessControlService (already injected)

**Frontend (izg-configuration-console):**
- `src/lib/type/AdsFileType.ts` - Add new TypeScript interface fields
- `src/components/AccessControl/AddFileTypeList.tsx` - Add form fields
- `src/components/AccessControl/FileTypeList.tsx` - Add grid columns
- `src/pages/api/` - May need API endpoint updates for CRUD operations

**Testing:**
- `ADSControllerTests.java` - Update for AccessControlService usage
- `MetadataBuilderTests.java` - Add tests for AccessControlService integration
- `AccessControlServiceTests.java` - Add tests for new methods
- `FilenameValidatorTests.java` (NEW) - Comprehensive validation tests

### Dependencies

**No new external dependencies required:**
- Uses existing DynamoDB Enhanced Client
- Uses existing Caffeine caching (already in classpath)
- Uses existing Spring Boot dependency injection
- Uses existing izg-configuration-console React/MUI framework

**Internal Dependencies:**
- AccessControlService already depends on FileTypeRepository (existing)
- MetadataBuilder → AccessControlService (via constructor)
- ADSController already has AccessControlService (via config.getAccessControls())

**Service Dependencies:**
```
ADSController → AccessControlService (EXISTING) → FileTypeRepository (EXISTING) → DynamoDB
                      ↓
                FilenameValidator (NEW)
```

### Affected Data

**DynamoDB Schema Changes (FileType table):**

New attributes (all optional for backward compatibility):
- `metaExtEvent` (String) - Maps to meta_ext_event metadata field
- `metaExtEventType` (String) - Maps to meta_ext_event_type metadata field
- `dataStreamIdTemplate` (String, nullable) - Explicit data_stream_id or null for auto-compute
- `filenamePattern` (String, nullable) - Optional regex for custom filename validation
- `periodType` (String) - "MONTHLY", "QUARTERLY", or "BOTH"
- `active` (Boolean) - Whether file type is enabled (default: true)

**Data Migration Strategy:**

Create data seeding for existing report types:
```java
// Example seed data
FileType routineImmunization = new FileType();
routineImmunization.setFileTypeName("routineImmunization");
routineImmunization.setDescription("Routine Immunization quarterly reporting");
routineImmunization.setMetaExtEvent("routineImmunization");
routineImmunization.setMetaExtEventType("routineImmunization");
routineImmunization.setDataStreamIdTemplate(null); // auto-compute to "routine-immunization"
routineImmunization.setPeriodType("QUARTERLY");
routineImmunization.setActive(true);
routineImmunization.setSortKey("1");

// Repeat for: influenzaVaccination, covidAllMonthlyVaccination, 
// rsvPrevention, measlesVaccination, farmerFlu, genericImmunization
```

### Testing Impact

**New Test Classes:**
1. `AccessControlServiceTests` - New method tests
   - Test file type lookup from existing cache (case-insensitive)
   - Test active file type filtering
   - Test data_stream_id computation algorithm
   - Test filename validation integration
   
2. `FilenameValidatorTests` - Validation algorithm tests
   - Valid monthly filenames: "MonthlyFlu_XXA_2026FEB.csv"
   - Valid quarterly filenames: "QuarterlyRI_XXA_2026Q2.csv"
   - Invalid entity codes: "MonthlyRI_XYZ_2026FEB.csv" (doesn't end in A)
   - Period mismatch: "QuarterlyRI_XXA_2026FEB.csv" (Quarterly prefix but monthly date)
   - Missing frequency: "Flu_XXA_2026FEB.csv" (missing "Monthly" prefix)
   - Date format errors: "MonthlyRI_XXA_26FEB.csv" (2-digit year)
   
3. `DataStreamIdComputationTests` - Algorithm verification
   - "RIQuarterlyAggregate" → "ri-quarterly-aggregate"
   - "covidAllMonthlyVaccination" → "covid-all-monthly-vaccination"
   - "influenzaVaccination" → "influenza-vaccination"
   - "rsvPrevention" → "rsv-prevention"
   - "farmerFluVaccination" → "farmer-flu-vaccination"

**Updated Test Classes:**
- `MetadataBuilderTests` - Add tests for AccessControlService integration
- `ADSControllerTests` - Verify AccessControlService usage, test dynamic validation

### API Impact

**No Breaking Changes** - Fully backward compatible

**Enhanced Behavior:**
- `/rest/ads/{destinationId}` endpoint accepts report types from active FileTypes
- Better error messages when invalid report type submitted
- OpenAPI documentation can dynamically reflect available report types (future enhancement)

**Potential New Endpoints:**
```java
// Optional endpoints for File Type discovery
@GetMapping("/ads/reportTypes")
public List<String> getAvailableReportTypes(); // Returns active file type names

@GetMapping("/ads/reportTypes/{reportType}")
public FileTypeInfo getReportTypeInfo(@PathVariable String reportType); // Returns metadata config

@PostMapping("/ads/reportTypes/validate")
public ValidationResult validateReportTypeMetadata(
    @RequestParam String reportType,
    @RequestParam String filename,
    @RequestParam String period,
    @RequestParam String facilityId
); // Pre-validation before actual upload
```

### Security Impact

**Access Control:**
- File Type management restricted to ADMIN role (existing)
- No changes to file upload authentication/authorization
- Audit trail maintained through DynamoDB audit fields

**Data Privacy:**
- No PHI/PII in FileType configuration
- Metadata fields are non-sensitive (report types, patterns)

### Performance Impact

**Database Access:**
- One DynamoDB read per file upload (lookup FileType by reportType)
- Estimated latency: 5-10ms (single-item query)
- Mitigated by Caffeine caching (5-minute TTL)
- Cache hit rate expected: >95% for production workloads

**Caching Strategy:**
```java
LoadingCache<String, Optional<FileType>> cache = Caffeine.newBuilder()
    .expireAfterWrite(5, TimeUnit.MINUTES)
    .maximumSize(100)  // Max 100 file types
    .build(key -> fileTypeRepository.findByFileTypeName(key));
```

**Filename Validation:**
- Regex matching and string parsing add <1ms per request
- Negligible impact compared to file upload and storage operations

### Backward Compatibility

**Full Backward Compatibility Maintained:**

1. **Existing report types continue to work**
   - Database seeded with current hardcoded report types
   - Existing API requests unchanged
   - No impact to current submissions

2. **Graceful Fallback**
   - If FileType not found in database, fall back to hardcoded logic (during migration)
   - Log warning when fallback used
   - Allows gradual migration

3. **Optional Fields**
   - New FileType fields are optional
   - Existing FileType records work without new fields
   - Default values applied when fields missing

4. **No API Changes**
   - Request/response formats unchanged
   - HTTP methods and paths unchanged
   - Query parameters unchanged

**Migration Path:**
```
Phase 1 (Day 1): Deploy code with FileType enhancements + fallback logic
Phase 2 (Day 2): Seed database with existing report types
Phase 3 (Day 3): Verify all report types work via database
Phase 4 (Week 2): Monitor and address any issues
Phase 5 (Month 2): Remove hardcoded fallback logic in future release
```

## Non-Goals

**Out of Scope for This Change:**
- ❌ Implementing new report types (only infrastructure for managing them)
- ❌ Modifying Azure Blob Storage or DEX upload mechanisms
- ❌ Adding multi-tenancy or organization-specific metadata rules
- ❌ Real-time metadata streaming or event sourcing
- ❌ Changing DynamoDB table structure (uses existing attributes mechanism)
- ❌ Implementing approval workflow for file type changes (existing audit trail sufficient)
- ❌ Modifying access control or authorization rules (uses existing)

## Alternatives Considered

### Alternative 1: Configuration File (YAML/JSON)
**Rejected because:**
- Requires file system access or classpath resources
- No UI for management
- No audit trail for changes
- Requires redeployment to update

### Alternative 2: Environment Variables
**Rejected because:**
- Complex mappings difficult to represent
- No UI for management
- Container restart required for changes
- Limited validation capabilities

### Alternative 3: External Configuration Service
**Rejected because:**
- Introduces new external dependency
- Additional infrastructure complexity
- Existing DynamoDB solution sufficient
- Network latency considerations

### Selected Approach: DynamoDB + Service Layer
**Chosen because:**
- ✅ Leverages existing infrastructure
- ✅ UI already exists (izg-configuration-console)
- ✅ Audit trail built-in (DynamoDbAudit)
- ✅ No new dependencies
- ✅ Caching mitigates latency
- ✅ Proven pattern in codebase

## Risks and Mitigations

### Risk 1: Database Unavailable During Upload
**Mitigation:**
- Implement caching with reasonable TTL (5 minutes)
- Add fallback to hardcoded logic (logged as warning)
- Monitor cache hit rates

### Risk 2: Invalid FileType Configuration
**Mitigation:**
- Validate fields when saving in UI
- Add unit tests for all existing report types
- Provide migration script for initial seeding
- Add admin validation endpoint

### Risk 3: Performance Degradation
**Mitigation:**
- Cache file type lookups aggressively
- Database queries are simple (single-item, indexed)
- Load test with realistic workloads
- Monitor P95/P99 latency metrics

### Risk 4: Configuration Errors by Admins
**Mitigation:**
- Provide comprehensive UI validation and help text
- Show preview of computed fields (data_stream_id)
- Add "test" button to validate configuration
- Require description field to document purpose
- Maintain audit trail for rollback

## Success Metrics

**Development Metrics:**
- ✅ Time to add new report type: <5 minutes (vs current ~2 weeks)
- ✅ Code changes required: 0 (vs current 5+ files)
- ✅ Test coverage: >85% for new services and utilities

**Performance Metrics:**
- ✅ P95 latency increase: <10ms for file upload endpoint
- ✅ Cache hit rate: >95% after warm-up period
- ✅ Database query time: <10ms average

**Operational Metrics:**
- ✅ Zero production incidents from file type additions
- ✅ Audit trail for all configuration changes
- ✅ Self-service rate: 100% for file type additions (no developer needed)
