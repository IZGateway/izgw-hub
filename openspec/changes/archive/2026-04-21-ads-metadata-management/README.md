# ADS Metadata Management Simplification

**Status:** In Progress  
**Created:** 2026-03-16 | **Updated:** 2026-03-26  
**Approach:** Computation-Only (v3.0) — no database changes, no UI changes  
**Estimated Effort:** 12 hours (1.5 days)

Replace hardcoded metadata mappings in `MetadataBuilder` and `ADSController` with values
computed from the existing `fileTypeName` field and looked up from the existing
`AccessControlService` / `NewModelHelper` FileType cache.

## Documents

| File | Purpose |
|------|---------|
| [proposal.md](./proposal.md) | Why, what changes, impact, non-goals |
| [design.md](./design.md) | Architecture, component design, algorithms, appendices (filename patterns, design decision, data-stream-id algorithm, validation spec) |
| [tasks.md](./tasks.md) | 12 tasks with acceptance criteria, status, and review checklist |

## Key Constraints

- ✅ NO changes to `FileType` entity or DynamoDB schema
- ✅ NO changes to `izg-configuration-console` UI
- ✅ All metadata computed from existing `fileTypeName` field
- ✅ Reuse existing `AccessControlService` / `NewModelHelper` FileType cache

## Status Log

| Date | Event |
|------|-------|
| 2026-03-16 | Change request created |
| 2026-03-16 | Filename pattern corrected (underscores, not hyphens; PascalCase frequency) |
| 2026-03-16 | Design decision: enhance `AccessControlService` instead of new `FileTypeService` |
| 2026-03-17 | Approach locked to computation-only (v3.0) |
| 2026-03-19 | Task 1 complete — computation methods added to `MetadataBuilder`; `DEX_REPORT_TYPES` removed |
| 2026-03-25 | Tasks 2–6 complete — `IAccessControlService.getFileType`, `CsvFilenameValidator`, `MetadataBuilder` refactored, `MetadataImpl.dataStreamId`, `ADSController` endpoint |
| 2026-03-26 | Classes renamed: `ParsedFilename` → `ZipFilenameComponents`, `FilenameValidator` → `CsvFilenameValidator`, etc. |