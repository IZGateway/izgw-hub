# Quick Reference: Adding a New ADS Report Type

## For Administrators (No Code Changes Required!)

### Prerequisites
- Admin access to izg-configuration-console
- Knowledge of report type requirements from CDC/NDLP
- Understanding of submission frequency (monthly vs quarterly)

### Steps to Add New Report Type

#### 1. Access File Type Management
Navigate to: **izg-configuration-console → Access Control → File Type List**

#### 2. Click "Add to File Type List"

#### 3. Fill in Required Fields

**File Type Name** (required)
- Enter the report type name exactly as it will appear in API requests
- Use camelCase or PascalCase (e.g., "measlesVaccination", "rsvPrevention")
- This value will be matched case-insensitively in API requests

**ID** (required)
- Enter a unique sort key (sequential number recommended)
- Used for ordering in UI lists

**Description** (required)
- Clear description of the report type
- Include program name and submission requirements
- Example: "Measles vaccination monthly reporting for NDLP"

**Meta Ext Event** (required)
- Value for Azure metadata field `meta_ext_event`
- Typically same as File Type Name but may differ
- Example: "measlesVaccination"
- Special cases:
  - Use "genericImmunization" for catch-all types
  - Use "farmerFluVaccination" for farmerFlu report type

**Meta Ext Event Type** (optional)
- Value for Azure metadata field `meta_ext_event_type`
- Defaults to File Type Name if left blank
- Usually leave blank unless CDC specifies different value

**Period Type** (required)
- Select from dropdown: MONTHLY, QUARTERLY, or BOTH
- Determines expected date format in filenames:
  - MONTHLY: Filenames must use YYYYMMM format (e.g., 2025JAN)
  - QUARTERLY: Filenames must use YYYYQ# format (e.g., 2025Q1)
  - BOTH: Accepts either format

**Active** (default: checked)
- Check to enable this report type for submissions
- Uncheck to disable without deleting (can reactivate later)

**Filename Pattern** (optional, advanced)
- Leave blank to use standard validation
- Enter custom regex pattern if special validation needed
- Contact izgateway@cdc.gov for assistance with custom patterns

#### 4. Preview Computed Fields

**Data Stream ID** (computed, read-only)
- Automatically computed from File Type Name
- Used in Azure metadata field `data_stream_id`
- Preview shown in form before saving
- Example: "measlesVaccination" → "measles-vaccination"

#### 5. Save

Click **"Add to File Type List"** button

#### 6. Verify

- New report type appears in File Type List
- Status shows "Active" (green chip)
- Computed data stream ID is correct

### Testing the New Report Type

#### 7. Test Upload

Use the ADS upload endpoint with your new report type:

```bash
curl -X POST "https://izgw-hub/rest/ads/dex-dev" \
  -H "Content-Type: multipart/form-data" \
  -F "facilityId=XXA" \
  -F "reportType=measlesVaccination" \
  -F "period=2026-FEB" \
  -F "file=@MonthlyMeasles_XXA_2026FEB.csv"
```

#### 8. Verify Metadata

Check response metadata includes:
```json
{
  "meta_ext_event": "measlesVaccination",
  "meta_ext_event_type": "measlesVaccination",
  "data_stream_id": "measles-vaccination",
  "meta_ext_submissionperiod": "2025-JAN"
}
```

## Filename Requirements

### Valid Filename Format

```
[Frequency][ReportType]_[Entity]_[Date].[extension]
```

**Example Monthly:**
```
MonthlyFlu_XXA_2026FEB.csv
```

**Example Quarterly:**
```
QuarterlyRI_XXA_2026Q2.csv
```

### Component Requirements

1. **Frequency + Report Type:** Must start with "Monthly" or "Quarterly" followed immediately by report type abbreviation (no separator)
2. **Entity:** 3 uppercase letters ending in 'A' (e.g., MAA, NYA, XXA), preceded by underscore
3. **Date:** Preceded by underscore
   - Monthly: YYYYMMM (e.g., 2026FEB, 2025DEC)
   - Quarterly: YYYYQ# (e.g., 2026Q2, 2025Q4)
4. **Extension:** .csv or .zip

