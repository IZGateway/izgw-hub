# Design: ADS Metadata Management Simplification

## Architecture Overview

This design replaces hardcoded metadata mappings with a database-driven configuration system, enabling administrators to manage ADS report types through the izg-configuration-console without requiring code changes.

**IMPORTANT:** FileType infrastructure already exists! The `NewModelHelper` class in `AccessControlService` already caches FileType records and uses `getEventTypes()` to return file type names for validation. This design extends that existing pattern to include metadata generation fields.

```
┌─────────────────────────────────────────────────────────────────┐
│               izg-configuration-console (UI)                     │
│              File Type Management Screens                        │
└─────────────────────────────────────────────────────────────────┘
                            │
                            │ CRUD Operations
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                      DynamoDB                                    │
│                   FileType Table (EXISTING)                      │
│  ┌────────────────────────────────────────────────────────┐    │
│  │ fileTypeName | metaExtEvent | periodType | active | ...│    │
│  └────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
                            │
                            │ Loaded by
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│              AccessControlService (EXISTING)                     │
│  ┌──────────────────────────────────────────────────────┐      │
│  │ EXISTING: NewModelHelper.fileTypeCache               │      │
│  │ EXISTING: getEventTypes() → fileTypeCache.keySet()   │      │
│  │ NEW: getFileType(reportType) → fileTypeCache.get()   │      │
│  │ NEW: getActiveFileTypes() → filtered cache values    │      │
│  │ NEW: computeDataStreamId(fileTypeName) → algorithm   │      │
│  │ NEW: validateFilename() → uses FilenameValidator     │      │
│  └──────────────────────────────────────────────────────┘      │
│  Refreshes every 5 minutes (scheduled)                          │
└─────────────────────────────────────────────────────────────────┘
                            │
                            │ Injected into
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                   MetadataBuilder                                │
│  • setReportType() → queries AccessControlService               │
│  • validateMetadata() → uses FilenameValidator                  │
└─────────────────────────────────────────────────────────────────┘
                            │
                            │ Built by
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    ADSController                                 │
│  • postADSFile() → creates Metadata                             │
│  • Uses AccessControlService (already injected)                 │
└─────────────────────────────────────────────────────────────────┘
```

## Component Design

### 1. FileType Entity (NO CHANGES)

**File:** `gov.cdc.izgateway.dynamodb.model.FileType`

**IMPORTANT CONSTRAINT:** No new fields added to FileType. All metadata must be computed from existing `fileTypeName` field only.

**Existing Structure (Unchanged):**
```java
@Data  
@EqualsAndHashCode(callSuper=false)
@DynamoDbBean
public class FileType extends DynamoDbAudit implements DynamoDbEntity, Serializable, IFileType {
    private String fileTypeName;        // PRIMARY KEY - sole source for all computations
    private String description;         // Existing field - unchanged
    // NO NEW FIELDS - ALL METADATA COMPUTED DYNAMICALLY
}
```

**Metadata Computation Strategy:**

All ADS metadata values are computed from `fileTypeName` using:
1. **Naming conventions** (e.g., "Quarterly" in name → QUARTERLY period type)
2. **Hyphenation algorithm** (e.g., camelCase → hyphenated-lowercase for data_stream_id)
3. **Hardcoded exceptions** (e.g., farmerFlu → farmerFluVaccination for metaExtEvent)

**Computation Table:**

| fileTypeName | metaExtEvent | data_stream_id | periodType |
|--------------|--------------|----------------|------------|
| routineImmunization | routineImmunization | routine-immunization | QUARTERLY |
| influenzaVaccination | influenzaVaccination | influenza-vaccination | MONTHLY |
| farmerFlu | farmerFluVaccination* | farmer-flu | MONTHLY |
| rsvPrevention | rsvPrevention | rsv-prevention | MONTHLY |
| measlesVaccination | measlesVaccination | measles-vaccination | MONTHLY |
| covidAllMonthlyVaccination | covidAllMonthlyVaccination | covid-all-monthly-vaccination | MONTHLY |
| genericImmunization | genericImmunization | generic-immunization | BOTH |

*Special case maintained in computation logic for backward compatibility

### 2. FileTypeRepository (NO CHANGES)

**No modifications needed.** Existing repository and cache in NewModelHelper provide all necessary access.

### 3. Computation Utility Methods

**New static utility class:** `gov.cdc.izgateway.ads.util.FileTypeMetadataUtil`

**Purpose:** Centralize all computation logic for FileType metadata.

```java
public class FileTypeMetadataUtil {
    
    /**
     * Compute meta_ext_event from file type name.
     * Default: returns fileTypeName
     * Exception: "farmerFlu" → "farmerFluVaccination"
     */
    public static String computeMetaExtEvent(String fileTypeName) {
        if ("farmerFlu".equalsIgnoreCase(fileTypeName)) {
            return "farmerFluVaccination";
        }
        return fileTypeName;
    }
    
    /**
     * Compute meta_ext_event_type from file type name.
     * Always returns fileTypeName.
     */
    public static String computeMetaExtEventType(String fileTypeName) {
        return fileTypeName;
    }
    
    /**
     * Compute period type from file type name using naming conventions.
     */
    public static String computePeriodType(String fileTypeName) {
        if (StringUtils.isBlank(fileTypeName)) {
            return "MONTHLY"; // default
        }
        
        String lower = fileTypeName.toLowerCase();
        
        // Check for quarterly indicators
        if (lower.contains("quarter") || lower.contains("quarterly")) {
            return "QUARTERLY";
        }
        
        // RI (Routine Immunization) is quarterly
        if (lower.startsWith("ri") || lower.equals("routineimmunization")) {
            return "QUARTERLY";
        }
        
        // Generic accepts both
        if ("genericImmunization".equals(fileTypeName)) {
            return "BOTH";
        }
        
        // Default to monthly
        return "MONTHLY";
    }
    
    /**
     * Compute data_stream_id using hyphenation algorithm.
     * Already defined in AccessControlModelHelper as static method.
     */
    public static String computeDataStreamId(String fileTypeName) {
        return AccessControlModelHelper.computeDataStreamId(fileTypeName);
    }
}
```

### 4. Enhanced AccessControlService

**Files:** 
- `gov.cdc.izgateway.service.IAccessControlService` (interface)
- `gov.cdc.izgateway.hub.service.accesscontrol.AccessControlService` (implementation)
- `gov.cdc.izgateway.hub.service.accesscontrol.NewModelHelper` (helper class)

**Purpose:** Extend existing access control service with ADS metadata operations. The AccessControlService already manages FileType caching through NewModelHelper, so we enhance it rather than create a new service.

**Current State:**
```java
// EXISTING in NewModelHelper
private Map<String, FileType> fileTypeCache = Collections.emptyMap();

@Override
public void refresh() {
    fileTypeCache = refreshCache(accessControlService.fileTypeRepository, FileType::getFileTypeName);
    // ... refreshes every 5 minutes via scheduled executor
}

@Override
public Set<String> getEventTypes() {
    return fileTypeCache.keySet();
}
```

