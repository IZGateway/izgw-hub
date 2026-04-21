# Tasks: fix-ndlp-folder-paths

Jira: IGDD-2775

## 1. Code changes

- [x] 1.1 In `MetadataBuilder.setReportType()`, delete the line `meta.setDataStreamId(computeDataStreamId(reportType))` and remove its mention from the method's Javadoc `@code data_stream_id` bullet.
- [x] 1.2 In `Metadata.getDataStreamId()`, replace the entire switch body with `return MetadataBuilder.computeDataStreamId(getExtEvent());` and update the Javadoc comment block above it.
- [x] 1.3 In `MetadataBuilder.setReportType()`, after a successful registry lookup add `reportType = fileType.getFileTypeName();` to normalize to canonical casing before any computation. Update Javadoc to note case-insensitive normalization.

## 2. Tests

- [ ] 2.1 Add a `@ParameterizedTest` to `MetadataBuilderTests` that calls `setReportType(x)` and asserts `getDataStreamId()` for every known file type using canonical casing:
  - `farmerFlu` → `farmer-flu-vaccination` (primary regression case)
  - `routineImmunization` → `routine-immunization`
  - `influenzaVaccination` → `influenza-vaccination`
  - `rsvPrevention` → `rsv-prevention`
  - `covidAllMonthlyVaccination` → `covid-all-monthly-vaccination`
  - `covidBridgeVaccination` → `covid-bridge-vaccination`
  - `genericImmunization` → `generic-immunization`
  - `measlesVaccination` → `measles-vaccination`
- [ ] 2.2 Add case-variant assertions to the same test (or a separate parameterized test) confirming that incorrect-casing inputs produce the same correct output:
  - `"covidall"` → `"covid-all-monthly-vaccination"`
  - `"farmerflu"` → `"farmer-flu-vaccination"`
  - `"ROUTINEIMMUNIZATION"` → `"routine-immunization"`
- [ ] 2.3 Run `mvn test` and confirm all tests green including `ComputeDataStreamIdTests`.

## 3. Validation

- [ ] 3.1 Run `mvn compile -q` — confirm zero errors.
- [ ] 3.2 Run `mvn javadoc:javadoc` — confirm zero errors.

## 4. Postman

- [ ] 4.1 Update Postman collection line asserting `data_stream_id` for farmerFlu test: change `"farmer-flu"` → `"farmer-flu-vaccination"`.

## 5. Report types discovery endpoint

- [x] 5.1 Add `GET /rest/ads/reportTypes` to `ADSController` returning `config.getAccessControls().getEventTypes()` as `List<String>`.
- [x] 5.2 Confirm the existing `@Schema` description on `reportType` parameter already references this URL (no change needed).

## 6. Post-deploy

- [ ] 6.1 After deployment to onboarding environment, resubmit `farmerFlu` test file targeting path `ext-immunization-izgw/farmer-flu-vaccination/`.
- [ ] 6.2 Confirm `covidAllMonthlyVaccination` path and resubmit if affected.
- [ ] 6.3 Confirm receipt with Juan Alvarado (wok1@cdc.gov).
