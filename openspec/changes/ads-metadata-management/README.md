# ADS Metadata Management Simplification

**Status:** Planning  
**Created:** 2026-03-16  
**Updated:** 2026-03-18  
**Approach:** Computation-Only (v3.0)  
**Estimated Effort:** 12 hours (1.5 days)

## Overview

This change simplifies metadata management for Automated Data Submission (ADS) components shipping data to CDC endpoints (formerly known as DEX, now using Azure Blob Storage). The feature enables dynamic metadata configuration through the izg-configuration-console's File Type management screens, replacing hardcoded report type mappings with a database-driven approach.

## Current State

### Hardcoded Metadata Mapping
The current implementation has hardcoded report type mappings in multiple locations:
- `MetadataBuilder.DEX_REPORT_TYPES` - Static set of known report types
- `MetadataBuilder.setReportType()` - Hardcoded switch logic for meta_ext_event mapping
- `Metadata.getDataStreamId()` - Hardcoded switch logic for data_stream_id computation
- `ADSController.postADSFile()` - Hardcoded allowable values in @Schema annotation
- `ADSController.normalizeReportType()` - Validation against hardcoded access control list

### Manual Metadata Management
- Adding new report types requires code changes in multiple classes
- No centralized configuration for metadata field generation
- Filename validation is partially implemented but not comprehensive
- No database-driven approach for report type configuration

### Existing Infrastructure
- **FileType entity and repository** already exist in DynamoDB
- **izg-configuration-console** has UI screens for managing File Type List
- **Access Control service** provides event type validation
- Filename parsing logic exists in `ParsedFilename` class

## Proposed Changes

**COMPUTATION-ONLY APPROACH:**

1. **Create FileTypeMetadataUtil** - Static utility to compute metadata from fileTypeName
2. **Enhance AccessControlService** - Add 2 methods (getFileType, computeDataStreamId)
3. **Create FilenameValidator** - Comprehensive filename validation
4. **Refactor MetadataBuilder** - Use computed values instead of hardcoded switch statements
5. **Update ADSController** - Add discovery endpoint, remove hardcoded allowableValues

**NO database changes, NO UI changes, NO new entity fields!**

### Key Algorithms
- **computePeriodType()** - Infer from fileTypeName (contains "quarter" → QUARTERLY, etc.)
- **computeMetaExtEvent()** - Return fileTypeName (exception: farmerFlu → farmerFluVaccination)
- **computeDataStreamId()** - Apply hyphenation algorithm (influenzaVaccination → influenza-vaccination)

## Key Benefits

- **Simplified Maintenance:** Add new report types through UI without code changes
- **Centralized Configuration:** Single source of truth for metadata field mappings in DynamoDB
- **Automated Validation:** Comprehensive filename validation based on report type patterns
- **Dynamic API Documentation:** OpenAPI specs reflect currently configured report types
- **Better Governance:** File type changes tracked through configuration console audit trail

## Documents

- [Proposal](./proposal.md) - Detailed rationale and impact analysis
- [Design](./design.md) - Architecture, algorithms, and implementation approach
- [Tasks](./tasks.md) - Breakdown of implementation work (8 tasks)
- [Specs](./specs/) - Technical specifications and validation rules

## Dependencies

- Existing FileType entity and FileTypeRepository in DynamoDB
- izg-configuration-console File Type management screens
- No new external dependencies required

## Related Changes

- **Future:** Extend to support additional metadata customization per jurisdiction
- **Future:** Add metadata validation rules engine for complex business logic

## Next Steps

1. Review and approve computation-only approach (see proposal.md)
2. Create FileTypeMetadataUtil with computation algorithms
3. Enhance AccessControlService with 2 new methods
4. Create FilenameValidator utility
5. Refactor MetadataBuilder to use computed values
6. Update ADSController for discovery endpoint
7. Test with all existing file types
8. Deploy (no migration needed!)