**New Methods to Add to IAccessControlService:**
```java
public interface IAccessControlService {
    // ...existing methods...
    
    // NEW METHODS for ADS metadata computation
    /**
     * Get file type by report type name (case-insensitive).
     * Uses existing cache to minimize database calls.
     * @param reportType The report type from API request
     * @return FileType if found, null otherwise
     */
    FileType getFileType(String reportType);
    
    /**
     * Compute data_stream_id from file type name using algorithm.
     * @param fileTypeName The file type name
     * @return Computed data stream ID
     */
    String computeDataStreamId(String fileTypeName);
}
```

**Implementation in AccessControlService:**
```java
@Service
@Slf4j
public class AccessControlService implements IAccessControlService {
    // ...existing fields...
    
    @Override
    public FileType getFileType(String reportType) {
        return currentModelHelper.getFileType(reportType);
    }
    
    @Override
    public String computeDataStreamId(String fileTypeName) {
        return AccessControlModelHelper.computeDataStreamId(fileTypeName);
    }
}
```

**Enhancement to NewModelHelper:**
```java
@Slf4j
class NewModelHelper implements AccessControlModelHelper {
    // ...existing fields and methods...
    
    /**
     * Get file type by report type name from cache.
     * Already cached, no database call needed.
     */
    @Override
    public FileType getFileType(String reportType) {
        if (StringUtils.isBlank(reportType)) {
            return null;
        }
        
        // Try exact match
        FileType result = fileTypeCache.get(reportType);
        if (result != null) {
            return result;
        }
        
        // Try case-insensitive match
        for (Map.Entry<String, FileType> entry : fileTypeCache.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(reportType)) {
                return entry.getValue();
            }
        }
        
        return null;
    }
}
```

**Add to AccessControlModelHelper interface:**
```java
interface AccessControlModelHelper {
    // ...existing methods...
    
    // NEW METHODS
    FileType getFileType(String reportType);
    
    /**
     * Static utility method for data stream ID computation.
     * Can be called without instance.
     */
    static String computeDataStreamId(String fileTypeName) {
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
            
            if (isUpper && i > 0 && (prevIsLower || nextIsLower)) {
                result.append('-');
            }
            
            result.append(Character.toLowerCase(current));
        }
        
        return result.toString();
    }
}
```

**Key Advantages of Using Existing AccessControlService:**
- ✅ Reuses existing FileType cache (already refreshed every 5 minutes)
- ✅ No new service needed - extends existing infrastructure
- ✅ Consistent with existing access control patterns
- ✅ No additional caching overhead
- ✅ Already injected into ADSController via config.accessControls

### 4. FilenameValidator Utility

**File:** `gov.cdc.izgateway.ads.util.FilenameValidator`

**Purpose:** Implements the standard filename validation algorithm according to ADS specifications.

**Filename Structure Specification:**

```
[Frequency][ReportType]_[Entity]_[Date].[extension]

Where:
- Frequency: "Monthly" or "Quarterly" (capital first letter)
- ReportType: Abbreviation or short name (no separator after frequency)
- Entity: 3 uppercase letters ending in 'A' (e.g., MAA, NYA, XXA)
- Date: YYYYMMM (monthly) or YYYYQ# (quarterly)
  - YYYY: 4-digit year
  - MMM: 3-letter month abbreviation (JAN, FEB, MAR, APR, MAY, JUN, JUL, AUG, SEP, OCT, NOV, DEC)
  - Q#: Quarter 1-4
- Extension: .csv or .zip
- Separators: Underscores between major components

Examples:
✅ "MonthlyFlu_XXA_2026FEB.csv"
✅ "QuarterlyRI_XXA_2026Q2.csv"
✅ "MonthlyAllCOVID_XXA_2026FEB.csv"
✅ "MonthlyRSV_XXA_2026FEB.csv"
✅ "MonthlyMeasles_MAA_2026JAN.csv"
✅ "MonthlyFarmerFlu_NYA_2025DEC.csv"
❌ "Flu_XXA_2026FEB.csv" (missing "Monthly")
❌ "MonthlyRI_XYZ_2026Q1.csv" (entity doesn't end in A)
❌ "MonthlyRI_XXA_2026Q1.csv" (period mismatch: Monthly prefix but quarterly date)
❌ "QuarterlyFlu_XXA_2026FEB.csv" (period mismatch: Quarterly prefix but monthly date)
```

**Implementation:**

