INSERT INTO accesscontrol (category, name, member, allow)
VALUES('group','admin','*.testing.izgateway.org',true),
      ('group','admin','*.ehealthsign.com',true),
      ('group','admin','*.aphl.org',true),
      ('group','operations','admin',true),
      ('group','operations','uptrends.izgateway.org',true),
      ('group','operations','securityrs.com',true),
      ('group','users','*',true),
      ('group','soap','users',true),('group','ads','admin',true),
      ('group','ads','adspilot',true),
      ('group','adspilot','KY-test.izgateway.envisiontechnology.com',true),
      ('group','adspilot','AR-test.izgateway.envisiontechnology.com',true),
      ('group','adspilot','NM-test.izgateway.envisiontechnology.com',true),
      ('group','adspilot','NV-test.izgateway.envisiontechnology.com',true),
      ('group','adspilot','onc.envisiontechnology.com',true),
      ('group','adspilot','izgateway.stchealthops.com',true),
      ('group','adspilot','izgateway2.stchealthops.com',true),
      ('group','adspilot','izg-onboarding.immunize.nyc',true),
      ('group','internal','*.phiz-project.org',true),
      ('group','internal','admin',true),
      ('eventToRoute','routineImmunization','ndlp-izgw-ri',true),
      ('eventToRoute','routineImmunization','azurite',true),
      ('eventToRoute','routineImmunization','azurite_http',true),
      ('eventToRoute','routineImmunization','dex-dev',true),
      ('eventToRoute','routineImmunization','dex-stg',true),
      ('eventToRoute','influenzaVaccination','ndlp-izgw-flu',true),
      ('eventToRoute','influenzaVaccination','azurite',true),
      ('eventToRoute','influenzaVaccination','azurite_http',true),
      ('eventToRoute','influenzaVaccination','dex-dev',true),
      ('eventToRoute','rsvPrevention','dex-dev',true),
      ('eventToRoute','rsvPrevention','dex',true),
      ('eventToRoute','covidallMonthlyVaccination','dex-dev',true),
      ('eventToRoute','covidallMonthlyVaccination','dex',true),
      ('eventToRoute','covidbridgeVaccination','dex-dev',true),
      ('eventToRoute','covidbridgeVaccination','dex',true);
      
