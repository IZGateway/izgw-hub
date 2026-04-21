# Metadata Requirements for NDLP Submission

**Source:** [`DMI_IZGW_to_NDLP_Routine_Immunizations_Spec.xlsx`](DMI_IZGW_to_NDLP_Routine_Immunizations_Spec.xlsx) — "Metadata Fields For Submission" tab

This spec defines the metadata fields that IZGW must attach when submitting files to the
NDLP DEX v2 storage container. Only fields with scope `NDLP` or `DEX 2.0` are included;
fields set by DEX itself (not sent by IZGW) are noted as not applicable.

---

## File-Level Metadata Fields

| Field | Value / Description | Scope | Optionality |
|---|---|---|---|
| `meta_destination_id` | `"NDLP"` | DEX 2.0 | Required |
| `sender_id` | `"IZGW"` | DEX 2.0 | Required |
| `meta_ext_source` | `"IZGW"` | NDLP | Required |
| `meta_ext_sourceversion` | One of: `V2022-12-31`, `V2023-09-01`, `V2024-09-04` | DEX 2.0 / NDLP | Required |
| `data_stream_id` | See [Data Stream IDs](#data-stream-ids) | DEX 2.0 | Required |
| `meta_ext_event` | See [Event Values](#event-values) | NDLP | Required |
| `data_stream_route` | `csv` or `other` | DEX 2.0 | Required |
| `meta_ext_event_type` | See [Event Type Values](#event-type-values) | NDLP | Deprecated |
| `meta_ext_entity` | Three-digit entity/grantee code (e.g. `NYA`, `CV1`, `DD2`). See Value Sets tab. | NDLP | Required |
| `data_producer_id` | Three-digit entity/grantee code — identifies the sender. | DEX 2.0 | Required |
| `jurisdiction` | Three-digit entity/grantee code — identifies the jurisdiction. | DEX 2.0 | Required |
| `meta_username` | Username of submitting user, or service account for system-to-system. | DEX 2.0 / NDLP | Required |
| `meta_ext_objectkey` | UUID tracking back to the source filename/object ID. e.g. `81bc2718-aef5-479b-8d67-955a2e75ac53` | DEX 2.0 / NDLP | Required |
| `received_filename` | Original filename submitted to IZGW. Must follow DUA naming guidelines. | DEX 2.0 | Required |
| `meta_ext_filename` | Original filename submitted to IZGW. Must follow DUA naming guidelines. | NDLP | Required |
| `meta_ext_file_timestamp` | Datetime the file was submitted to IZGW. | DEX 2.0 / NDLP | Required |
| `meta_ext_submissionperiod` | See [Submission Period Formats](#submission-period-formats) | DEX 2.0 / NDLP | Required |
| `version` | `2.0` — version of metadata associated with the file. | DEX 2.0 | Optional |
| `meta_schema_version` | `2.0` — version of metadata associated with the file. | NDLP | Optional |
| `meta_ext_testfile` | `yes` or `no` — indicates test vs. production data. Absent = production. | NDLP | Optional |

### Fields Set by DEX (not sent by IZGW)

| Field | Description |
|---|---|
| `dex_ingest_datetime` | Set by DEX and returned in the response. |
| `upload_id` | Set by DEX and returned in the response. |

---

## Data Stream IDs

The `data_stream_id` determines the DEX storage container. It is computed from the
`extEvent` using the camelCase-to-kebab algorithm in `MetadataBuilder.computeDataStreamId()`.

| Report Type (`extEvent`) | `data_stream_id` |
|---|---|
| `routineImmunization` | `routine-immunization` |
| `influenzaVaccination` | `influenza-vaccination` |
| `rsvPrevention` | `rsv-prevention` |
| `covidAllMonthlyVaccination` | `covid-all-monthly-vaccination` |
| `farmerFluVaccination` | `farmer-flu-vaccination` |
| `measlesVaccination` | `measles-vaccination` |
| `riQuarterlyAggregate` | `ri-quarterly-aggregate` |

> **Note:** The source spec uses `"farmer-flu-vaccination"` — not `"farmer-flu"`. This
> is the primary defect corrected by this CR. See design Decision 1.

---

## Event Values

The `meta_ext_event` field (NDLP scope) drives storage container routing.

| Report Type | `meta_ext_event` |
|---|---|
| Routine Immunization | `routineImmunization` |
| Influenza | `influenzaVaccination` |
| RSV | `rsvPrevention` |
| COVID monthly aggregate | `covidallmonthlyVaccination` |
| Farmer Flu | `farmerfluVaccination` |
| Measles | `measlesVaccination` |
| RI Quarterly Aggregate | `riQuarterlyAggregate` |

---

## Event Type Values

`meta_ext_event_type` is **deprecated**. It was used to drive NDLP routing for files with
`data_stream_id = "genericImmunization"`. IZGW should not rely on this field for new submissions.

| Report Type | `meta_ext_event_type` |
|---|---|
| Routine Immunization | `routineImmunization` |
| Influenza | `influenzaVaccination` |
| RSV | `rsvPrevention` |
| COVID monthly aggregate | `covidallmonthlyVaccination` |
| Farmer Flu | `farmerFlu` |
| Measles | `measlesVaccination` |
| RI Quarterly Aggregate | `riQuarterlyAggregate` |

---

## Submission Period Formats

The `meta_ext_submissionperiod` format depends on the event type:

| Event Type | Format | Example |
|---|---|---|
| `routineImmunization` | `YYYYQ#` | `2022Q1` |
| `riQuarterlyAggregate` | `YYYYQ#` | `2026Q1` |
| `influenzaVaccination` | `YYYY-MON` | `2023-FEB` |
| `rsvPrevention` | `YYYY-MON` | `2023-FEB` |
| `covidAllMonthlyVaccination` | `YYYY-MON` | `2023-FEB` |
| `farmerFlu` | `YYYY-MON` | `2023-FEB` |
| `measlesVaccination` | `YYYY-MON_WK` | *(format TBD)* |