```java
@Slf4j
public class FilenameValidator {
    
    private static final String MONTH_PATTERN = "(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)";
    private static final String QUARTERLY_DATE_PATTERN = "(\\d{4})Q([1-4])";
    private static final String MONTHLY_DATE_PATTERN = "(\\d{4})" + MONTH_PATTERN;
    private static final String ENTITY_PATTERN = "([A-Z]{2}A)";
    
    // Full pattern: [Frequency][ReportType]_[Entity]_[Date].[ext]
    private static final Pattern FILENAME_PATTERN = Pattern.compile(
        "^(Monthly|Quarterly)([A-Za-z]+)_" + ENTITY_PATTERN + "_(" + 
        QUARTERLY_DATE_PATTERN + "|" + MONTHLY_DATE_PATTERN + ")\\.(csv|zip)$"
    );
    
    /**
     * Validate filename according to ADS specification.
     * 
     * @param filename The filename to validate
     * @param dataStreamId The expected data stream ID (e.g., "routine-immunization") - optional, for logging
     * @param periodType The expected period type ("MONTHLY", "QUARTERLY", or "BOTH")
     * @param expectedPeriod The expected period (e.g., "2026-FEB", "2026Q2")
     * @param expectedEntity The expected entity ID (e.g., "MAA"), null to skip entity validation
     * @return Validation result with pass/fail and error details
     */
    public static FilenameValidationResult validate(
            String filename,
            String dataStreamId,  // Optional - not used for validation, kept for compatibility
            String periodType,
            String expectedPeriod,
            String expectedEntity) {
        
        List<String> errors = new ArrayList<>();
        
        if (StringUtils.isBlank(filename)) {
            errors.add("Filename is required");
            return new FilenameValidationResult(false, errors, null);
        }
        
        // Parse filename components
        FilenameComponents components = parseFilename(filename);
        if (components == null) {
            errors.add("Filename does not match expected pattern: [Frequency][ReportType]_[Entity]_[Date].[ext]");
            return new FilenameValidationResult(false, errors, null);
        }
        
        // Validate frequency keyword presence and case
        validateFrequencyKeyword(components, periodType, errors);
        
        // Validate entity ID format
        validateEntityId(components, expectedEntity, errors);
        
        // Validate date format matches frequency
        validateDateFormat(components, periodType, errors);
        
        // Validate date matches expected period
        validatePeriodMatch(components, expectedPeriod, errors);
        
        boolean valid = errors.isEmpty();
        return new FilenameValidationResult(valid, errors, components);
    }
    
    /**
     * Parse filename into components.
     * Pattern: [Frequency][ReportType]_[Entity]_[Date].[ext]
     * Example: MonthlyFlu_XXA_2026FEB.csv
     */
    public static FilenameComponents parseFilename(String filename) {
        Matcher matcher = FILENAME_PATTERN.matcher(filename);
        if (!matcher.matches()) {
            return null;
        }
        
        FilenameComponents components = new FilenameComponents();
        components.setFrequency(matcher.group(1));           // "Monthly" or "Quarterly"
        components.setReportType(matcher.group(2));          // e.g., "Flu", "RI", "AllCOVID"
        components.setEntityId(matcher.group(3));            // e.g., "XXA", "MAA"
        components.setDateString(matcher.group(4));          // Full date string
        components.setExtension(matcher.group(9));           // "csv" or "zip"
        
        // Parse date details
        if (matcher.group(5) != null) {
            // Quarterly format matched
            components.setQuarterly(true);
            components.setYear(Integer.parseInt(matcher.group(5)));
            components.setQuarter(Integer.parseInt(matcher.group(6)));
        } else {
            // Monthly format matched
            components.setMonthly(true);
            components.setYear(Integer.parseInt(matcher.group(7)));
            components.setMonth(matcher.group(8));
        }
        
        return components;
    }
    
    /**
     * Validate frequency keyword ("Monthly" or "Quarterly") is present and matches period type.
     */
    private static void validateFrequencyKeyword(FilenameComponents components, String periodType, List<String> errors) {
        String frequency = components.getFrequency();
        boolean isMonthly = "Monthly".equals(frequency);
        boolean isQuarterly = "Quarterly".equals(frequency);
        
        if (!isMonthly && !isQuarterly) {
            errors.add("Filename must start with 'Monthly' or 'Quarterly' (case-sensitive)");
            return;
        }
        
        // Check frequency matches period type configuration
        if ("MONTHLY".equals(periodType) && !isMonthly) {
            errors.add("Filename must start with 'Monthly' for monthly report types");
        } else if ("QUARTERLY".equals(periodType) && !isQuarterly) {
            errors.add("Filename must start with 'Quarterly' for quarterly report types");
        }
        // BOTH allows either
    }
    
    /**
     * Validate entity ID is 3 uppercase letters ending in 'A'.
     */
    private static void validateEntityId(FilenameComponents components, String expectedEntity, List<String> errors) {
        String entityId = components.getEntityId();
        
        if (!entityId.matches("[A-Z]{2}A")) {
            errors.add(String.format(
                "Entity ID '%s' must be 3 uppercase letters ending in 'A' (e.g., MAA, NYA, CVA)",
                entityId
            ));
        }
        
        // If expected entity specified, must match
        if (expectedEntity != null && !entityId.equalsIgnoreCase(expectedEntity)) {
            errors.add(String.format(
                "Entity ID '%s' in filename does not match expected entity '%s'",
                entityId, expectedEntity
            ));
        }
    }
    
    /**
     * Validate date format matches frequency keyword.
     */
    private static void validateDateFormat(FilenameComponents components, String periodType, List<String> errors) {
        String frequency = components.getFrequency();
        boolean frequencyIsMonthly = "Monthly".equals(frequency);
        boolean frequencyIsQuarterly = "Quarterly".equals(frequency);
        boolean dateIsMonthly = components.isMonthly();
        boolean dateIsQuarterly = components.isQuarterly();
        
        // Frequency keyword must match date format
        if (frequencyIsMonthly && !dateIsMonthly) {
            errors.add("Filename starts with 'Monthly' but date is in quarterly format (YYYYQ#)");
        } else if (frequencyIsQuarterly && !dateIsQuarterly) {
            errors.add("Filename starts with 'Quarterly' but date is in monthly format (YYYYMMM)");
        }
    }
    
    /**
     * Validate date in filename matches expected period.
     */
    private static void validatePeriodMatch(FilenameComponents components, String expectedPeriod, List<String> errors) {
        if (StringUtils.isBlank(expectedPeriod)) {
            return;
        }
        
        String normalizedPeriod = expectedPeriod.trim().toUpperCase().replace("-", "");
        String filenamePeriod = components.getDateString().toUpperCase();
        
        if (!normalizedPeriod.equals(filenamePeriod)) {
            errors.add(String.format(
                "Date in filename '%s' does not match expected period '%s'",
                components.getDateString(), expectedPeriod
            ));
        }
    }
    
    /**
     * Clear the cache (for testing or after admin updates).
     */
    public void clearCache() {
        cache.invalidateAll();
        log.info("FileType cache cleared");
    }
    
    /**
     * Get cache statistics for monitoring.
     */
    public CacheStats getCacheStats() {
        return cache.stats();
    }
}
```

### 5. Data Stream ID Computation Algorithm

**Algorithm Specification:**

The algorithm converts a camelCase or PascalCase fileTypeName to a hyphenated lowercase data_stream_id.

**Rules:**
1. Identify sequences of consecutive uppercase letters (acronyms)
2. For sequences of 2+ uppercase letters followed by lowercase:
   - Insert hyphen before the LAST uppercase letter in the sequence
   - This last letter starts the next word
3. For single uppercase letter followed by lowercase:
   - Insert hyphen before the uppercase letter
4. For uppercase letter at position 0:
   - Do not insert hyphen
5. Convert entire result to lowercase

**Examples:**

| Input (fileTypeName) | Processing Steps | Output (data_stream_id) |
|---------------------|------------------|------------------------|
| `RIQuarterlyAggregate` | RI→ri + Quarterly→-quarterly + Aggregate→-aggregate | `ri-quarterly-aggregate` |
| `covidAllMonthlyVaccination` | covid + All→-all + Monthly→-monthly + Vaccination→-vaccination | `covid-all-monthly-vaccination` |
| `influenzaVaccination` | influenza + Vaccination→-vaccination | `influenza-vaccination` |
| `rsvPrevention` | rsv + Prevention→-prevention | `rsv-prevention` |
| `farmerFluVaccination` | farmer + Flu→-flu + Vaccination→-vaccination | `farmer-flu-vaccination` |
| `routineImmunization` | routine + Immunization→-immunization | `routine-immunization` |
| `measlesVaccination` | measles + Vaccination→-vaccination | `measles-vaccination` |
| `genericImmunization` | generic + Immunization→-immunization | `generic-immunization` |

**Test Cases:**

```java
@Test
public void testComputeDataStreamId() {
    assertEquals("ri-quarterly-aggregate", 
                 FileTypeService.computeDataStreamId("RIQuarterlyAggregate"));
    
    assertEquals("covid-all-monthly-vaccination", 
                 FileTypeService.computeDataStreamId("covidAllMonthlyVaccination"));
    
    assertEquals("influenza-vaccination", 
                 FileTypeService.computeDataStreamId("influenzaVaccination"));
    
    assertEquals("rsv-prevention", 
                 FileTypeService.computeDataStreamId("rsvPrevention"));
    
    assertEquals("farmer-flu-vaccination", 
                 FileTypeService.computeDataStreamId("farmerFluVaccination"));
    
    assertEquals("routine-immunization", 
                 FileTypeService.computeDataStreamId("routineImmunization"));
    
    // Edge cases
    assertEquals("abc", FileTypeService.computeDataStreamId("ABC"));
    assertEquals("a-b-c", FileTypeService.computeDataStreamId("ABC")); // If followed by lowercase
    assertEquals("covid19-vaccine", FileTypeService.computeDataStreamId("COVID19Vaccine"));
}
```