INSERT INTO `jurisdiction` (jurisdiction_id, name, description, dest_prefix) VALUES
     (1, 'DEVELOPMENT', 'Development Testing', 'dev'), 
     (2, 'CDC', 'Centers for Disease Control', 'dex'),
     (3, 'AK VacTrAK','Alaska', 'ak'),
     (4, 'AL ImmPRINT','Alabama', 'al'),
     (5, 'AR WebIZ','Arkansas', 'ar'),
     (6, 'AZ ASIIS','Arizona', 'az'),
     (7, 'CA CAIR','California', 'ca'),
     (8, 'CO CIIS','Colorado', 'co'),
     (9, 'CT WiZ','Connecticut', 'ct'),
     (10, 'DC DOCIIS','District of Columbia', 'dc'),
     (11, 'DE DelVAX','Delaware', 'de'),
     (12, 'FL SHOTS','Florida', 'fl'),
     (13, 'GA GRITS','Georgia', 'ga'),
     (14, 'HI HIR','Hawaii', 'ha'),
     (15, 'IA IRIS','Iowa', 'ia'),
     (16, 'ID IRIS','Idaho', 'io'),
     (17, 'IL I-CARE','Illinois', 'il'),
     (18, 'IN CHIRP','Indiana', 'in'),
     (19, 'KS WebIZ','Kansas', 'ks'),
     (20, 'KY KYIR','Kentucky', 'ky'),
     (21, 'LA LINKS','Louisiana', 'la'),
     (22, 'MA MIIS','Massachusetts', 'ma'),
     (23, 'MD IMMUNET','Maryland', 'md'),
     (24, 'ME ImmPact2','Maine', 'me'),
     (25, 'MI MCIR','Michigan', 'mi'),
     (26, 'MN MIIC','Minnesota', 'mn'),
     (27, 'MO ShowMeVax','Missouri', 'mi'),
     (28, 'MS MIIX','Mississippi', 'ms'),
     (29, 'MT imMTrax','Montana', 'mt'),
     (30, 'NCIR','North Carolina', 'nc'),
     (31, 'ND SIIS','North Dakota', 'nd'),
     (32, 'NE NESIIS','Nebraska', 'nb'),
     (33, 'NH VaxNH','New Hampshire', 'nh'),
     (34, 'NJ NJIIS','New Jersey', 'nj'),
     (35, 'NM NMSIIS','New Mexico', 'nm'),
     (36, 'NV WebIZ','Nevada', 'nv'),
     (37, 'NY NYSIIS','New York State', 'ny'),
     (38, 'NYC CIR','New York City', 'nyc'),
     (39, 'OH Impact SIIS','Ohio', 'oh'),
     (40, 'OK OSIIS','Oklahoma', 'ok'),
     (41, 'OR ALERT','Oregon', 'or'),
     (42, 'PA PHIL','Pennsylvania - Philadelphia', 'ph'),
     (43, 'PA SIIS','Pennsylvania', 'pa'),
     (44, 'PI - American Samoa','PI - American Samoa', 'as'),
     (45, 'PI - Federated States of Micronesia','PI - Federated States of Micronesia', 'fm'),
     (46, 'PI - Palau','PI - Palau', 'pw'),
     (47, 'PI - PI Guam','PI - Guam', 'gu'),
     (48, 'PI - PI N Mariana Islands','PI - Commonwealth of the Mariana Islands', 'mp'),
     (49, 'PI - Republic of the Marshall Islands','PI - Republic of the Marshall Islands', 'mh'),
     (50, 'PR PRIR','Puerto Rico', 'pr'),
     (51, 'RI CAIR','Rhode Island', 'ri'),
     (52, 'SC SCI','South Carolina', 'sc'),
     (53, 'SD SDIIS','South Dakota', 'sd'),
     (54, 'TN TennIIS','Tennessee', 'tn'),
     (55, 'TX ImmTrac','Texas', 'tx'),
     (56, 'U.S. Virgin Islands','U.S. Virgin Islands', 'vi'),
     (57, 'UT USIIS','Utah', 'ut'),
     (59, 'VA VIIS','Virginia', 'va'),
     (60, 'VT IMR','Vermont', 'vt'),
     (61, 'WA WAIIS','Washington', 'wa'),
     (62, 'WI WIR','Wisconsin', 'wi'),
     (63, 'WV WVSIIS','West Virginia', 'wv'),
     (64, 'WY WyIR','Wyoming', 'wy');
      

INSERT INTO `destination_type` VALUES 
    (1, 'PRODUCTION'),  -- APHL Production
    (2, 'TEST'),        -- Audacious Test Environment
    (3, 'ONBOARD'),     -- APHL Onboarding Environment
    (4, 'STAGE'),       -- APHL Staging Environment
    (5, 'DEV'),         -- Audacious Development Environment
    (6, 'UNKNOWN');     -- Unknown, used a default for migrations

INSERT INTO `destinations` (`dest_id`, `dest_type`, `dest_uri`, `username`, `password`, 
        `facility_id`, `MSH3`, `MSH4`, `MSH5`, `MSH6`, `MSH22`, `RXA11`, 
        `pass_expiry`, `jurisdiction_id`, `maint_reason`, `maint_start`) 
    VALUES ('maint', '5', 'https://dev.izgateway.org/dev/IISService', 'user', 'pass', 
        'IZGW', 'IZGW', 'IZGW', 'IZGW', 'IZGW', 'IZGW', 'IZGW', 
        '2024-08-12', '1', 'Destination is under maintenance', '2023-11-16 04:28:00');      

INSERT INTO `destinations` (dest_id, dest_uri, username, password, facility_id, MSH3, MSH4, MSH5, MSH6, MSH22, RXA11, dest_version, pass_expiry, dest_type, jurisdiction_id)
    VALUES
    ( '404', '/dev/NotFound', 'NOT_FOUND_ENDPOINT', 'NONE', 'IZGW', 'IZGW', 'IZGW', 'IZGW', 'IZGW', '', '', '', '2024-07-12', 5, 1);
