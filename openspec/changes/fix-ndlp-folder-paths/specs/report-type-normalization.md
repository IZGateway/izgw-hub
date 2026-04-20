# Spec: Report Type Case-Insensitive Normalization

## Purpose

Define how submitted `reportType` values are normalized to their canonical form so that
case variations (`"covidall"`, `"CovidAll"`, `"covidAllMonthlyVaccination"`) all produce
identical metadata and blob storage paths.

## Requirements

### Registry lookup

- WHEN `setReportType(reportType)` is called, the system SHALL perform a case-insensitive
  lookup of `reportType` in the file-type registry.
- The lookup SHALL first attempt an exact-case match; if not found, it SHALL scan for a
  case-insensitive match.
- WHEN a match is found, the canonical `fileTypeName` from the registry record SHALL
  replace the submitted value for all subsequent computation in that request.
- WHEN no match is found, the system SHALL log a warning at WARN level and continue
  processing using the submitted value unchanged.

### Field values after normalization

- `meta_ext_event_type` SHALL always reflect the **submitted** (raw) value, not the
  canonical name. This preserves auditability of what was actually sent.
- `meta_ext_event` and `data_stream_id` SHALL be computed from the **canonical** name.

### No-service fallback

- WHEN no `IAccessControlService` is injected, normalization SHALL be skipped entirely.
  All fields SHALL be computed directly from the submitted value.
- This path is used in unit tests and lightweight contexts without a full service layer.

### Test coverage

- Unit tests SHALL verify that case variants of at least `farmerFlu` and
  `covidAllMonthlyVaccination` produce the same `data_stream_id` as the canonical form
  when a registry service is present.
- Unit tests SHALL verify that without a service, the raw submitted value drives
  computation (no normalization occurs).