### 6. Refactored MetadataBuilder

**File:** `gov.cdc.izgateway.ads.MetadataBuilder`

**Changes:**

```java
@Slf4j
public class MetadataBuilder {
    // Remove static DEX_REPORT_TYPES set
    // Remove hardcoded switch statements
    
    private MetadataImpl meta = new MetadataImpl();
    private List<String> errors = new ArrayList<>();
    private String destUrl;
    private IAccessControlService accessControlService; // EXISTING: Already available, just use it!
    
    /**
     * Constructor with AccessControlService injection.
     * Note: AccessControlService already exists and is injected into ADSController.
     */
    public MetadataBuilder(IAccessControlService accessControlService) {
        this.accessControlService = accessControlService;
        meta.setExtSource("IZGW");
    }
    
    /**
     * Set the report type using FileType configuration from database.
     * Replaces hardcoded DEX_REPORT_TYPES and switch logic.
     */
    public MetadataBuilder setReportType(String reportType) {
        meta.setExtEventType(reportType);
        
        if (StringUtils.isBlank(reportType)) {
            errors.add("Report Type must be present and not empty");
            return this;
        }
        
        // Look up file type configuration from existing cache
        FileType fileType = accessControlService.getFileType(reportType);
        
        if (fileType == null) {
            // File type not found in cache
            errors.add(String.format(
                "Report type '%s' is not recognized. Available types: %s",
                reportType,
                getActiveReportTypeNames()
            ));
            // Set genericImmunization as fallback for backward compatibility
            meta.setExtEvent("genericImmunization");
            meta.setExtSourceVersion(Metadata.DEX_VERSION2);
            return this;
        }
        
        if (fileType.getActive() != null && !fileType.getActive()) {
            // File type exists but is disabled
            errors.add(String.format(
                "Report type '%s' is currently disabled. Please contact administrator.",
                reportType
            ));
            return this;
        }
        
        // Apply metadata computed from fileTypeName
        String metaExtEvent = FileTypeMetadataUtil.computeMetaExtEvent(fileType.getFileTypeName());
        String metaExtEventType = FileTypeMetadataUtil.computeMetaExtEventType(fileType.getFileTypeName());
        String dataStreamId = accessControlService.computeDataStreamId(fileType.getFileTypeName());
        
        meta.setExtEvent(metaExtEvent);
        meta.setExtEventType(metaExtEventType);
        meta.setDataStreamId(dataStreamId);
        
        log.debug("Applied computed metadata: reportType={}, metaExtEvent={}, dataStreamId={}",
                  reportType, metaExtEvent, dataStreamId);
        
        return this;
    }
    
    /**
     * Enhanced filename validation using FileType configuration.
     */
    @Override
    public MetadataBuilder setFilename(String filename) {
        filename = filename.trim();
        meta.setFilename(filename);
        
        if (!ADSUtils.validateFilename(filename)) {
            errors.add(String.format(
                "Filename (%s) contains invalid characters",
                filename
            ));
            return this;
        }
        
        ParsedFilename pf = ParsedFilename.parse(filename, errors);
        meta.setTestFile(pf.isTestfile());
        
        if (isMetadataValidationEnabled()) {
            validateMetadataWithFileType(pf);
        }
        
        return this;
    }
    
    /**
     * Validate metadata using FileType configuration.
     */
    private void validateMetadataWithFileType(ParsedFilename pf) {
        String reportType = meta.getExtEventType();
        FileType fileType = accessControlService.getFileType(reportType);
        
        if (fileType == null) {
            // Fall back to basic validation if no file type config
            log.warn("No FileType configuration found for {}, using basic validation", reportType);
            validateMetadata(pf); // Existing validation logic
            return;
        }
        
        // Use AccessControlService for comprehensive validation
        String periodType = FileTypeMetadataUtil.computePeriodType(fileType.getFileTypeName());
        String dataStreamId = accessControlService.computeDataStreamId(fileType.getFileTypeName());
        
        FilenameValidationResult result = FilenameValidator.validate(
            meta.getFilename(),
            dataStreamId,
            periodType,
            meta.getPeriod(),
            null // entityId extracted from filename
        );
        
        if (!result.isValid()) {
            errors.addAll(result.getErrors());
        }
        
        // Additional entity validation
        if (!pf.getEntityId().equalsIgnoreCase(meta.getExtEntity())) {
            errors.add(String.format(
                "Entity ID (%s) in filename does not match facility entity (%s)",
                pf.getEntityId(), meta.getExtEntity()
            ));
        }
    }
    
    /**
     * Get list of active report type names for error messages.
     */
    private String getActiveReportTypeNames() {
        return accessControlService.getActiveFileTypes().stream()
            .map(FileType::getFileTypeName)
            .collect(Collectors.joining(", "));
    }
}
```

**Key Changes:**
- Constructor now requires `IAccessControlService` injection (already available!)
- `setReportType()` uses accessControlService.getFileType() instead of database query
- Enhanced validation using `FilenameValidator`
- Better error messages with available report types
- No new caching needed - reuses existing NewModelHelper.fileTypeCache
- Graceful fallback for missing configurations

### 7. Updated Metadata Interface

**File:** `gov.cdc.izgateway.ads.Metadata`

**Changes to getDataStreamId():**

```java
@JsonProperty("data_stream_id")
default String getDataStreamId() {
    String eventType = getExtEventType();
    
    if (StringUtils.isBlank(eventType)) {
        return null;
    }
    
    // Use FileTypeService to compute from extEventType
    // This method is called during JSON serialization, so we need static access
    // Option 1: Store computed value in MetadataImpl during building
    // Option 2: Use FileTypeService static method
    
    // Recommended: Store computed value in MetadataImpl.dataStreamId field
    // Computed in MetadataBuilder.setReportType() and cached
    return FileTypeService.computeDataStreamId(eventType);
}
```

**Alternative Design (Store Computed Value):**

Add field to `MetadataImpl`:
```java
@Data
public class MetadataImpl implements Metadata {
    // ... existing fields ...
    
    private String dataStreamId; // NEW: Computed and stored
    
    @Override
    public String getDataStreamId() {
        if (dataStreamId != null) {
            return dataStreamId;
        }
        // Fallback to computation
        return FileTypeService.computeDataStreamId(getExtEventType());
    }
}
```

Compute in `MetadataBuilder.setReportType()`:
```java
public MetadataBuilder setReportType(String reportType) {
    // ... lookup FileType ...
    
    meta.setExtEvent(fileType.getMetaExtEvent());
    meta.setExtEventType(fileType.getMetaExtEventType());
    
    // Compute and store data_stream_id
    String dataStreamId = fileType.getDataStreamIdTemplate();
    if (dataStreamId == null) {
        dataStreamId = FileTypeService.computeDataStreamId(fileType.getFileTypeName());
    }
    meta.setDataStreamId(dataStreamId);
    
    return this;
}
```