### Common Validation Errors

❌ **"Filename must start with 'Monthly' or 'Quarterly'"**
- Fix: Add frequency keyword at start (capital first letter)
- Example: Change "RI_XXA_2026Q2.csv" to "QuarterlyRI_XXA_2026Q2.csv"

❌ **"Entity ID must be 3 uppercase letters ending in 'A'"**
- Fix: Ensure entity code ends with A
- Example: Change "XYZ" to "XXA" (for testing)

❌ **"Date format must be YYYYQ# for quarterly submissions"**
- Fix: Use quarterly format for quarterly reports
- Example: Change "2026FEB" to "2026Q1"

❌ **"Filename starts with 'Monthly' but date is in quarterly format"**
- Fix: Match frequency with date format
- Example: Change "MonthlyRI_XXA_2026Q1.csv" to "QuarterlyRI_XXA_2026Q1.csv"

## Troubleshooting

### Report Type Not Accepted

**Check:**
1. Is file type marked as "Active" in UI?
2. Does file type name match API request exactly (case-insensitive)?
3. Has cache been cleared? (Wait 5 minutes or contact admin)

**Resolution:**
- Edit file type and ensure "Active" is checked
- Verify File Type Name spelling
- Clear cache via management endpoint if urgent

### Filename Validation Fails

**Check:**
1. Does filename start with "Monthly" or "Quarterly" (capital first letter)?
2. Is entity code exactly 3 uppercase letters ending in 'A'?
3. Does date format match frequency (YYYYMMM for Monthly, YYYYQ# for Quarterly)?
4. Are components separated by underscores (not hyphens)?

**Resolution:**
- Review filename requirements above
- Pattern: `[Frequency][ReportType]_[Entity]_[Date].[ext]`
- Example: `MonthlyFlu_XXA_2026FEB.csv`
- Use validation endpoint: `GET /rest/ads/reportTypes/{reportType}/validate?filename={filename}`
- Contact izgateway@cdc.gov for assistance

### Data Stream ID Incorrect

**Check:**
- Review computed data stream ID in UI preview
- Verify File Type Name uses correct capitalization

**Override if needed:**
- Contact admin to set explicit dataStreamIdTemplate
- Rarely needed - algorithm handles 99% of cases

## API Endpoints

### List Available Report Types
```
GET /rest/ads/reportTypes
```

Response:
```json
[
  {
    "reportType": "measlesVaccination",
    "description": "Measles vaccination monthly reporting",
    "periodType": "MONTHLY",
    "dataStreamId": "measles-vaccination"
  },
  ...
]
```

### Upload File
```
POST /rest/ads/{destinationId}
```

Parameters:
- `facilityId`: Entity code (e.g., MAA, NYA)
- `reportType`: Report type name from File Type List
- `period`: Submission period (YYYY-MMM or YYYYQ#)
- `file`: File to upload
- `filename`: Optional, defaults to uploaded filename
- `force`: Optional, set to true to skip validation

## Data Stream ID Computation Examples

Use these examples to verify correct File Type Names:

| File Type Name | → | Data Stream ID |
|----------------|---|----------------|
| `routineImmunization` | → | `routine-immunization` |
| `influenzaVaccination` | → | `influenza-vaccination` |
| `rsvPrevention` | → | `rsv-prevention` |
| `covidAllMonthlyVaccination` | → | `covid-all-monthly-vaccination` |
| `measlesVaccination` | → | `measles-vaccination` |
| `farmerFluVaccination` | → | `farmer-flu-vaccination` |
| `genericImmunization` | → | `generic-immunization` |
| `RIQuarterlyAggregate` | → | `ri-quarterly-aggregate` |

**Rule:** Hyphen inserted before each uppercase letter that starts a new word, then lowercase entire result.

## Getting Help

**Technical Issues:**
- Check logs for detailed error messages
- Review validation errors in API response
- Use validation endpoint to test before upload

**Configuration Questions:**
- Email: izgateway@cdc.gov
- Include: Report type name, description, CDC program reference

**Emergency Support:**
- For urgent new report type needs, contact on-call developer
- Developer can add via database seeding script if UI unavailable
