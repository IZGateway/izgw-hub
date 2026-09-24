## Why

Two ADS submission folder paths were mismatched against NDLP's mapping table, causing
CDC to be unable to process submitted files. `MetadataBuilder.setReportType()` eagerly
stored `computeDataStreamId(reportType)` — a pure camelCase→kebab conversion of the raw
submitted value — so `farmerFlu` produced `farmer-flu` where NDLP expects
`farmer-flu-vaccination`, and lower-cased aliases such as `covidall` produced un-hyphenated
paths (`covidallmonthly-vaccination`) instead of `covid-all-monthly-vaccination`.
Jira: [IGDD-2775](https://izgateway.atlassian.net/browse/IGDD-2775).

## What Changes

- Stop pre-computing `data_stream_id` in `MetadataBuilder.setReportType()`; compute it
  lazily in `Metadata.getDataStreamId()` from `meta_ext_event` (which already carries the
  `farmerFlu` → `farmerFluVaccination` special case).
- Replace the hardcoded `switch` in `Metadata.getDataStreamId()` with a single call to
  `MetadataBuilder.computeDataStreamId(getExtEvent())`.
- Normalize the submitted `reportType` to its canonical registry name before any
  computation, using a three-tier match (exact → case-insensitive → noise-word stripped)
  in `NewModelHelper.getFileType()` and `ADSUtils.matchReportType()`.
- Reject REST submissions whose `reportType` matches no registered type
  (`ADSController.normalizeReportType()`).
- Add `GET /rest/ads/reportTypes` so callers can discover valid values (deferred from
  `ads-metadata-management`).
- Update the Postman collection and the existing `compute-data-stream-id` / `compute-meta-ext-event`
  specs so they no longer imply `farmerFlu` produces `farmer-flu`.
- Resubmit the affected test files to the NDLP onboarding container after deployment.

## Capabilities

### New Capabilities

- `ads-folder-path-computation`: `data_stream_id` is derived from `meta_ext_event` (not
  from the raw `reportType`) and is never pre-computed during `setReportType()`.
- `report-type-normalization`: three-tier canonical-name resolution for submitted
  `reportType` values, including backward-compatible legacy aliases, controller-level
  rejection of unregistered types, and the `GET /rest/ads/reportTypes` discovery endpoint.

### Modified Capabilities

- `compute-data-stream-id`: implementation location corrected to `MetadataBuilder`; input
  is now `meta_ext_event` rather than the raw `reportType`.
- `compute-meta-ext-event`: aligned to current code (null/empty → `genericImmunization`;
  `meta_ext_event_type` is the normalized report type; no `computeMetaExtEventType()`).

## Impact

- **`MetadataBuilder.java`**: remove eager `setDataStreamId()`; normalize `reportType` via
  registry lookup.
- **`Metadata.java`**: `getDataStreamId()` delegates to `computeDataStreamId(getExtEvent())`.
- **`NewModelHelper.java`**: three-tier `getFileType()` lookup; `fileTypeCache` package-private.
- **`ADSUtils.java`**: `NOISE_WORDS`, `stripNoiseWords()`, `matchReportType()`.
- **`ADSController.java`**: controller-level `reportType` validation; `GET /ads/reportTypes`.
- **Tests**: `MetadataBuilderSetReportTypeTests`, `NewModelHelperGetFileTypeTests`.
- **Postman**: `farmerFlu` test asserts `data_stream_id = farmer-flu-vaccination`.
- **NDLP onboarding**: manual resubmission of `farmerFlu` and `covidAllMonthly` test files.
- **No database or UI changes.**