**Recommendation:** Use stored computed value approach (already shown in MetadataBuilder above).

**Update MetadataImpl to store computed value:**
```java
@Data
public class MetadataImpl implements Metadata {
    // ... existing fields ...
    
    private String dataStreamId; // NEW: Computed and stored during building
    
    @Override
    public String getDataStreamId() {
        if (dataStreamId != null) {
            return dataStreamId;
        }
        // Fallback to computation using static helper
        return AccessControlModelHelper.computeDataStreamId(getExtEventType());
    }
}
```

### 8. Updated ADSController

**File:** `gov.cdc.izgateway.ads.ADSController`

**Changes:**

```java
@Slf4j
@RestController
public class ADSController {
    private final ADSControllerConfiguration config;
    // NO NEW INJECTION NEEDED - AccessControlService already available via config.accessControls
    
    @Autowired
    public ADSController(
            ADSControllerConfiguration config, 
            AccessControlRegistry registry) { // NO CHANGE - same constructor
        this.config = config;
        registry.register(this);
    }
    
    /**
     * Updated postADSFile with dynamic report type validation.
     * Remove hardcoded allowableValues from @Schema annotation.
     */
    @PostMapping(value = "/ads/{destinationId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Metadata postADSFile(
            @PathVariable String destinationId,
            @RequestHeader(name = "X-Message-ID", required = false) String xMessageId,
            @RequestHeader(name = "X-Request-ID", required = false) String xRequestId,
            @RequestHeader(name = "X-Correlation-ID", required = false) String xCorrelationId,
            @RequestParam("facilityId") String facilityId,
            @Schema(description = "The type of report. Use /rest/ads/reportTypes to list available types.") 
            @RequestParam("reportType") String reportType, // Removed hardcoded allowableValues
            @RequestParam("file") MultipartFile file,
            @RequestParam("period") String period,
            @RequestParam(required = false) String filename,
            @RequestParam(defaultValue = "false") boolean force)
            throws Fault {
        // ... existing implementation ...
        
        // Pass AccessControlService to MetadataBuilder (via config)
        meta = getMetadata(messageId, destinationId, facilityId, reportType, period, filename, force);
        
        // ... rest of method unchanged ...
    }
    
    /**
     * Updated getMetadata to use AccessControlService (already available).
     */
    private MetadataImpl getMetadata(String messageId, String destinationId, String facilityId, 
            String reportType, String period, String filename, boolean force) throws MetadataFault {
        
        // Use existing AccessControlService (already in config)
        MetadataBuilder m = new MetadataBuilder(config.getAccessControls());
        TransactionData tData = initLogging(destinationId, messageId, m);
        
        m.setRouteId(config.getDests(), destinationId);
        m.setMessageId(messageId);
        m.setProvenance(facilityId, RequestContext.getTransactionData());
        normalizeReportType(m, reportType);
        m.setFileSize(0);
        m.setPeriod(period);
        m.setMetadataValidationEnabled(!force);
        m.setFilename(filename);
        
        // ... rest unchanged ...
    }
    
    /**
     * Updated normalizeReportType to use AccessControlService.
     */
    private void normalizeReportType(MetadataBuilder m, String reportType) {
        FileType fileType = config.getAccessControls().getFileType(reportType);
        
        if (fileType != null && (fileType.getActive() == null || fileType.getActive())) {
            // Use normalized name from database
            m.setReportType(fileType.getFileTypeName());
        } else {
            // File type not found or inactive
            m.getErrors().add(String.format(
                "%s is not a valid reportType value. Available types: %s",
                reportType,
                getActiveReportTypeNames()
            ));
            m.setReportType(reportType);
        }
    }
    
    /**
     * Helper to get active report type names for error messages.
     */
    private String getActiveReportTypeNames() {
        return config.getAccessControls().getActiveFileTypes().stream()
            .map(FileType::getFileTypeName)
            .collect(Collectors.joining(", "));
    }
    
    /**
     * NEW: Get available report types endpoint for API discovery.
     */
    @GetMapping("/ads/reportTypes")
    @Operation(summary = "List available report types", 
               description = "Returns list of active report types that can be used in file uploads")
    public List<ReportTypeInfo> getAvailableReportTypes() {
        return config.getAccessControls().getEventTypes().stream()
            .map(fileTypeName -> new ReportTypeInfo(
                fileTypeName,
                null, // Description not available in simple implementation
                FileTypeMetadataUtil.computePeriodType(fileTypeName),
                accessControlService.computeDataStreamId(fileTypeName)
            ))
            .collect(Collectors.toList());
    }
    
    @Data
    @AllArgsConstructor
    public static class ReportTypeInfo {
        private String reportType;
        private String description;
        private String periodType;
        private String dataStreamId;
    }
}
```

### 5. UI Changes (NONE)

**CONSTRAINT:** No UI changes allowed. The existing izg-configuration-console File Type management screens remain unchanged.

**Rationale:** All metadata is computed from existing `fileTypeName` field, so no new UI fields are needed.

### 6. Data Seeding (NOT NEEDED)

### Current Flow (Hardcoded)

```
Request → ADSController.postADSFile()
             ↓
         getMetadata()
             ↓
         normalizeReportType()
             ↓
         MetadataBuilder.setReportType()
             ↓
         Hardcoded switch statement
             ↓
         meta.setExtEvent(hardcodedValue)
             ↓
         Metadata.getDataStreamId()
             ↓
         Hardcoded switch statement
             ↓
         return "hardcoded-value"
```

### New Flow (Database-Driven)

```
Request → ADSController.postADSFile()
             ↓
         getMetadata()
             ↓
         normalizeReportType()
             ↓
         MetadataBuilder.setReportType(fileTypeService)
             ↓
         FileTypeService.getFileTypeByReportType()
             ↓
         Cache.get() → [Cache Hit] → Return cached FileType
             ↓ [Cache Miss]
         FileTypeRepository.findByFileTypeName()
             ↓
         DynamoDB Query
             ↓
         Return FileType
             ↓
         meta.setExtEvent(fileType.metaExtEvent)
         meta.setExtEventType(fileType.metaExtEventType)
         meta.setDataStreamId(computed or template)
             ↓
         Metadata.getDataStreamId()
             ↓
         return meta.dataStreamId (pre-computed)
```

### Filename Validation Flow

```
MetadataBuilder.setFilename()
    ↓
validateMetadataWithFileType()
    ↓
FileTypeService.validateFilename(filename, fileType, period)
    ↓
FilenameValidator.validate()
    ↓
parseFilename() → FilenameComponents
    ↓
Parallel Validation:
    ├─ validateFrequencyKeyword()
    ├─ validateEntityId()
    ├─ validateDateFormat()
    └─ validatePeriodMatch()
    ↓
FilenameValidationResult
    ├─ valid: boolean
    ├─ errors: List<String>
    └─ components: FilenameComponents
    ↓
Add errors to MetadataBuilder.errors
```

## Caching Strategy

### Cache Configuration

**Technology:** Caffeine (already in classpath via HAPI FHIR)

