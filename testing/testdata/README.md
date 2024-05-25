* MAA_*.zip
These are test files with fake patient data for use in testing Automated Data Submission (ADS) to CDC for larger Routine Immunization Submissions.

* Monthly*.csv
These are empty test files for sending Monthly COVID Reports via ADS to CDC.

* 2019_10_01_pat.csv and 2019_10_01_pat.csv 

This is fake patient data generated on October 9, 2021 for generating performance test QBP and VXU messages.

50,000 patients
307,967 immunizations

Children are simulated to receive typical immunizations for their age
Adults are simulated to receive COVID-19 vaccinations, with about 1 in 7 receiving only one dose

CSV formatted patient file:
<pre>
    "IIS Patient ID",       -- 1
    "Name - First",         -- 2
    "Name - Middle" ,       -- 3
    "Name - Last",          -- 4
    "Mothers Maiden Name",  -- 5
    "Guardian Name: First", -- 6
    "Mothers Name: Middle", -- 7
    "Mothers Name: Last",   -- 8
    "Date of Birth",        -- 9
    "Gender",               -- 10
    "Address: Street",      -- 11
    "Address: City",        -- 12 
    "Address: State",       -- 13
    "Address: Country",     -- 14
    "Address: Zip",         -- 15
    "Race",                 -- 16
    "Ethnicity",            -- 17
    "Telephone Number",     -- 18
    "Email address",        -- 19
    "Record Creation Date"  -- 20
</pre>
CSV formatted immunization file:
<pre>
    "IIS Patient ID",            -- 1
    "IIS Vaccination Event ID",  -- 2
    "Vaccine Type (CVX)",        -- 3
    "Vaccine Type (NDC)",        -- 4
    "Administration Date",       -- 5
    "Manufacturer",              -- 6
    "Lot Number",                -- 7
    "Event Record Type",         -- 8
    "Route of Administration",   -- 9
    "Site of Administration",    -- 10
    "Expiration Date",           -- 11
    "Dose Volume",               -- 12
    "Dose Level Eligibility",    -- 13
    "Vaccine Funding Source",    -- 14
    "Record Creation Date"       -- 15
</pre>
