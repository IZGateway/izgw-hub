# Spec: Report Type Case-Insensitive Normalization

## Purpose

Define how submitted `reportType` values are normalized to their canonical form so that
case variations (`"covidall"`, `"CovidAll"`, `"covidAllMonthlyVaccination"`) all produce
identical metadata and blob storage paths.

## Requirements

### Registry lookup

- WHEN `setReportType(reportType)` is called, the system SHALL perform a three-tier
  lookup of `reportType` in the file-type registry:
  1. **Exact match** — case-sensitive against the stored `fileTypeName`.
  2. **Case-insensitive match** — handles casing variants (e.g. `"ROUTINEIMMUNIZATION"`
     → `"routineImmunization"`).
  3. **Noise-word stripped match** — strips the words `"vaccination"`, `"immunization"`,
     `"prevention"`, `"monthly"`, and `"quarterly"` (case-insensitively) from both the
     submitted value and each registry key before comparing. This allows legacy submission
     values that omit noise-word components (e.g. `"farmerFlu"`, `"covidall"`) to match
     their canonical registry entries and preserves backward compatibility.
- WHEN a match is found at any tier, the canonical `fileTypeName` from the registry
  record SHALL replace the submitted value for all subsequent computation in that request.
- WHEN no match is found at any tier, the system SHALL log a warning at WARN level and
  continue processing using the submitted value unchanged.

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
- Unit tests SHALL verify that noise-word stripped variants such as `"farmerFlu"`,
  `"rsv"`, `"influenza"`, and `"measles"` resolve to their canonical registry entries.
- Unit tests SHALL verify that without a service, the raw submitted value drives
  computation (no normalization occurs).