**Configuration:**
```java
LoadingCache<String, Optional<FileType>> cache = Caffeine.newBuilder()
    .expireAfterWrite(5, TimeUnit.MINUTES)  // TTL: 5 minutes
    .maximumSize(100)                        // Max entries: 100 file types
    .recordStats()                           // Enable statistics
    .build(key -> fileTypeRepository.findByFileTypeName(key));
```

**Rationale:**
- 5-minute TTL balances freshness with performance
- 100 entry limit is ample (expect <20 file types in production)
- Statistics enable monitoring of hit rates

### Cache Invalidation

**Automatic Invalidation:**
- Time-based: 5-minute TTL ensures stale data is refreshed
- Size-based: LRU eviction if >100 entries (unlikely)

**Manual Invalidation:**
- `FileTypeService.clearCache()` method for admin operations
- Called after file type CRUD operations via API
- Available as management endpoint for troubleshooting

**Monitoring:**
- Cache hit rate logged periodically
- Expose metrics via Spring Boot Actuator
- Alert if hit rate <90% (indicates configuration issues)

## Error Handling

### Validation Error Scenarios

1. **Report Type Not Found**
   - Error: "{reportType} is not a valid reportType value. Available types: ..."
   - HTTP Status: 400 Bad Request
   - Logged: WARN level

2. **Report Type Inactive**
   - Error: "Report type '{reportType}' is currently disabled. Please contact administrator."
   - HTTP Status: 400 Bad Request
   - Logged: WARN level

3. **Filename Validation Failure**
   - Error: Multiple specific errors from FilenameValidator
   - HTTP Status: 400 Bad Request (unless force=true)
   - Logged: WARN level with full validation details

4. **Database Unavailable**
   - Fallback to hardcoded logic (temporary migration strategy)
   - Error logged: ERROR level
   - Metrics: Database failure counter incremented
   - Continue processing if fallback succeeds

### Logging Strategy

```java
// File type lookup
log.debug("Looking up FileType for reportType: {}", reportType);
log.trace("Cache stats: {}", fileTypeService.getCacheStats());

// Cache hit/miss
log.trace("FileType cache hit for: {}", reportType);
log.debug("FileType cache miss, querying database for: {}", reportType);

// Validation failures
log.warn("FileType validation failed for reportType={}: {}", reportType, errors);

// Database fallback
log.error("Failed to load FileType from database for {}, using fallback", reportType, exception);

// Configuration issues
log.warn("FileType {} is inactive, rejecting submission", reportType);
```

## Backward Compatibility Strategy

### Phase 1: Deploy with Dual Logic (Week 1)

**Both hardcoded and database-driven logic active:**
```java
public MetadataBuilder setReportType(String reportType) {
    // Try database first
    FileType fileType = fileTypeService.getFileTypeByReportType(reportType);
    
    if (fileType != null && fileType.isActive()) {
        // Use database configuration
        meta.setExtEvent(fileType.getMetaExtEvent());
        meta.setExtEventType(fileType.getMetaExtEventType());
        log.debug("Using database FileType configuration for: {}", reportType);
    } else {
        // Fallback to hardcoded logic
        log.warn("FileType not found in database for {}, using hardcoded fallback", reportType);
        applyHardcodedMapping(reportType); // Existing switch logic
    }
    
    return this;
}
```

### Phase 2: Database Seeding (Week 1-2)

**Seed all existing report types:**
- Run migration script to populate FileType table
- Verify all existing submissions work via database
- Monitor for any issues

### Phase 3: Monitor (Week 2-4)

**Monitoring checklist:**
- ✅ All file uploads succeed
- ✅ Cache hit rate >95%
- ✅ No database errors
- ✅ Metadata fields match expected values
- ✅ No fallback to hardcoded logic

### Phase 4: Remove Fallback (Month 2+)

**Remove hardcoded logic after confidence built:**
- Remove DEX_REPORT_TYPES set
- Remove switch statements in setReportType()
- Remove switch statement in getDataStreamId()
- Keep validation tests to ensure database seeding is correct

## Testing Strategy

### Unit Tests

**FileTypeServiceTests:**
```java
@Test
void testGetFileTypeByReportType_ExactMatch() { }

@Test
void testGetFileTypeByReportType_CaseInsensitive() { }

@Test
void testGetFileTypeByReportType_NotFound() { }

@Test
void testGetActiveFileTypes() { }

@Test
void testComputeDataStreamId_AllExistingTypes() { }

@Test
void testCaching_HitRate() { }
```

**FilenameValidatorTests:**
```java
@Test
void testValidate_ValidMonthlyFilename() { }

@Test
void testValidate_ValidQuarterlyFilename() { }

@Test
void testValidate_MissingFrequencyKeyword() { }

@Test
void testValidate_InvalidEntityId() { }

@Test
void testValidate_PeriodMismatch() { }

@Test
void testValidate_KeywordMismatch() { }

@Test
void testParseFilename_AllFormats() { }
```

**MetadataBuilderTests:**
```java
@Test
void testSetReportType_WithDatabaseConfig() { }

@Test
void testSetReportType_InactiveFileType() { }

@Test
void testSetReportType_NotFound() { }

@Test
void testFilenameValidation_WithFileTypeConfig() { }
```

### Integration Tests

**ADSControllerIntegrationTests:**
```java
@Test
void testPostADSFile_WithDatabaseConfiguredReportType() {
    // Seed FileType
    // Upload file
    // Verify metadata
}

@Test
void testPostADSFile_NewReportTypeAddedViaUI() {
    // Add FileType via repository
    // Clear cache
    // Upload file
    // Verify acceptance
}
```

### Manual Testing Checklist

- [ ] Add new file type via UI
- [ ] Upload file with new report type
- [ ] Verify metadata in Azure Blob Storage
- [ ] Verify metadata in DynamoDB logs
- [ ] Test inactive file type rejection
- [ ] Test filename validation errors
- [ ] Test period type validation
- [ ] Verify cache hit rates in logs
- [ ] Test all existing report types still work

## Migration Plan

### Pre-Deployment

1. **Create migration script** to seed FileType table with existing report types
2. **Test script in dev environment** with real uploads
3. **Prepare rollback plan** (keep hardcoded logic as fallback)

### Deployment Day

**Step 1: Deploy backend (izgw-hub)**
- Deploy with FileType enhancements + fallback logic
- Verify health checks pass
- Monitor for errors

**Step 2: Seed database**
- Run migration script to populate FileType table
- Verify all 7 report types inserted correctly
- Check data integrity

**Step 3: Deploy frontend (izg-configuration-console)**
- Deploy UI changes with new form fields
- Verify File Type screens load correctly
- Test CRUD operations

**Step 4: Smoke testing**
- Upload test file for each report type
- Verify metadata generated correctly
- Check logs for database queries and cache hits

### Post-Deployment

**Week 1:**
- Monitor all file uploads
- Check for validation errors
- Verify cache performance
- Address any issues

**Week 2-4:**
- Analyze cache hit rates
- Review error logs
- Collect feedback from administrators
- Document process for adding new report types

