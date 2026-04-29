## Why

Two ADS submission folder paths are mismatched against NDLP's mapping table, causing
CDC to be unable to process submitted files. The `computeDataStreamId()` algorithm
in `ads-metadata-management` produces `farmer-flu` for `farmerFlu` but NDLP expects
`farmer-flu-vaccination`; the `covidAllMonthly` file type was submitted with a legacy
un-hyphenated path (`covidallmonthly-vaccination`) instead of the correctly-computed
`covid-all-monthly-vaccination`. Jira: [IGDD-2775](https://izgateway.atlassian.net/browse/IGDD-2775).

## What Changes

- Add a special-case override in `computeDataStreamId()` so that `farmerFlu` produces
  `farmer-flu-vaccination` (not `farmer-flu`).
- Verify that `covidAllMonthlyVaccination` correctly produces `covid-all-monthly-vaccination`
  via the standard camelCase-to-kebab algorithm (no code change expected — submission error only).
- Update the computation table in `ads-metadata-management` design/specs to reflect the
  correct `data_stream_id` for `farmerFlu`.
- Resubmit the affected test files to the NDLP onboarding container with the corrected
  folder paths once the code fix is deployed.

## Capabilities

### New Capabilities

<!-- None — this is a targeted bug fix to existing computation logic. -->

### Modified Capabilities

- `ads-folder-path-computation`: Correct the `computeDataStreamId()` special-case mapping
  for `farmerFlu` from `farmer-flu` to `farmer-flu-vaccination`, and add a unit test
  asserting both corrected values.

## Impact

- **`MetadataBuilder.java` / `FileTypeMetadataUtil.java`**: `computeDataStreamId()` special
  case for `farmerFlu`.
- **`MetadataBuilderTests.java` / `FileTypeMetadataUtilTests.java`**: Updated expected value
  for `farmerFlu`; new assertion for `covidAllMonthlyVaccination`.
- **NDLP onboarding**: Manual resubmission of `farmerFlu` and `covidAllMonthly` test files
  to correct NDLP container folders after code is deployed.
- **No database or UI changes.**
