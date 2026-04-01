| Input reportType Value | meta_ext_event (Blob Metadata) | meta_ext_event_type (Blob Metadata) | data_stream_id (Computed) | Notes | 
|-------------------------|----------------------------------|---------------------------------------|----------------------------|--------|
| covidAllMonthlyVaccination | covidAllMonthlyVaccination | covidAllMonthlyVaccination | covid-all-monthly-vaccination | Standard DEX report type | 
| influenzaVaccination | influenzaVaccination | influenzaVaccination | influenza-vaccination | Standard DEX report type |
| routineImmunization | routineImmunization | routineImmunization | routine-immunization | Standard DEX report type |
| rsvPrevention | rsvPrevention | rsvPrevention | rsv-prevention | Standard DEX report type |
| measlesVaccination | measlesVaccination | measlesVaccination | measles-vaccination | Standard DEX report type |
| farmerFlu | farmerFluVaccination | farmerFlu | farmer-flu-vaccination | Special case - event name differs |
| farmerFluVaccination | farmerFluVaccination | farmerFluVaccination | farmer-flu-vaccination | Fix special case - event name differs |
| riQuarterlyAggregate | riQuarterlyAggregate | riQuarterlyAggregate | ri-quarterly-aggregate | Aggregated Routine Immunization report |
| Any other value | genericImmunization | [original reportType value] | generic-immunization | Forces meta_ext_sourceversion to V2024-09-04 (DEX 2.0) |