**Month 2:**
- Consider removing hardcoded fallback
- Update documentation
- Plan for additional enhancements

## Performance Considerations

### Expected Latency

**Database Query (cache miss):**
- DynamoDB single-item query: ~5-10ms
- Frequency: <5% of requests (due to caching)
- Impact: <0.5ms average per request

**Cache Lookup (cache hit):**
- In-memory hash map lookup: <0.1ms
- Frequency: >95% of requests
- Impact: negligible

**Filename Validation:**
- Regex matching: <0.5ms
- String parsing: <0.5ms
- Total: ~1ms per request
- Impact: negligible compared to file I/O

### Load Testing Scenarios

**Scenario 1: Normal Load**
- 100 uploads/hour
- Mix of report types
- Expected: >99% cache hit rate after warm-up

**Scenario 2: Spike Load**
- 1000 uploads/hour
- All same report type
- Expected: 100% cache hit rate (after first request)

**Scenario 3: Cache Cold Start**
- Application restart
- First request per report type queries database
- Expected: 7 database queries, then cached

### Monitoring Metrics

**Application Metrics (Spring Boot Actuator):**
- `filetype.cache.hit.rate` - Cache hit percentage
- `filetype.cache.miss.count` - Cache miss counter
- `filetype.database.query.duration` - Database query time
- `filetype.validation.duration` - Filename validation time

**Business Metrics:**
- Report type distribution (which types most used)
- Validation failure rate by report type
- Active vs inactive file type counts

## Security Considerations

### Access Control

**File Type Management:**
- Only ADMIN role can create/update/delete file types
- Uses existing izg-configuration-console authentication
- Audit trail tracks who made changes and when

**File Upload:**
- No changes to existing authentication/authorization
- File type validation adds defense-in-depth
- Invalid report types rejected early (before storage)

### Data Validation

**Input Sanitization:**
- All FileType fields validated in UI and backend
- Regex patterns validated before storage
- SQL/NoSQL injection not applicable (DynamoDB Enhanced Client uses prepared statements)

**Filename Validation:**
- Prevents path traversal (existing ADSUtils.validateFilename)
- Additional pattern matching adds robustness
- Rejects filenames with suspicious patterns

### Audit Trail

**DynamoDB Audit Fields (existing):**
- `createdBy` - Username who created file type
- `createdOn` - Creation timestamp
- `modifiedBy` - Username who last modified
- `modifiedOn` - Last modification timestamp

**Logging:**
- All file type changes logged at INFO level
- Validation failures logged with details
- Database query failures logged at ERROR level

## Open Questions

### Question 1: Cache Invalidation Strategy
**Options:**
a) Time-based only (current design: 5-minute TTL)
b) Event-based (invalidate on CRUD operations)
c) Manual invalidation endpoint for admins

**Recommendation:** Start with (a), add (c) as management endpoint. Consider (b) if stale data becomes issue.

### Question 2: UI Validation of Custom Regex
**Options:**
a) No validation - trust admin to enter correct regex
b) Basic validation - check syntax compiles
c) Advanced validation - test regex against sample filenames

**Recommendation:** Start with (b), show compilation errors. Add (c) in future if needed.

### Question 3: Data Stream ID Computation
**Options:**
a) Always compute from fileTypeName (current design)
b) Allow explicit dataStreamIdTemplate override
c) Validate computed value matches CDC expectations

**Recommendation:** Use (a) with (b) as escape hatch for edge cases. Add validation in UI showing computed preview.

### Question 4: Deprecated Report Types
**Options:**
a) Set active=false (soft delete)
b) Hard delete from database
c) Archive to separate table

**Recommendation:** Use (a) - allows reactivation if needed, maintains audit history.

## Future Enhancements

### Phase 2 (Future):
- Add validation rules engine for complex business logic
- Support jurisdiction-specific filename patterns
- Add metadata template system for custom fields
- Implement approval workflow for file type changes
- Add bulk import/export of file type configurations

### Phase 3 (Future):
- Real-time cache invalidation using DynamoDB Streams
- Multi-region replication of file type configurations
- Version history for file type changes
- A/B testing support for new report type configurations

---

## Appendix A — Actual Production Filename Patterns

### Confirmed Pattern

```
[Frequency][ReportType]_[Entity]_[Date].[extension]
```

Separators are **underscores** between components; there is **no separator** between the frequency keyword and the report type abbreviation. The frequency keyword is PascalCase.

### Production Examples

| Filename | fileTypeName | metaExtEvent | data_stream_id |
|----------|--------------|--------------|----------------|
| `MonthlyFlu_XXA_2026FEB.csv` | influenzaVaccination | influenzaVaccination | influenza-vaccination |
| `QuarterlyRI_XXA_2026Q2.csv` | routineImmunization | routineImmunization | routine-immunization |
| `MonthlyAllCOVID_XXA_2026FEB.csv` | covidAllMonthlyVaccination | covidAllMonthlyVaccination | covid-all-monthly-vaccination |
| `MonthlyRSV_XXA_2026FEB.csv` | rsvPrevention | rsvPrevention | rsv-prevention |
| `MonthlyMeasles_XXA_2026FEB.csv` | measlesVaccination | measlesVaccination | measles-vaccination |
| `MonthlyFarmerFlu_XXA_2026FEB.csv` | farmerFlu | farmerFluVaccination | farmer-flu-vaccination |

**Note:** The filename abbreviation (Flu, RI, RSV) is for human readability. The authoritative report type comes from the `reportType` API parameter. Filename validation checks structure, entity, and date — not that the abbreviation maps to the report type.

### Corrected Validation Regex

```regex
^(Monthly|Quarterly)([A-Za-z]+)_([A-Z]{2}A)_((\d{4})Q([1-4])|(\d{4})(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC))\.(csv|zip)$
```

Note that frequency detection is now done case-insensitively anywhere in the prefix segment (not anchored to the start), matching the `CsvFilenameValidator` implementation.

---

## Appendix B — Design Decision: Enhance AccessControlService (Not New FileTypeService)

**Decision date:** 2026-03-16

### Options Considered

| | Option A: New FileTypeService | Option B: Enhance AccessControlService (chosen) |
|--|--|--|
| New files | FileTypeService.java + tests | 0 |
| Modified files | 9 | 4 (IAccessControlService, AccessControlService, NewModelHelper, AccessControlModelHelper) |
| Cache | New Caffeine cache, new refresh cycle | Reuses existing `fileTypeCache` in NewModelHelper, refreshed every 5 min |
| Injection | New injection in ADSController + MetadataBuilder | Already injected via `config.getAccessControls()` |
| Effort | ~4h | ~3h |

### Rationale

- `NewModelHelper` already caches `FileType` records and exposes `getEventTypes()` — metadata operations are a natural extension of the same cache
- `AccessControlService` is already available everywhere it is needed; no new wiring required
- Single cache = single source of truth, no risk of stale data divergence
- Saves ~6.5 hours across Tasks 3, 5, 7, 12, 15

---

## Appendix C — Computation Methods Refactoring (Task 1)

The original design proposed a separate `FileTypeMetadataUtil` class. During implementation, the computation methods were placed as `private static` methods directly on `MetadataBuilder` — the only consumer.