INSERT INTO `destinations` (dest_id, dest_uri, username, password, facility_id, MSH3, MSH4, MSH5, MSH6, MSH22, RXA11, dest_version, pass_expiry, dest_type, jurisdiction_id)
    VALUES
    ( 'devwup', '/dev/IISService', '', '', 'IZGW', 'IZGW', 'IZGW', 'IZGW', 'IZGW', '', '', '', '2024-07-12', 5, 1),
    ( 'dev', '/dev/IISService', 'user', 'pass', 'IZGW', 'IZGW', 'IZGW', 'IZGW', 'IZGW', '', '', '', '2024-07-12', 5, 1),
    ( 'dev2011', '/dev/client_Service', 'user', 'pass', 'IZGW', 'IZGW', 'IZGW', 'IZGW', 'IZGW', '', '', '2011', '2024-07-12', 5, 1),
    ( 'down', 'https://192.0.2.0/dev/IISService', 'NON_RESPONDING_IP_ENDPOINT', 'NONE', 'IZGW', 'IZGW', 'IZGW', 'IZGW', 'IZGW', '', '', '', '2024-07-12', 5, 1),
    ( 'invalid', 'https://iis.invalid', 'NON_DNS_RESOLVABLE_ENDPOINT', 'NONE', 'IZGW', 'IZGW', 'IZGW', 'IZGW', 'IZGW', '', '', '', '2024-07-12', 5, 1),
    ( 'reject', 'https://localhost:12345/dev/IISService', 'REJECTING_ENDPOINT', 'NONE', 'IZGW', 'IZGW', 'IZGW', 'IZGW', 'IZGW', '', '', '', '2024-07-12', 5, 1),
    ( 'azurite', 'https://localhost:10000/devstoreaccount1/izgw', 'IZGW', 
        'sv=2018-03-28&st=2022-09-16T19%3A32%3A55Z&se=2023-09-07T19%3A32%3A00Z&sr=c&sp=racwdl&sig=SzCq1AFTf2kADcqb16gAb7b6lL0sm1QuHFXV8JEPCGE%3D', 
        'IZGW', 'IZGW', 'IZGW', 'IZGW', 'IZGW', '', '', 'V2022-12-31', '2024-07-12', 5, 1
    ),
    ( 'dex-dev', 'https://localhost/rest/upload/dex', 'dex-dev', 'dex-dev', 'IZGW', 'IZGW', 'IZGW', 'IZGW', 'IZGW', '', '', 'DEX1.0', '2024-07-12', 5, 1 );

