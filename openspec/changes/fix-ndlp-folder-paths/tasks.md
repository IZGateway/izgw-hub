# Tasks: fix-ndlp-folder-paths

Jira: IGDD-2775

## 1. Code changes

- [ ] 1.1 In `MetadataBuilder.setReportType()`, delete the line `meta.setDataStreamId(computeDataStreamId(reportType))` and remove its mention from the method's Javadoc `@code data_stream_id` bullet.
- [ ] 1.2 In `Metadata.getDataStreamId()`, replace the entire switch body with `return MetadataBuilder.computeDataStreamId(getExtEvent());` and update the Javadoc comment block above it.

## 2. Tests

- [ ] 2.1 Add a `@ParameterizedTest` to `MetadataBuilderTests` that calls `setReportType(x)` and asserts `getDataStreamId()` for every known file type:
  - `farmerFlu` → `farmer-flu-vaccination` (primary regression case)
  - `routineImmunization` → `routine-immunization`
  - `influenzaVaccination` → `influenza-vaccination`
  - `rsvPrevention` → `rsv-prevention`
  - `covidAllMonthlyVaccination` → `covid-all-monthly-vaccination`
  - `covidBridgeVaccination` → `covid-bridge-vaccination`
  - `genericImmunization` → `generic-immunization`
  - `measlesVaccination` → `measles-vaccination`
- [ ] 2.2 Run `mvn test` and confirm all tests green including `ComputeDataStreamIdTests`.

## 3. Validation

- [ ] 3.1 Run `mvn compile -q` — confirm zero errors.
- [ ] 3.2 Run `mvn javadoc:javadoc` — confirm zero errors.

## 5. Report types discovery endpoint

- [x] 5.1 Add `GET /rest/ads/reportTypes` to `ADSController` returning `config.getAccessControls().getEventTypes()` as `List<String>`.
- [x] 5.2 Confirm the existing `@Schema` description on `reportType` parameter already references this URL (no change needed).


- [ ] 4.1 After deployment to onboarding environment, resubmit `farmerFlu` test file targeting path `ext-immunization-izgw/farmer-flu-vaccination/`.
- [ ] 4.2 Confirm `covidAllMonthlyVaccination` path and resubmit if affected.
- [ ] 4.3 Confirm receipt with Juan Alvarado (wok1@cdc.gov).