### Before

```java
private static final Set<String> DEX_REPORT_TYPES = new LinkedHashSet<>(Arrays.asList(
    "covidallmonthlyvaccination", "influenzavaccination", "routineimmunization",
    "rsvprevention", "measlesvaccination"
));

public MetadataBuilder setReportType(String reportType) {
    if (DEX_REPORT_TYPES.contains(reportType.toLowerCase())) {
        meta.setExtEvent(reportType);
    } else if ("farmerFlu".equalsIgnoreCase(reportType)) {
        meta.setExtEvent("farmerFluVaccination");
    } else {
        meta.setExtEvent(GENERIC);
        meta.setExtSourceVersion(Metadata.DEX_VERSION2);
    }
    meta.setExtEventType(reportType);
}
```

### After

```java
public MetadataBuilder setReportType(String reportType) {
    if (StringUtils.isBlank(reportType)) {
        errors.add("Report Type must be present and not empty");
        return this;
    }
    String metaExtEvent = computeMetaExtEvent(reportType);
    meta.setExtEvent(metaExtEvent);
    meta.setExtEventType(reportType);
    meta.setDataStreamId(IAccessControlService.computeDataStreamId(reportType));
    if (GENERIC.equals(metaExtEvent)) {
        meta.setExtSourceVersion(Metadata.DEX_VERSION2);
    }
    return this;
}
```

### Computation Method Test Cases

| Input | `computeMetaExtEvent` | `computePeriodType` | `computeDataStreamId` |
|-------|----------------------|--------------------|-----------------------|
| `routineImmunization` | `routineImmunization` | `QUARTERLY` | `routine-immunization` |
| `influenzaVaccination` | `influenzaVaccination` | `MONTHLY` | `influenza-vaccination` |
| `farmerFlu` | `farmerFluVaccination` | `MONTHLY` | `farmer-flu` |
| `genericImmunization` | `genericImmunization` | `BOTH` | `generic-immunization` |
| `rsvPrevention` | `rsvPrevention` | `MONTHLY` | `rsv-prevention` |
| `measlesVaccination` | `measlesVaccination` | `MONTHLY` | `measles-vaccination` |
| `covidAllMonthlyVaccination` | `covidAllMonthlyVaccination` | `MONTHLY` | `covid-all-monthly-vaccination` |

---

## Appendix D — Data Stream ID Algorithm

### Purpose

Convert camelCase/PascalCase fileTypeNames to hyphenated lowercase Azure data-stream IDs.
Example: `influenzaVaccination` → `influenza-vaccination`

### Rules

1. Insert a hyphen before an uppercase letter when either:
   - the previous character is lowercase (start of a new word), or
   - the next character is lowercase and position > 0 (last letter of an acronym)
2. Convert the entire result to lowercase
3. Never insert a hyphen at position 0

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
        char next    = (i + 1 < chars.length) ? chars[i + 1] : '\0';
        char prev    = (i > 0)                ? chars[i - 1] : '\0';
        boolean isUpper    = Character.isUpperCase(current);
        boolean nextIsLower = Character.isLowerCase(next);
        boolean prevIsLower = Character.isLowerCase(prev);
        if (isUpper && i > 0 && (prevIsLower || nextIsLower)) {
            result.append('-');
        }
        result.append(Character.toLowerCase(current));
    }
    return result.toString();
}
```

### Algorithm Test Cases

| Input | Output |
|-------|--------|
| `routineImmunization` | `routine-immunization` |
| `influenzaVaccination` | `influenza-vaccination` |
| `rsvPrevention` | `rsv-prevention` |
| `farmerFluVaccination` | `farmer-flu-vaccination` |
| `covidAllMonthlyVaccination` | `covid-all-monthly-vaccination` |
| `genericImmunization` | `generic-immunization` |
| `measlesVaccination` | `measles-vaccination` |
| `RIQuarterlyAggregate` | `ri-quarterly-aggregate` |
| `ABCDef` | `ab-cdef` |
| `ABC` | `abc` |
| `""` / `null` | `null` |

---

## Appendix E — CSV Filename Validation Specification

### Filename Structure

```
[Frequency][ReportType]_[Entity]_[Date].[extension]
```

### Component Rules

**Frequency + ReportType prefix**
- Frequency keyword (`Monthly` or `Quarterly`) detected case-insensitively anywhere in the first `_`-delimited segment; removed to yield the report type abbreviation
- No separator between frequency and report type abbreviation

**Entity ID** — format `[A-Z]{2}A` (2 uppercase letters + `A`); must match facilityId

**Date**
- Monthly: `YYYYMMM` (e.g., `2026FEB`) — must use a valid 3-letter month abbreviation
- Quarterly: `YYYYQ#` (e.g., `2026Q2`) — quarter must be 1–4
- Format must match frequency; date must match the `period` API parameter (after normalisation: uppercase, hyphens stripped)

**Extension** — `csv` or `zip` (case-insensitive)

### Validation Steps (in order)

1. Parse with regex — return failure if no match or no frequency keyword found
2. Frequency/period-type consistency — `MONTHLY` requires `Monthly`, `QUARTERLY` requires `Quarterly`, `BOTH` accepts either
3. Entity ID match — case-insensitive comparison against facilityId (skip if null)
4. Date/period match — normalise both sides (uppercase, strip hyphens) and compare (skip if null)

### Valid Test Cases

| Filename | periodType | entity | period | Valid? |
|----------|------------|--------|--------|--------|
| `MonthlyFlu_XXA_2026FEB.csv` | MONTHLY | XXA | 2026-FEB | ✅ |
| `QuarterlyRI_XXA_2026Q2.csv` | QUARTERLY | XXA | 2026Q2 | ✅ |
| `riQuarterlyAggregate_XXA_2025Q4.csv` | QUARTERLY | XXA | 2025Q4 | ✅ |
| `testMonthlyRSV_XXA_2022SEP.csv` | MONTHLY | XXA | 2022-SEP | ✅ (isTestFile=true) |
| `MonthlyFarmerFlu_XXA_2026FEB.csv` | MONTHLY | XXA | 2026-FEB | ✅ |

### Invalid Test Cases

| Filename | Error |
|----------|-------|
| `RIA_16M.csv` | No frequency keyword, pattern mismatch |
| `Flu_XXA_2026FEB.csv` | Missing frequency keyword |
| `MonthlyRI_XYZ_2026FEB.csv` | Entity `XYZ` doesn't end in `A` |
| `MonthlyRI_XXA_26FEB.csv` | Year must be 4 digits |
| `QuarterlyRI_XXA_2026Q5.csv` | Quarter must be 1–4 |
| `MonthlyRI_XXA_2026Q1.csv` | Monthly frequency with quarterly date |
| `MonthlyRI_XXA_2026JAN.csv` (period=2026-FEB) | Date doesn't match period |

### Period Normalisation

```java
String normalizedPeriod = period.trim().toUpperCase().replace("-", "");
// "2026-FEB" → "2026FEB"
// "2026Q2"   → "2026Q2"
```