INSERT INTO `messageheaderinfo` (msh, dest_id, iis, sourceType)
VALUES
    ( 'Docket-1_0_0', null, null, 'Patient Access' ),
    ( '99990', 'dev', 'ma', 'IIS Share' ),
    ( '161143928', 'dev', 'md', 'IIS Share' ),
    ( '161147173', 'dev', 'md', 'IIS Share' ),
    ( 'AL9997', 'dev', 'or', 'IIS Share' ),
    ( 'ALERT', 'dev', 'or', 'IIS Share' ),
    ( 'AS0000', 'dev', 'as', 'IIS Share' ),
    ( 'ASIIS', 'dev', 'az', 'IIS Share' ),
    ( 'CDPHE', 'dev', 'co', 'IIS Share' ),
    ( 'CHIRPPRD', 'dev', 'in', 'IIS Share' ),
    ( 'CIIS', 'dev', 'co', 'IIS Share' ),
    ( 'CT0000', 'dev', 'ct', 'IIS Share' ),
    ( 'CT0000_UI', 'dev', 'ct', 'IIS Share' ),
    ( 'DCIIS', 'dev', 'dc', 'IIS Share' ),
    ( 'DE0000', 'dev', 'de', 'IIS Share' ),
    ( 'DE0000_UI', 'dev', 'de', 'IIS Share' ),
    ( 'dn1fro00', 'dev', 'wa', 'IIS Share' ),
    ( 'FLSHOTS', 'dev', 'fl', 'IIS Share' ),
    ( 'FM0000', 'dev', 'fm', 'IIS Share' ),
    ( 'GRITS', 'dev', 'ga', 'IIS Share' ),
    ( 'GU0000', 'dev', 'gu', 'IIS Share' ),
    ( 'ICARE', 'dev', 'il', 'IIS Share' ),
    ( 'IMMPACT', 'dev', 'me', 'IIS Share' ),
    ( 'IMMTRAX', 'dev', 'mt', 'IIS Share' ),
    ( 'IMMUNET', 'dev', 'md', 'IIS Share' ),
    ( 'IMMUNETS', 'dev', 'md', 'IIS Share' ),
    ( 'ImpactSIIS', 'dev', 'oh', 'IIS Share' ),
    ( 'IRIS', 'dev', 'id', 'IIS Share' ),
    ( 'IRISIA', 'dev', 'ia', 'IIS Share' ),
    ( 'IRISID', 'dev', 'id', 'IIS Share' ),
    ( 'KS0000', 'dev', 'ks', 'IIS Share' ),
    ( 'KY0000_UI', 'dev', 'ky', 'IIS Share' ),
    ( 'LA0000', 'dev', 'la', 'IIS Share' ),
    ( 'LA0000_UI', 'dev', 'la', 'IIS Share' ),
    ( 'LALinks', 'dev', 'la', 'IIS Share' ),
    ( 'MCIR', 'dev', 'mi', 'IIS Share' ),
    ( 'MH0000', 'dev', 'mh', 'IIS Share' ),
    ( 'MICHIGAN', 'dev', 'mi', 'IIS Share' ),
    ( 'MIIC', 'dev', 'mn', 'IIS Share' ),
    ( 'MIIS', 'dev', 'ma', 'IIS Share' ),
    ( 'MIIX', 'dev', 'ms', 'IIS Share' ),
    ( 'MIIXHL7', 'dev', 'ms', 'IIS Share' ),
    ( 'MODHSS', 'dev', 'mo', 'IIS Share' ),
    ( 'MP0000', 'dev', 'mp', 'IIS Share' ),
    ( 'NCIR', 'dev', 'nc', 'IIS Share' ),
    ( 'NESIIS', 'dev', 'ne', 'IIS Share' ),
    ( 'NHIIS', 'dev', 'nh', 'IIS Share' ),
    ( 'NJDOH', 'dev', 'nj', 'IIS Share' ),
    ( 'NJIIS', 'dev', 'nj', 'IIS Share' ),
    ( 'NMSIIS', 'dev', 'nm', 'IIS Share' ),
    ( 'NMSIIS_UI', 'dev', 'nm', 'IIS Share' ),
    ( 'NV0000', 'dev', 'nv', 'IIS Share' ),
    ( 'NV0000_UI', 'dev', 'nv', 'IIS Share' ),
    ( 'NYCDOHMH', 'dev', 'nyc', 'IIS Share' ),
    ( 'NYSIIS', 'dev', 'ny', 'IIS Share' ),
    ( 'OHSIIS', 'dev', 'oh', 'IIS Share' ),
    ( 'OK0000', 'dev', 'ok', 'IIS Share' ),
    ( 'OK0000_UI', 'dev', 'ok', 'IIS Share' ),
    ( 'PH0000', 'dev', 'ph', 'IIS Share' ),
    ( 'PH0000_UI', 'dev', 'ph', 'IIS Share' ),
    ( 'PREIS', 'dev', 'pr', 'IIS Share' ),
    ( 'PRIIS', 'dev', 'pr', 'IIS Share' ),
    ( 'PU0000', 'dev', 'pu', 'IIS Share' ),
    ( 'RIA', 'dev', 'ri', 'IIS Share' ),
    ( 'SDIIS', 'dev', 'sd', 'IIS Share' ),
    ( 'SHOWMEVAX', 'dev', 'mo', 'IIS Share' ),
    ( 'SIMON', 'dev', 'sc', 'IIS Share' ),
    ( 'TENNIIS', 'dev', 'tn', 'IIS Share' ),
    ( 'TNIIS', 'dev', 'tn', 'IIS Share' ),
    ( 'TxDSHS', 'dev', 'tx', 'IIS Share' ),
    ( 'TxImmTrac', 'dev', 'tx', 'IIS Share' ),
    ( 'USVIIIS', 'dev', 'vi', 'IIS Share' ),
    ( 'VIIS', 'dev', 'va', 'IIS Share' ),
    ( 'WADOHIIS', 'dev', 'wa', 'IIS Share' ),
    ( 'WAIIS', 'dev', 'wa', 'IIS Share' ),
    ( 'WIA', 'dev', 'wi', 'IIS Share' ),
    ( 'WIR', 'dev', 'wi', 'IIS Share' ),
    ( 'WVIIS', 'dev', 'wv', 'IIS Share' ),
    ( 'WVSIIS', 'dev', 'wv', 'IIS Share' ),
    ( 'WYIR', 'dev', 'wy', 'IIS Share' );
COMMIT WORK;
