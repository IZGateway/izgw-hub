# IZ Gateway Release 2.1.9
The IZ Gateway Hub 2.1.9 Release
* IGDD-1709 Hub: Smoke Tests Break when running against prod and dev in phiz-project.org
* IGDD-1874 IZGW Hub not supporting status inquiry on NDLP Endpoints
* IGDD-1888 Switch IZGW Hub and IZGW Core to GitFlow configuration in CI/CD
* IGDD-1896 Weekly Performance Test is Not Working in test/mock environment
* IGDD-1899 NDLP submissions larger than 128Mb are not submitting metadata

# IZ Gateway Release 2.1.8
The IZ Gateway Hub 2.1.8 release
* Adjusts metadata handling for farmer-flu reporting to correct for specification changes published in December.
* Upgrade logback to version 1.5.16
* Upgrade spring-security to 6.3.5
* Changes logging level to debug for scheduled endpoint testing
* Improves reliability on submissions to Azure endpoints

# IZ Gateway Release 2.1.7
The IZ Gateway Hub 2.1.7 release
* Supports access to NDLP Storage for ADS Uploads

# IZ Gateway Release 2.1.6
The IZ Gateway Hub 2.1.6 release
* Improves logging on ADS input errors or outbound file upload errors
* Adjust filename validation logic for ADS file uploads to relax date validation
* Refresh now resets all circuit breakers
* Removes PHI from fault/reason if present before logging.

# IZ Gateway Release 2.1.5
The IZ Gateway Hub 2.1.5 patch release:

* Fixes a null pointer error in IIS to CDC (DEX) APIs with file sizes of 0
* Corrects a bug in meta_schema_version and version fields to ensure they have the same values

# IZ Gateway Release 2.1.4
The IZ Gateway Hub 2.1.4 patch release:

* Improves system load capacity
* Fixes a bug in ADS DEX Metadata reporting for the DEX 2.0 Schema
* Reports izgw_submission_status and izgw_submission_location to verify receipt of reports
* Upgrades spring-web components to address a CVE
* Upgrades Bouncy Castle FIPS to Version 2.0 (NIST Certificate Number: 4743)

# IZ Gateway Release 2.1.3
The IZ Gateway Hub 2.1.3 patch release enables support for the DEX 2.0 schema and exchange of farmerFlu and other report submission formats determined by CDC in the future.  It also enables access to information from the /upload/info endpoint supported via DEX APIs enabling jurisdictions to determine the status of submissions sent to CDC.

# IZ Gateway Release 2.1.2
The IZ Gateway Hub 2.1.2 patch release enables catch and kill for messages not matching the onboarding security policy, and 
adjusts logging levels for that environment.

# IZ Gateway Release 2.1.1
The IZ Gateway Hub 2.1.1 patch release adds access control capabilities for destinations.

# IZ Gateway Release 2.1.0
The IZ Gateway Hub 2.1.0 is in preparation for code sharing across IZ Gateway Hub and the IZ Gateway Transformation Service.

* Split IZ Gateway into separate repositories for core capabilities [izgw-core](https://github.com/izgateway/izgw-core) and the
IZ Gateway Hub [izgw-hub](https://github.com/izgateway/izgw-hub)
* Update access control table to support multiple environments and certificate blacklisting
* Fix two NPEs in Message Sender
* Added Security Checks on Message Sending to Verify Endpoint is for correct Environment
* Fixed issue with filebeat and metricbeat race condition on startup
* Address data validation for ADS uploads from distant timezones

# IZ Gateway Release 2.0.7
Expected Date: Onboarding 7/17/2024, Production 7/17/2024
IZ Gateway Hub 2.0.7 is a patch release to address the following issue:
* Correct endpoint in WSDL

# IZ Gateway Release 2.0.6
Expected Date: Onboarding 6/27/2024, Production 6/27/2024
IZ Gateway Hub 2.0.6 is a patch release to address the following issues:
* Trim messages of trailing whitespace to fix \r\n problem
* Do not throw circuit breaker on exempt endpoints

# IZ Gateway Release 2.0.5
Expected Date: Onboarding 6/18/2024, Production 6/18/2024
IZ Gateway Hub 2.0.5 is an urgent patch release to address the following issues:

* Remove incorrect elements from IZGW Hub fault messages
* Add DEX 2.0 schema metadata


# IZ Gateway Release 2.0.4
Expected Date: Onboarding 5/28/2024, Production 6/11/2024
IZ Gateway Hub 2.0.4 is a patch release to address the following issues:

* Add namespace declarations for Fault elements in response messages.


# IZ Gateway Release 2.0.3
Expected Date: Onboarding 5/23/2024, Production 5/30/2024
IZ Gateway Hub 2.0.3 is a patch release to address the following issues:

* Fix to destinationUri element name

# IZ Gateway Release 2.0.2
Expected Date: Onboarding 5/21/2024, Production 5/28/2024
IZ Gateway Hub 2.0.2 is a patch release to address the following issues:

* Update content type to application/soap+xml in SOAP Messages
* Support use of SNI in TusClient code

# IZ Gateway Release 2.0.1
Expected Date: Onboarding 5/7/2024, Production 5/21
IZ Gateway Hub 2.0.1 is a patch release to address the following issues:

* Missing credentials in messages sent to jurisdictions
* Use of SNI in sending data to DEX OAuth endpoint

# IZ Gateway Release 2.0.0
Expected Date: 4/19/2024

IZ Gateway 2.0.0 is a major release of IZ Gateway. The 2.0 release is functionally equivalent to IZ Gateway 1.4.7.1 as far and prior releases without exception for end users.  There are a few exceptions impacting operations listed later below.

- IZ Gateway 2.0 support Provided Connect, IIS to IIS Share, Patient Access and IIS to CDC use cases without any changes to the API calls 
  or communication protocols in use.

- IZ Gateway 2.0 uses the same database structures and connectivity options as IZ Gateway 1.4.7.1 and prior.  See [CONFIGURATION.md](CONFIIGURATION.md) for details on
  configuring IZ Gateway.
  
- IZ Gateway ships its logs via an embedded log shipping agent. The configuration of this log shipping agent has not changed.

- IZ Gateway now runs in Java 17 using the Alpine deployed Java Runtime Environment instead of Java 11.

- The FIPS certified Bouncy Castle encryption module has been upgraded to the latest NIST Certified release for use with Java 11 and later.

- IZ Gateway 2.0 does NOT support database migration, backup or restore. We anticipate major changes to this functionality when 
  IZ Gateway is upgraded to support hot/hot multi-region fail-over and so did not migrate those capabilities to 2.0.

- IZ Gateway 2.0 provides automatically generated online documentation for all of its APIs to those with administrative access 
  to the server.

# IZ Gateway Release 1.4.7.1
Expected Date: 3/5/2024

Patched 1.4.7 to address an issue for submissions of Covid Bridge RVR files, and dates for RI files.

# IZ Gateway Release 1.4.7
Expected Date: 2/15 Onboarding, 2/22 Production
This release includes bug fixes, refinement of performance testing, updates to resource monitoring, updates to the manual ADS submission page, and security updates.

## Bug Fixes
[IGDD-1176](https://support.izgateway.org/browse/IGDD-1176) - Non-Prod: 500 Error response does not contain Retry details

[IGDD-1208](https://support.izgateway.org/browse/IGDD-1208) - Build bug

## ADS Submission Page Updates
[IGDD-1254](https://support.izgateway.org/browse/IGDD-1254) - When month cannot be parsed from the filename, the submit form reports an error.

[IGDD-1253](https://support.izgateway.org/browse/IGDD-1253) - Validate IZ Gateway Metadata for ADS Submissions

[IGDD-1262](https://support.izgateway.org/browse/IGDD-1262) - Update submitFile page with new logo and color scheme

## Performance Test Refinement
[IGDD-656](https://support.izgateway.org/browse/IGDD-656) - Reliability Testing: build test for &quot;a large IIS having repeated failures&quot; scenario

[IGDD-667](https://support.izgateway.org/browse/IGDD-667) - Reliability Testing: run test for &quot;a large IIS having repeated failures&quot; scenario

[IGDD-668](https://support.izgateway.org/browse/IGDD-668) - Reliability Testing: run test for &quot;regional outage&quot; scenario

## Resource Monitoring
[IGDD-1242](https://support.izgateway.org/browse/IGDD-1242) - Log host memory and CPU resource consumption to monitor

# IZ Gateway Release 1.4.6.1
Expected Date: 12/12 Onboarding and Production

This release includes fixes to Event reporting for refresh and status checks, and a correction to the web Form.

## Bug Fixes
[IGDD-1208](https://support.izgateway.org/browse/IGDD-1208)  Intermittent build failure bug fix

# IZ Gateway Release 1.4.6
Expected Date: 11/28 Onboarding, 12/4 Production

This release will include:

* Security features to enable source attack protection and disable/enable endpoints or sources for maintenance and security.
* Logging Improvements
* An API to support testing of a configured username/password combination
* A web form to support automated upload to IZ Gateway from a web browser for DEX submissions.
* Process Improvements

## Security Features
[IGDD-1109](https://support.izgateway.org/browse/IGDD-1109)  Take action post Source Attack Detection
[IGDD-1185](https://support.izgateway.org/browse/IGDD-1185)  API to enable / disable a message source via blacklist
[IGDD-1118](https://support.izgateway.org/browse/IGDD-1118)  Have the ability to disable / re-enable a destination for maintenance
## Logging Features
[IGDD-1187](https://support.izgateway.org/browse/IGDD-1187)  Update Configuration to report all Header and Facility ID Values
## Testing Features
[IGDD-1188](https://support.izgateway.org/browse/IGDD-1188)  Password Validation: Test a username and password for an endpoint 
## DEX Submission Form
To support jurisdictions wanting to upload to IZ Gateway from a browser 

[IGDD-1191](https://support.izgateway.org/browse/IGDD-119)   publish a web form which would enable a user with the correct certificate to publish to IZ Gateway using the DEX API and report on the results.
## Process Improvements
[IGDD-1115](https://support.izgateway.org/browse/IGDD-1115)  Improve CI/CD times to download POMs and deal with Version Numbering
[IGDD-1095](https://support.izgateway.org/browse/IGDD-1095)  Remove JAR Artifact from build

# IZ Gateway Release 1.4.5
Expected Date: 11/2/2023

This maintenance release includes the following fixes:

It also introduces a new administrative endpoint /rest/refresh which will attempt to refresh all database content across all servers to support password changes.

* [IGDD-1163](https://support.izgateway.org/browse/IGDD-1163)   Status history endpoint response should have statuses based on the count passed in the query parameter
* [IGDD-1100](https://support.izgateway.org/browse/IGDD-1100)   Heartbeat messages are not arriving every minute
* [IGDD-1098](https://support.izgateway.org/browse/IGDD-1098)   CI/CD tests do not fail b/c circuit breaker is thrown, unless intended.
* [IGDD-1096](https://support.izgateway.org/browse/IGDD-1096)   Add retry logic to OAuth requests to get or refresh an access token.
* [IGDD-1092](https://support.izgateway.org/browse/IGDD-1092)   Jurisdiction description, id and name are returned with IZG status history endpoint
* [IGDD-1090](https://support.izgateway.org/browse/IGDD-1090)   Intermittent failures in testing due to background connectivity testing
* [IGDD-1088](https://support.izgateway.org/browse/IGDD-1088)   Postman tests running against onboarding are failing for TC-15
* [IGDD-1084](https://support.izgateway.org/browse/IGDD-1084)   Update Fields on IZG Status History endpoint
* [IGDD-707](https://support.izgateway.org/browse/IGDD-707)     Create ISS/Provider sourced attack detection

# IZ Gateway Release 1.4.4.4
Expected Date: 9/29/2023

IZGW 1.4.4.3 Had a bug that leaked PHI into logs.

IZGW 1.4.4.4 Resolves that issue.

# IZ Gateway Release 1.4.4.3
Expected Date: 9/28/2023

This maintenance release includes certificates for preprod (new), onboarding (renewed) and production (current) environments in case EFS is still not able to be configured, and changes to the statushistory interface to support configuration console.

It also contains fixes for 10Gb DEX limit for CAIR testing, additional updates to statushistory API, and addresses intermittent testing failures in CI/CD and smoke testing scripts found during deployment of 1.4.4.2.

# IZ Gateway Release 1.4.4.2
Expected Date: 9/21/2023

# IZ Gateway Release 1.4.4.1
Expected Date: 9/18/2023

This maintenance release includes certificates for onboarding and production environments in case EFS is still
not able to be configured.

# IZ Gateway Release 1.4.4
Expected Date: 9/7/2023
This maintenance release includes the following features rolled from Release 1.4.3 (PLUS ADD NEW FEATURES):

Status Reporting Improvements
Automatic Database Upgrade
Improved Reliability and Responsiveness
ADS Improvements
Bug Fixes

## Status Reporting Improvements
Routinely track status of onboarded IIS from within IZ Gateway, and provide APIs to get status information.

* [IGDD-1073](https://support.izgateway.org/browse/IGDD-1073) - Enable status checks in Development environments and document use of them for APHL
* [IGDD-1058](https://support.izgateway.org/browse/IGDD-1058) - statushistory endpoint issue with the data retrieved
* [IGDD-1057](https://support.izgateway.org/browse/IGDD-1057) - Add Status Check API endpoint for DEX
* [IGDD-1053](https://support.izgateway.org/browse/IGDD-1053) - Report certificate and Build Version in use in Health Check
* [IGDD-1054](https://support.izgateway.org/browse/IGDD-1054) - Improve IZ Gateway Status Check Speed
* [IGDD-1009](https://support.izgateway.org/browse/IGDD-1009) - Connection status for each destination will be persisted

## Automatic Database Upgrade
Automate database upgrade process with versioned database migration for each release

* [IGDD-901](https://support.izgateway.org/browse/IGDD-901) - Automate upgrade of database tables for 1.4
* [IGDD-1012](https://support.izgateway.org/browse/IGDD-1012) - Perform database backup before DB migration using Flyway
* [IGDD-1016](https://support.izgateway.org/browse/IGDD-1016) - Schema will be updated so a Jurisdiction has many destinations

## Improved Reliability and Responsiveness
Improve responsiveness by implementing circuit breaker design pattern for unreachable destinations, and automate circuit breaker reset when destination becomes available again.  Improve reliability in the face of a DNS outage by caching previously obtained IP addresses.

* [IGDD-659](https://support.izgateway.org/browse/IGDD-659) - Add circuit breaker logic for messaging to outbound destinations
* [IGDD-1029](https://support.izgateway.org/browse/IGDD-1029) - Part 2: Add circuit breaker logic for messaging to outbound destinations
* [IGDD-949](https://support.izgateway.org/browse/IGDD-949) - Implement DNS Cache in IZG Alpine image

## ADS Improvements
Add DEX Status API

* [IGDD-1047](https://support.izgateway.org/browse/IGDD-1047) - Enable filetypes to be configurable in ADS

## Bug Fixes
Address bugs and minor improvements found in prior releases.

* [IGDD-1055](https://support.izgateway.org/browse/IGDD-1055) - Heartbeat Messages are Timestamped by the Server
* [IGDD-1010](https://support.izgateway.org/browse/IGDD-1010) - Remove ^^ from requestMSH/responseMSH fields during logging
* [IGDD-1008](https://support.izgateway.org/browse/IGDD-1008) - Patch Release with Production Certificate
* [IGDD-995](https://support.izgateway.org/browse/IGDD-995) - IZ Gateway Log Messages dependent upon host header
* [IGDD-982](https://support.izgateway.org/browse/IGDD-982) - Failed transaction message reports destination as null if no connection happens
* [IGDD-981](https://support.izgateway.org/browse/IGDD-981) - Enable CORS from https://izgateway.github.io/ads-file-submission-app in IZGW
* [IGDD-881](https://support.izgateway.org/browse/IGDD-881) - Updates for some database tables to get consistent reporting data

# IZ Gateway Release 1.4.3
Expected Date: 8/23/2023

Release 1.4.3 was NOT deployed due to several failures deploying to onboarding.

This maintenance release includes the following features

## Improved Reliability and Responsiveness
Improve reliability of IZ Gateway by enabling IZ Gateway to retry failed communications, and enable circuit breaker logic to reduce time spent on failed communications.
* [IGDD-901](https://support.izgateway.org/browse/IGDD-901) - Automate upgrade of database tables for 1.4
* [IGDD-659](https://support.izgateway.org/browse/IGDD-659) - Add circuit breaker logic for messaging to outbound destinations
* [IGDD-850](https://support.izgateway.org/browse/IGDD-850) - Enhance retry strategy messaging for http: error response

## Development Process Improvements
* [IGDD-956](https://support.izgateway.org/browse/IGDD-956) - Separate production of IZGW Key and Trust Store Configuration Files from IZGW Build
* [IGDD-816](https://support.izgateway.org/browse/IGDD-816) - Review pom.xml files, remove redundant dependencies and make it readable.

## Bug Fixes
* [IGDD-881](https://support.izgateway.org/browse/IGDD-881) - Updates for some database tables to get consistent reporting data

# IZ Gateway Release 1.4.2.2
This patch release addresses issues for destination endpoints using the TLS 1.2 Server Name Identifier
extension to support serving requests from the same server with multiple certificates, enabling IZ Gateway to connect to servers using this feature.

* [IGDD-1013](https://support.izgateway.org/browse/IGDD-1013) SNI Support needed for Production

# IZ Gateway Release 1.4.2.1
This patch release updated the certificate for production.

* [IGDD-1008](https://support.izgateway.org/browse/IGDD-1008) Patch Release with Production Certificate

# IZ Gateway Release 1.4.2

This maintenance release adds features enabling testing and development of Configuration Console, offers security and reliability enhancements, and includes identified bug fixes

* [IGDD-986](https://support.izgateway.org/browse/IGDD-986) - Security Enhancements
  Automate weekly dependency checks for all deployed and under development releases, use BCFIPS instead of team   maintained trust and key manager code, and enable attack detection/disablement of attacked sources.
  - [IGDD-688](https://support.izgateway.org/browse/IGDD-688) - Weekly Dependency Check
  - [IGDD-928](https://support.izgateway.org/browse/IGDD-928) - Use (and possibly extend) BC-FIPS rather than maintaining complex PhizTrustManager code
  - [IGDD-984](https://support.izgateway.org/browse/IGDD-984) - Refactor Key Manager configuration
  - [IGDD-985](https://support.izgateway.org/browse/IGDD-985) - Refactor Trust Manager Configuration
  - [IGDD-929](https://support.izgateway.org/browse/IGDD-929) - Enable reload of trust and key store files if they have changed

* [IGDD-990](https://support.izgateway.org/browse/IGDD-990) Reliability Testing Improvements
  Develop additional performance tests for reliablity
  - [IGDD-651](https://support.izgateway.org/browse/IGDD-651) - Spike: Reliability Testing research and planning
  - [IGDD-656](https://support.izgateway.org/browse/IGDD-656) - Reliability Testing: build test for "a large IIS having repeated failures" scenario
  - [IGDD-668](https://support.izgateway.org/browse/IGDD-668) - Reliability Testing: run test for "regional outage" scenario
  - [IGDD-666](https://support.izgateway.org/browse/IGDD-666) - Reliability Testing: built test for "regional outage" scenario
  - [IGDD-667](https://support.izgateway.org/browse/IGDD-667) - Reliability Testing: run test for "a large IIS having repeated failures" scenario

* [IGDD-991](https://support.izgateway.org/browse/IGDD-991) Bug Fixes
  - [IGDD-734](https://support.izgateway.org/browse/IGDD-734) - IZ Gateway will respond to faults that have HTTP Error 400
  - [IGDD-934](https://support.izgateway.org/browse/IGDD-934) - ADS- Handle Exceptions for Azurite Endpoint Failure 
  - [IGDD-982](https://support.izgateway.org/browse/IGDD-982) - Failed transaction message reports destination as null if no connection happens 

* [IGDD-992](https://support.izgateway.org/browse/IGDD-992) Development Process Improvements
  Improving process for development team.
  - [IGDD-573](https://support.izgateway.org/browse/IGDD-573) - Add test/dev/config.izgateway.org endpoints for Audacious hosted environments 
  - [IGDD-981](https://support.izgateway.org/browse/IGDD-981) - Enable CORS from https://izgateway.github.io/ads-file-submission-app in IZGW 
  - [IGDD-987](https://support.izgateway.org/browse/IGDD-987) - Adjust integration test suite to skip log and SLA testing for APHL environments 


# IZ Gateway Release 1.4.1
These are release notes for IZ Gateway 1.4.1.

IZ Gateway Release 1.4.1 is the next release of the IZ Gateway Application software provided by Audacious Inquiry. This Release adds X.

## Release Features
This release candidate includes the following Features:

-  Daily OCSP Checks 
   - [IGDD-510](https://support.izgateway.org/browse/IGDD-510) Create Shared Resource
   - [IGDD-809](https://support.izgateway.org/browse/IGDD-809) Perform OCSP Checks
   - [IGDD-811](https://support.izgateway.org/browse/IGDD-811) Integrate Trust Manager (Business Logic)

- Automated Data Submission Code and Logging Changes & Bug Fixes
  - [IGDD-797](https://support.izgateway.org/browse/IGDD-797) Access logs for Azurite Test Endpoint in ElasticSearch
  - [IGDD-798](https://support.izgateway.org/browse/IGDD-798) Sweep Azurite test endpoint periodically to ensure that the container does not run out of storage  
  - [IGDD-883](https://support.izgateway.org/browse/IGDD-883) Log failed TLS Connection Attempts
  - [IGDD-915](https://support.izgateway.org/browse/IGDD-915) Connectivity Test does not update transactionData.destination fields
  - [IGDD-934](https://support.izgateway.org/browse/IGDD-934) ADS- Handle Exceptions for Azurite Endpoint Failure
  - [IGDD-957](https://support.izgateway.org/browse/IGDD-957) Send filename in Metadata map
  - [IGDD-958](https://support.izgateway.org/browse/IGDD-958) After a successful call to RestfulFileSender.getStatus(), transactionData.destination.connected should be set to true
  - [IGDD-959](https://support.izgateway.org/browse/IGDD-959) Update logging to log START of ADS transactions
  - [IGDD-960](https://support.izgateway.org/browse/IGDD-960) Remove authorized certificate detail from logging 
  - [IGDD-961](https://support.izgateway.org/browse/IGDD-961) For a Connection Timeout in DEX, IZ Gateway is reporting incorrectly an active reject.
  - [IGDD-962](https://support.izgateway.org/browse/IGDD-962) We are calling oauth endpoint to retrieve a token 3 times for every DEX request

- Automate Performance Testing to support Automated Data Submission service
  - [IGDD-641](https://support.izgateway.org/browse/IGDD-641) Set up framework to support Automated Performance Testing
  - [IGDD-841](https://support.izgateway.org/browse/IGDD-841) The ADS Service will meet or exceed the Reliability non-functional requirement 
  - [IGDD-842](https://support.izgateway.org/browse/IGDD-842) The ADS Service will meet or exceed the Scalability non-functional requirement 
  - [IGDD-844](https://support.izgateway.org/browse/IGDD-844) The ADS Service will meet or exceed the Security non-functional requirement 
  - [IGDD-846](https://support.izgateway.org/browse/IGDD-846) The ADS Service will meet or exceed the Throughput non-functional requirement 
  - [IGDD-847](https://support.izgateway.org/browse/IGDD-847) The ADS Service will meet or exceed the Timeliness non-functional requirement 
  - [IGDD-918](https://support.izgateway.org/browse/IGDD-918) Fail Performance test if non-functional requirements are not met

- Operational Enhancements
  - [IGDD-888](https://support.izgateway.org/browse/IGDD-888) Automate creation of release
  - [IGDD-901](https://support.izgateway.org/browse/IGDD-901) Automate upgrade of database tables for 1.4
  - [IGDD-923](https://support.izgateway.org/browse/IGDD-923) Set up IZ Gateway demo environment

# IZ Gateway Release 1.4.0 RC4
This is Release Candidate 4 for IZ Gateway Release 1.4. 

IZ Gateway Release 1.4 is the next release of the IZ Gateway Application software provided by Audacious Inquiry. This Release adds a RESTful file submission API that allows authorized users to access the Automated Data Submission service in the IZ Gateway code base so that IZ DataLake and DEX endpoints can be reached to support the Jurisdiction-Initiated CDC Data Reporting use case.

## New in this Release Candidate
This is a patch to RC3 to correct the keystore for the Production environment.

## Existing Capabilities from RC1, RC2 and RC3
This release candidate includes the following changes: 
- [IGDD-920](https://support.izgateway.org/browse/IGDD-920) Envision cannot submit to ADS due to Exception thrown in X500Utils.getParts()

## Release Features
This release candidate includes the following Features: 
- [IGDD-867](https://support.izgateway.org/browse/IGDD-867) RESTful file submission API
- [IGDD-899](https://support.izgateway.org/browse/IGDD-899) Update metadata fields to support current IZDL specs
- [IGDD-909](https://support.izgateway.org/browse/IGDD-909) Add capability to support DEX Endpoints
- [IGDD-909](https://support.izgateway.org/browse/IGDD-909) Add capability to support DEX Endpoints
- [IGDD-916](https://support.izgateway.org/browse/IGDD-916) Enable database configuration of access controls and routing restrictions

## Release Details
IZ Gateway Release 1.4 adds the following functionality to the IZ Gateway code base: 

- Adds Submission Endpoint: POST /rest/ads/{destinationId}
  - IZ Gateway 1.4 introduces a new RESTful API, which is documented [here](https://izgateway.github.io/ads-file-submission-app/) 
  - Access to this API is restricted to users with authorized client certificates.
  
- Access to other APIs:
  - ADS Endpoint status: GET /rest/ads/status/{id}
    - Uses Azure HTTP API to list at most one element from the contents of storage container.
  - ADS/SOAP Status: GET /rest/status and /rest/status/{id}
    - Calls IZ Gateway connectivityTest for SOAP endpoints, or internally uses /rest/ADS/status to report status and connectivity.
  - Configuration: GET /rest/config and /rest/config/{id}
    - Returns configuration endpoint URL, destination id, and version for endpoints.
  - GET /rest/ads/{destinationId}
    - Retrieval of content submitted to azurite endpoint (Used in onboarding for verification activities).
  - DELETE /rest/ads/{destinationId}
    - Deletion of content submitted to azurite endpoint (Used in onboarding for storage management).
  - These endpoints are accessible only to those certificates which are whitelisted for those activities in the system configuration.

- Access to IZ DataLake (Azure) and DEX Endpoints routed through the Automated Data Submission service
  - IZ DataLake Endpoints (Azure)
    - ndlp-izgw-ri: endpoint for submission of Routine Immunization reports to the IZ DataLake test environment. This Azure blob storage container is managed by the CDC NDLP team and secured using SAS tokens.
      - Routine Immunization report files must be zipped
    -  ndlp-izgw-flu: endpoint for submission of Flu Immunization reports to the IZ DataLake test environment. This Azure blob storage container is managed by the CDC NDLP team and secured using SAS tokens.
       - Flu Immunization report files should stay in XLS or XLSX format 
    - azurite: a test endpoint emulating the IZ DataLake transport mechanism for both Routine and Flu Immunization reports.
      - This endpoint runs internally in IZ Gateway nodes at port 10000 within the docker image, but that port is not exposed to users.
      - Data in this endpoint is deleted when storage is not available to send a new file.
    - The endpoint URL and SAS token are stored in the dest_uri and password fields of the IZ Gateway database, just like uris for other destinations.
    - The mime type of the data is determined by the file extension.  At present, only ‘zip’, ‘xsl’, and ‘xslx’ files may be transmitted.  
    - User supplied parameters in the API control the destination location in blob storage.  The period, facilityId (sender identifier), and date of submission specify the folders, and the user supplied file or filename override parameter specify the filename:  {period}/{facilityId}/{YYYYMMDD}/{filename}
  - DEX Endpoints
    - dex-stg: an endpoint for submission of Routine Immunizations for the CDC Data Exchange Front door (DEX)
    - dex-dev: an endpoint emulating the protocol for DEX for submission of Routine Immunizations and Influenza reporting

  - Environment variables or configuration files control which endpoints can be used to transmit Routine and Flue Immunization report files and restrict users from transmitting to inappropriate endpoints.
  - All API parameters, the file size, send times, and generated metadata are returned to the user, and are also logged.

# IZ Gateway Release 1.4.0 RC3

This is Release Candidate 3 for IZ Gateway Release 1.4. 

IZ Gateway Release 1.4 is the next release of the IZ Gateway Application software provided by Audacious Inquiry. This Release adds a RESTful file submission API that allows authorized users to access the Automated Data Submission service in the IZ Gateway code base so that IZ DataLake and DEX endpoints can be reached to support the Jurisdiction-Initiated CDC Data Reporting use case.


## New in this Release Candidate
This release candidate includes the following changes: 
- [IGDD-920](https://support.izgateway.org/browse/IGDD-920) Envision cannot submit to ADS due to Exception thrown in X500Utils.getParts()

# IZ Gateway Release 1.4.0 RC2

This is Release Candidate 2 for IZ Gateway Release 1.4. 

IZ Gateway Release 1.4 is the next release of the IZ Gateway Application software provided by Audacious Inquiry. This Release adds a RESTful file submission API that allows authorized users to access the Automated Data Submission service in the IZ Gateway code base so that IZ DataLake and DEX endpoints can be reached to support the Jurisdiction-Initiated CDC Data Reporting use case.

This is expected to be the final release candidate and will being deployed to the Onboarding Environment for at least one week of testing before pushing to Production.

## New in this Release Candidate
This release candidate includes the following changes: 
- [IGDD-909](https://support.izgateway.org/browse/IGDD-909) Add capability to support DEX Endpoints
- [IGDD-916](https://support.izgateway.org/browse/IGDD-916) Enable database configuration of access controls and routing restrictions

## Existing Capabilities from RC1

## Release Features
This release candidate includes the following Features: 
- [IGDD-867](https://support.izgateway.org/browse/IGDD-867) RESTful file submission API
- [IGDD-899](https://support.izgateway.org/browse/IGDD-899) Update metadata fields to support current IZDL specs
- [IGDD-909](https://support.izgateway.org/browse/IGDD-909) Add capability to support DEX Endpoints

## Release Details
IZ Gateway Release 1.4 adds the following functionality to the IZ Gateway code base: 

- Adds Submission Endpoint: POST /rest/ads/{destinationId}
  - IZ Gateway 1.4 introduces a new RESTful API, which is documented [here](https://izgateway.github.io/ads-file-submission-app/) 
  - Access to this API is restricted to users with authorized client certificates.
  
- Access to other APIs:
  - ADS Endpoint status: GET /rest/ads/status/{id}
    - Uses Azure HTTP API to list at most one element from the contents of storage container.
  - ADS/SOAP Status: GET /rest/status and /rest/status/{id}
    - Calls IZ Gateway connectivityTest for SOAP endpoints, or internally uses /rest/ADS/status to report status and connectivity.
  - Configuration: GET /rest/config and /rest/config/{id}
    - Returns configuration endpoint URL, destination id, and version for endpoints.
  - GET /rest/ads/{destinationId}
    - Retrieval of content submitted to azurite endpoint (Used in onboarding for verification activities).
  - DELETE /rest/ads/{destinationId}
    - Deletion of content submitted to azurite endpoint (Used in onboarding for storage management).
  - These endpoints are accessible only to those certificates which are whitelisted for those activities in the system configuration.

- Access to IZ DataLake (Azure) and DEX Endpoints routed through the Automated Data Submission service
  - IZ DataLake Endpoints (Azure)
    - ndlp-izgw-ri: endpoint for submission of Routine Immunization reports to the IZ DataLake test environment. This Azure blob storage container is managed by the CDC NDLP team and secured using SAS tokens.
      - Routine Immunization report files must be zipped
    -  ndlp-izgw-flu: endpoint for submission of Flu Immunization reports to the IZ DataLake test environment. This Azure blob storage container is managed by the CDC NDLP team and secured using SAS tokens.
       - Flu Immunization report files should stay in XLS or XLSX format 
    - azurite: a test endpoint emulating the IZ DataLake transport mechanism for both Routine and Flu Immunization reports.
      - This endpoint runs internally in IZ Gateway nodes at port 10000 within the docker image, but that port is not exposed to users.
      - Data in this endpoint is deleted when storage is not available to send a new file.
    - The endpoint URL and SAS token are stored in the dest_uri and password fields of the IZ Gateway database, just like uris for other destinations.
    - The mime type of the data is determined by the file extension.  At present, only ‘zip’, ‘xsl’, and ‘xslx’ files may be transmitted.  
    - User supplied parameters in the API control the destination location in blob storage.  The period, facilityId (sender identifier), and date of submission specify the folders, and the user supplied file or filename override parameter specify the filename:  {period}/{facilityId}/{YYYYMMDD}/{filename}
  - DEX Endpoints
    - dex-stg: an endpoint for submission of Routine Immunizations for the CDC Data Exchange Front door (DEX)
    - dex-dev: an endpoint emulating the protocol for DEX for submission of Routine Immunizations and Influenza reporting

  - Environment variables or configuration files control which endpoints can be used to transmit Routine and Flue Immunization report files and restrict users from transmitting to inappropriate endpoints.
  - All API parameters, the file size, send times, and generated metadata are returned to the user, and are also logged.


# IZ Gateway Release 1.4.0
IZ Gateway Release 1.4 is the next release of the IZ Gateway Application software provided by Audacious Inquiry. This Release adds a RESTful file submission API that allows authorized users to access the Automated Data Submission service in the IZ Gateway code base so that IZ DataLake and DEX endpoints can be reached to support the Jurisdiction-Initiated CDC Data Reporting use case.

This is expected to be the final release candidate and will being deployed to the Onboarding Environment for at least one week of testing before pushing to Production.

## Release Features
This release candidate includes the following Features: 
- [IGDD-867](https://support.izgateway.org/browse/IGDD-867) RESTful file submission API
- [IGDD-899](https://support.izgateway.org/browse/IGDD-899) Update metadata fields to support current IZDL specs
- [IGDD-909](https://support.izgateway.org/browse/IGDD-909) Add capability to support DEX Endpoints

## Release Details
The release candidate is described in more detail here:
- Submission Endpoint: POST /rest/ads/{destinationId}
  - IZ Gateway 1.4 introduces a new RESTful API, which is documented [here](https://izgateway.github.io/ads-file-submission-app/) 
- Access to this API IZ Gateway is restricted to users with authorized client certificates.
- Access to other APIs:
  - ADS Endpoint status: GET /rest/ads/status/{id}
    - Uses Azure HTTP API to list at most one element from the contents of storage container.
  - ADS/SOAP Status: GET /rest/status and /rest/status/{id}
    - Calls IZ Gateway connectivityTest for SOAP endpoints, or internally uses /rest/ADS/status to report status and connectivity.
  - Configuration: GET /rest/config and /rest/config/{id}
    - Returns configuration endpoint URL, destination id, and version for endpoints.
  - GET /rest/ads/{destinationId}
    - Retrieval of content submitted to azurite endpoint (Used in onboarding for verification activities).
  - DELETE /rest/ads/{destinationId}
    - Deletion of content submitted to azurite endpoint (Used in onboarding for storage management).
  - These endpoints are accessible only to those certificates which are whitelisted for those activities in the system configuration.

# IZ DataLake Endpoint (Azure)
- A new endpoint is available for users to test their submissions that is implemented using the Microsoft ‘azurite’ application, emulating azure secure storage.  This endpoint runs internally in IZ Gateway nodes at port 10000 within the docker image, but that port is not exposed to users.  Data in this endpoint is deleted when storage is not available to send a new file.
- Other endpoints ndlp-izgw-ri, and ndlp-izgw-flu are actual Azure blob storage containers.  These storage containers are managed by the CDC NDLP team and secured using SAS tokens.
- The endpoint URL and SAS token are stored in the dest_uri and password fields of the IZ Gateway database, just like uris for other destinations.
- Environment variables or configuration files control which endpoints can be used to transmit routineImmunization and influenza files and restrict users from transmitting to inappropriate endpoints.
- The mime type of the data is determined by the file extension.  At present, only ‘zip’, ‘xsl’, and ‘xslx’ files may be transmitted.  
- User supplied parameters in the API control the destination location in blob storage.  The period, facilityId (sender identifier), and date of submission specify the folders, and the user supplied file or filename override parameter specify the filename:  {period}/{facilityId}/{YYYYMMDD}/{filename}
- All API parameters, the file size, send times, and generated metadata are returned to the user, and are also logged.

# IZ Gateway Release 1.3.0.1

IZ Gateway Release 1.3.0.1 is a patch to IZGW 1.3.0 RC2.  It addresses the issue of case sensitivity of destination addresses.  The destinationId field is case insensitive.
This release candidate includes the following Features:

[IGDD-907](https://support.izgateway.org/browse/IGDD-907) Patch IZGW 1.3.0 RC2 to fix case sensitivity in destinationId lookup

# IZ Gateway Release 1.3.0
IZ Gateway Release 1.3 is the next release of the IZ Gateway Application software provided by Audacious Inquiry. This Release provides several enhancements, as well as address issues, in the IZ Gateway code base that are described in more detail below.

This is expected to be the final release candidate and will being deployed to the Onboarding Environment for one week of testing before pushing to Production.

## New in this Release Candidate
This release candidate includes the following changes [IGDD-878](https://support.izgateway.org/browse/IGDD-878):
- Add key and trust stores for productions environment
- Upgrade tomcat
- Fix exception reporting from pentest
- Fix host naming in cxf autogenerated html

## Existing Capabilities from RC1

### IZ Gateway Running as Docker
IZ Gateway Release 1.3 migrates IZ Gateway services to a containerized orchestration and deployment model, which will allow for faster configurations and deployments, and helps move IZ Gateway towards a continuous testing and deployment process. 

As part of this Release, Horizontal Scalability Testing has been included in the IZ Gateway Release process to ensure that IZ Gateway can dynamically scale when volume increases or decreases. 

This release candidate includes the following Features:
- [IGDD-311](https://support.izgateway.org/browse/IGDD-311) IZ Gateway Running as Docker
   - IZ Gateway will no longer utilize EC2 Servers and will now deploy using ECS Docker images. 
- [IGDD-848](https://support.izgateway.org/browse/IGDD-848) Horizontal Scalability Testing
   - Horizontal Scalability Testing has been included in the IZ Gateway Release process to ensure that IZ Gateway can scale appropriately to load.

### NIST Certified FIPS 140-2 Cipher Module
Release 1.3 implements the NIST Certified encryption module, which is needed so that the IZ Gateway can maintain the authority to operate.

In addition, the NIST Certified encryption capabilities are moved out of individual services and put in front of the IZ Gateway application infrastructure, simplifying implementation and reducing code complexity.

This release candidate includes the following Epic:
- [IGDD-732](https://support.izgateway.org/browse/IGDD-732) NIST Certified Encryption Module

### Quality and Performance Enhancements
As part of Audacious Inquiry’s ongoing development efforts, static analysis tools are run to identify areas of code that are: 1) difficult to maintain, 2) could cause application instability, or 3) do not follow established best practices. IZ Gateway Release 1.3 addresses several high-priority issues identified by these tools. 

This release candidate includes the following Features:
- [IGDD-465](https://support.izgateway.org/browse/IGDD-465) Best Practices
    - The IZ Gateway code base was cleaned up so that "best practice" development standards are maintained within the code.
- [IGDD-503](https://support.izgateway.org/browse/IGDD-503) Miscellaneous Improvements
    - Database connects with TLS V1.2
    - Refactor phiz.data, destination and messageheader to simplify code

# IZ Gateway Release 1.2.1 RC2

This is Release Candidate 2 for IZ Gateway 1.2.1.

IZ Gateway 1.2.1 is the patch release of the IZ Gateway Application software to release 1.2.0 provided by Audacious Inquiry.
This patch addresses issues identified in the IZ Gateway code base that are described in more detail below.

This is expected to be the final release candidate and will being deployed to the Onboarding Environment for one week of testing before pushing to Production.

## New in this Release Candidate
This release candidate upgrades Tomcat to address a vulnerability, and adds support for Elliptic Curve Ciphers.

## Existing Capabilities from RC1
This release candidate includes fixes to the following issues:
1. Logging Improvements
- Report Build.txt content from Resources in logs
- Report database name, host and port in logs after successful connection
- Add connected property to transactionData.destination to track whether there was a successful connection
- Set default transactionData.requestPayloadType to UNKNOWN instead of QBP
- Add requestMSH7, requestMSH10, responseMSH7 and responseMSH10 to logs (timestamp and messageId) for better search
- Improve logging by reducing duplicate pathways
2. Stability Improvements
- Improve stability of testing for Mock IIS
- Remove potential NPE from sendHl7Message handler
- Improve mode (dev vs. prod) detection
- Preserve destination information on message send failure
- Remove potential NPE from fault handler
3. Security Improvements
- Hide secrets in dev mode

## Defect Fixes
This release candidate includes fixes to the following issues:
- [IGDD-791](https://support.izgateway.org/browse/IGDD-791) IZ Gateway accept Elliptic Curve ciphers and Upgrade Tomcat to 8.5.82
- [IGDD-733](https://support.izgateway.org/browse/IGDD-733) Logging Error during startup while accessing build.txt
- [IGDD-776](https://support.izgateway.org/browse/IGDD-776) Report MSH-7 (timestamp) and MSH-10 (message identifier) in logs for diagnostics

# IZ Gateway Release 1.2.0 RC3
This is Release Candidate 3 for IZ Gateway 1.2.0  

IZ Gateway 1.2.0 is the first release of the IZ Gateway Application software provided by Audacious Inquiry.
This initial release addresses critical to quality issues identified in the IZ Gateway code base that are
described in more detail below.

This is expected to be the final release candidate and is being deployed to the Onboarding Environment for two weeks of testing before pushing to Production, presently planned for the week of July 11th.

## New in this Release
This release candidate includes fixes to the following issues:
- Adjusting Integration for Logging of Memory and CPU Utilization
- Improved Performance by Adjusting Configuring Parameters
- Resolve Build.txt file from Resources to allow for drop-in JAR replacement for patch deployment

## Existing Capabilities from RC2
This release candidate includes fixes to the following issues:

- [IGDD-632](https://support.izgateway.org/browse/IGDD-632) Enable Health Checks in the Application
- [IGDD-657](https://support.izgateway.org/browse/IGDD-657) Correct wsa:Action values reported when a Fault occurs
- [IGDD-662](https://support.izgateway.org/browse/IGDD-662) Correct the placement of Summary and Retry elements in Faults
- [IGDD-671](https://support.izgateway.org/browse/IGDD-671) Correct Logfile names in phiz.properties to match values in filebeat.yml
- [IGDD-672](https://support.izgateway.org/browse/IGDD-672) Updated Logback configuration to use a RollingAppender to automatically zip logs after 1 day and retain only 7 days of logs
- [IGDD-675](https://support.izgateway.org/browse/IGDD-675) Correct issues with logging output not matching expected inputs to ElasticSearch
- [IGDD-686](https://support.izgateway.org/browse/IGDD-686) Upgrade Embedded Tomcat from 8.5.77 to 8.5.78

## Existing Capabilities from RC1
### Upgraded to OpenJDK 11
IZ Gateway 1.2 now runs on OpenJDK 11, instead of Oracle JDK 1.8. This change improves overall performance, in addition to enabling static code analysis and CVE detection in dependencies.

### Improved Testing
The new release of IZ Gateway includes additional automated testing, smoke tests to verify correct deployment of the
IZ Gateway application (reducing downtime due to failed deployments), periodic health checks, and a repeatable
and controlled load testing process to ensure that IZ Gateway can handle loads beyond the initial design capacity.
- Added Mocks to /dev/IISService for Smoke Testing
- Smoke Tests Created
- Unit Tests Enabled
- Functional Test Suite Created


### Vulnerability Reduction
IZ Gateway 1.2 upgraded dependencies to the most recent Patch Release for all dependencies.
- Access to /dev/IISService restricted to authorized users
- Password Expiration Tracking

### Improved Logging
Release 1.2 fixes several bugs to improve the reliability and precision of log data and the ability for our operations team to identify and address issues in IZ Gateway performance. Further improvements include the removal of unused log messages, further reducing operational costs by reducing the size of log storage.
- Logging Reports IP Addresses of destination and source endpoints
- Logging generates previously computed fields in ElasticSearch
- Improved Exception Reporting
- Unnecessary logging removed
- Correct Identification of HL7 Errors in Logging
- Logging Reports IP Addresses of destination and source endpoints
- Logging generates previously computed fields in ElasticSearch


### Quality Improvements
Release 1.2 corrects several issues within the code base and how errors are logged. Additional enhancements were made to increase overall quality.
- Corrected issues found during static analysis of IZ Gateway code base.
- Correct Identification of HL7 Errors in Logging
- Improved Application Stability
- Improved Exception Reporting

### Operational Improvements
Operations staff can now access: the overall health status of IZ Gateway, the connectivity status of each
enabled connection endpoint, and information about the current build version.
- Build Version Reporting
- Health Checks
- Connectivity Tests can now be routed to a destination endpoint to verify connectivity by sending a HubRequestHeader element.
- IIS Connectivity Status Reporting
- Fault reporting improvements including retry advice


# IZ Gateway Release 1.4.0 RC4
This is Release Candidate 4 for IZ Gateway Release 1.4. 

IZ Gateway Release 1.4 is the next release of the IZ Gateway Application software provided by Audacious Inquiry. This Release adds a RESTful file submission API that allows authorized users to access the Automated Data Submission service in the IZ Gateway code base so that IZ DataLake and DEX endpoints can be reached to support the Jurisdiction-Initiated CDC Data Reporting use case.

## New in this Release Candidate
This is a patch to RC3 to correct the keystore for the Production environment.

## Existing Capabilities from RC1, RC2 and RC3
This release candidate includes the following changes: 
- [IGDD-920](https://support.izgateway.org/browse/IGDD-920) Envision cannot submit to ADS due to Exception thrown in X500Utils.getParts()

## Release Features
This release candidate includes the following Features: 
- [IGDD-867](https://support.izgateway.org/browse/IGDD-867) RESTful file submission API
- [IGDD-899](https://support.izgateway.org/browse/IGDD-899) Update metadata fields to support current IZDL specs
- [IGDD-909](https://support.izgateway.org/browse/IGDD-909) Add capability to support DEX Endpoints
- [IGDD-909](https://support.izgateway.org/browse/IGDD-909) Add capability to support DEX Endpoints
- [IGDD-916](https://support.izgateway.org/browse/IGDD-916) Enable database configuration of access controls and routing restrictions

## Release Details
IZ Gateway Release 1.4 adds the following functionality to the IZ Gateway code base: 

- Adds Submission Endpoint: POST /rest/ads/{destinationId}
  - IZ Gateway 1.4 introduces a new RESTful API, which is documented [here](https://izgateway.github.io/ads-file-submission-app/) 
  - Access to this API is restricted to users with authorized client certificates.
  
- Access to other APIs:
  - ADS Endpoint status: GET /rest/ads/status/{id}
    - Uses Azure HTTP API to list at most one element from the contents of storage container.
  - ADS/SOAP Status: GET /rest/status and /rest/status/{id}
    - Calls IZ Gateway connectivityTest for SOAP endpoints, or internally uses /rest/ADS/status to report status and connectivity.
  - Configuration: GET /rest/config and /rest/config/{id}
    - Returns configuration endpoint URL, destination id, and version for endpoints.
  - GET /rest/ads/{destinationId}
    - Retrieval of content submitted to azurite endpoint (Used in onboarding for verification activities).
  - DELETE /rest/ads/{destinationId}
    - Deletion of content submitted to azurite endpoint (Used in onboarding for storage management).
  - These endpoints are accessible only to those certificates which are whitelisted for those activities in the system configuration.

- Access to IZ DataLake (Azure) and DEX Endpoints routed through the Automated Data Submission service
  - IZ DataLake Endpoints (Azure)
    - ndlp-izgw-ri: endpoint for submission of Routine Immunization reports to the IZ DataLake test environment. This Azure blob storage container is managed by the CDC NDLP team and secured using SAS tokens.
      - Routine Immunization report files must be zipped
    -  ndlp-izgw-flu: endpoint for submission of Flu Immunization reports to the IZ DataLake test environment. This Azure blob storage container is managed by the CDC NDLP team and secured using SAS tokens.
       - Flu Immunization report files should stay in XLS or XLSX format 
    - azurite: a test endpoint emulating the IZ DataLake transport mechanism for both Routine and Flu Immunization reports.
      - This endpoint runs internally in IZ Gateway nodes at port 10000 within the docker image, but that port is not exposed to users.
      - Data in this endpoint is deleted when storage is not available to send a new file.
    - The endpoint URL and SAS token are stored in the dest_uri and password fields of the IZ Gateway database, just like uris for other destinations.
    - The mime type of the data is determined by the file extension.  At present, only ‘zip’, ‘xsl’, and ‘xslx’ files may be transmitted.  
    - User supplied parameters in the API control the destination location in blob storage.  The period, facilityId (sender identifier), and date of submission specify the folders, and the user supplied file or filename override parameter specify the filename:  {period}/{facilityId}/{YYYYMMDD}/{filename}
  - DEX Endpoints
    - dex-stg: an endpoint for submission of Routine Immunizations for the CDC Data Exchange Front door (DEX)
    - dex-dev: an endpoint emulating the protocol for DEX for submission of Routine Immunizations and Influenza reporting

  - Environment variables or configuration files control which endpoints can be used to transmit Routine and Flue Immunization report files and restrict users from transmitting to inappropriate endpoints.
  - All API parameters, the file size, send times, and generated metadata are returned to the user, and are also logged.

# IZ Gateway Release 1.4.0 RC3

This is Release Candidate 3 for IZ Gateway Release 1.4. 

IZ Gateway Release 1.4 is the next release of the IZ Gateway Application software provided by Audacious Inquiry. This Release adds a RESTful file submission API that allows authorized users to access the Automated Data Submission service in the IZ Gateway code base so that IZ DataLake and DEX endpoints can be reached to support the Jurisdiction-Initiated CDC Data Reporting use case.


## New in this Release Candidate
This release candidate includes the following changes: 
- [IGDD-920](https://support.izgateway.org/browse/IGDD-920) Envision cannot submit to ADS due to Exception thrown in X500Utils.getParts()

# IZ Gateway Release 1.4.0 RC2

This is Release Candidate 2 for IZ Gateway Release 1.4. 

IZ Gateway Release 1.4 is the next release of the IZ Gateway Application software provided by Audacious Inquiry. This Release adds a RESTful file submission API that allows authorized users to access the Automated Data Submission service in the IZ Gateway code base so that IZ DataLake and DEX endpoints can be reached to support the Jurisdiction-Initiated CDC Data Reporting use case.

This is expected to be the final release candidate and will being deployed to the Onboarding Environment for at least one week of testing before pushing to Production.

## New in this Release Candidate
This release candidate includes the following changes: 
- [IGDD-909](https://support.izgateway.org/browse/IGDD-909) Add capability to support DEX Endpoints
- [IGDD-916](https://support.izgateway.org/browse/IGDD-916) Enable database configuration of access controls and routing restrictions

## Existing Capabilities from RC1

## Release Features
This release candidate includes the following Features: 
- [IGDD-867](https://support.izgateway.org/browse/IGDD-867) RESTful file submission API
- [IGDD-899](https://support.izgateway.org/browse/IGDD-899) Update metadata fields to support current IZDL specs
- [IGDD-909](https://support.izgateway.org/browse/IGDD-909) Add capability to support DEX Endpoints

## Release Details
IZ Gateway Release 1.4 adds the following functionality to the IZ Gateway code base: 

- Adds Submission Endpoint: POST /rest/ads/{destinationId}
  - IZ Gateway 1.4 introduces a new RESTful API, which is documented [here](https://izgateway.github.io/ads-file-submission-app/) 
  - Access to this API is restricted to users with authorized client certificates.
  
- Access to other APIs:
  - ADS Endpoint status: GET /rest/ads/status/{id}
    - Uses Azure HTTP API to list at most one element from the contents of storage container.
  - ADS/SOAP Status: GET /rest/status and /rest/status/{id}
    - Calls IZ Gateway connectivityTest for SOAP endpoints, or internally uses /rest/ADS/status to report status and connectivity.
  - Configuration: GET /rest/config and /rest/config/{id}
    - Returns configuration endpoint URL, destination id, and version for endpoints.
  - GET /rest/ads/{destinationId}
    - Retrieval of content submitted to azurite endpoint (Used in onboarding for verification activities).
  - DELETE /rest/ads/{destinationId}
    - Deletion of content submitted to azurite endpoint (Used in onboarding for storage management).
  - These endpoints are accessible only to those certificates which are whitelisted for those activities in the system configuration.

- Access to IZ DataLake (Azure) and DEX Endpoints routed through the Automated Data Submission service
  - IZ DataLake Endpoints (Azure)
    - ndlp-izgw-ri: endpoint for submission of Routine Immunization reports to the IZ DataLake test environment. This Azure blob storage container is managed by the CDC NDLP team and secured using SAS tokens.
      - Routine Immunization report files must be zipped
    -  ndlp-izgw-flu: endpoint for submission of Flu Immunization reports to the IZ DataLake test environment. This Azure blob storage container is managed by the CDC NDLP team and secured using SAS tokens.
       - Flu Immunization report files should stay in XLS or XLSX format 
    - azurite: a test endpoint emulating the IZ DataLake transport mechanism for both Routine and Flu Immunization reports.
      - This endpoint runs internally in IZ Gateway nodes at port 10000 within the docker image, but that port is not exposed to users.
      - Data in this endpoint is deleted when storage is not available to send a new file.
    - The endpoint URL and SAS token are stored in the dest_uri and password fields of the IZ Gateway database, just like uris for other destinations.
    - The mime type of the data is determined by the file extension.  At present, only ‘zip’, ‘xsl’, and ‘xslx’ files may be transmitted.  
    - User supplied parameters in the API control the destination location in blob storage.  The period, facilityId (sender identifier), and date of submission specify the folders, and the user supplied file or filename override parameter specify the filename:  {period}/{facilityId}/{YYYYMMDD}/{filename}
  - DEX Endpoints
    - dex-stg: an endpoint for submission of Routine Immunizations for the CDC Data Exchange Front door (DEX)
    - dex-dev: an endpoint emulating the protocol for DEX for submission of Routine Immunizations and Influenza reporting

  - Environment variables or configuration files control which endpoints can be used to transmit Routine and Flue Immunization report files and restrict users from transmitting to inappropriate endpoints.
  - All API parameters, the file size, send times, and generated metadata are returned to the user, and are also logged.


# IZ Gateway Release 1.4.0
IZ Gateway Release 1.4 is the next release of the IZ Gateway Application software provided by Audacious Inquiry. This Release adds a RESTful file submission API that allows authorized users to access the Automated Data Submission service in the IZ Gateway code base so that IZ DataLake and DEX endpoints can be reached to support the Jurisdiction-Initiated CDC Data Reporting use case.

This is expected to be the final release candidate and will being deployed to the Onboarding Environment for at least one week of testing before pushing to Production.

## Release Features
This release candidate includes the following Features: 
- [IGDD-867](https://support.izgateway.org/browse/IGDD-867) RESTful file submission API
- [IGDD-899](https://support.izgateway.org/browse/IGDD-899) Update metadata fields to support current IZDL specs
- [IGDD-909](https://support.izgateway.org/browse/IGDD-909) Add capability to support DEX Endpoints

## Release Details
The release candidate is described in more detail here:
- Submission Endpoint: POST /rest/ads/{destinationId}
  - IZ Gateway 1.4 introduces a new RESTful API, which is documented [here](https://izgateway.github.io/ads-file-submission-app/) 
- Access to this API IZ Gateway is restricted to users with authorized client certificates.
- Access to other APIs:
  - ADS Endpoint status: GET /rest/ads/status/{id}
    - Uses Azure HTTP API to list at most one element from the contents of storage container.
  - ADS/SOAP Status: GET /rest/status and /rest/status/{id}
    - Calls IZ Gateway connectivityTest for SOAP endpoints, or internally uses /rest/ADS/status to report status and connectivity.
  - Configuration: GET /rest/config and /rest/config/{id}
    - Returns configuration endpoint URL, destination id, and version for endpoints.
  - GET /rest/ads/{destinationId}
    - Retrieval of content submitted to azurite endpoint (Used in onboarding for verification activities).
  - DELETE /rest/ads/{destinationId}
    - Deletion of content submitted to azurite endpoint (Used in onboarding for storage management).
  - These endpoints are accessible only to those certificates which are whitelisted for those activities in the system configuration.

# IZ DataLake Endpoint (Azure)
- A new endpoint is available for users to test their submissions that is implemented using the Microsoft ‘azurite’ application, emulating azure secure storage.  This endpoint runs internally in IZ Gateway nodes at port 10000 within the docker image, but that port is not exposed to users.  Data in this endpoint is deleted when storage is not available to send a new file.
- Other endpoints ndlp-izgw-ri, and ndlp-izgw-flu are actual Azure blob storage containers.  These storage containers are managed by the CDC NDLP team and secured using SAS tokens.
- The endpoint URL and SAS token are stored in the dest_uri and password fields of the IZ Gateway database, just like uris for other destinations.
- Environment variables or configuration files control which endpoints can be used to transmit routineImmunization and influenza files and restrict users from transmitting to inappropriate endpoints.
- The mime type of the data is determined by the file extension.  At present, only ‘zip’, ‘xsl’, and ‘xslx’ files may be transmitted.  
- User supplied parameters in the API control the destination location in blob storage.  The period, facilityId (sender identifier), and date of submission specify the folders, and the user supplied file or filename override parameter specify the filename:  {period}/{facilityId}/{YYYYMMDD}/{filename}
- All API parameters, the file size, send times, and generated metadata are returned to the user, and are also logged.

# IZ Gateway Release 1.3.0.1

IZ Gateway Release 1.3.0.1 is a patch to IZGW 1.3.0 RC2.  It addresses the issue of case sensitivity of destination addresses.  The destinationId field is case insensitive.
This release candidate includes the following Features:

[IGDD-907](https://support.izgateway.org/browse/IGDD-907) Patch IZGW 1.3.0 RC2 to fix case sensitivity in destinationId lookup

# IZ Gateway Release 1.3.0
IZ Gateway Release 1.3 is the next release of the IZ Gateway Application software provided by Audacious Inquiry. This Release provides several enhancements, as well as address issues, in the IZ Gateway code base that are described in more detail below.

This is expected to be the final release candidate and will being deployed to the Onboarding Environment for one week of testing before pushing to Production.

## New in this Release Candidate
This release candidate includes the following changes [IGDD-878](https://support.izgateway.org/browse/IGDD-878):
- Add key and trust stores for productions environment
- Upgrade tomcat
- Fix exception reporting from pentest
- Fix host naming in cxf autogenerated html

## Existing Capabilities from RC1

### IZ Gateway Running as Docker
IZ Gateway Release 1.3 migrates IZ Gateway services to a containerized orchestration and deployment model, which will allow for faster configurations and deployments, and helps move IZ Gateway towards a continuous testing and deployment process. 

As part of this Release, Horizontal Scalability Testing has been included in the IZ Gateway Release process to ensure that IZ Gateway can dynamically scale when volume increases or decreases. 

This release candidate includes the following Features:
- [IGDD-311](https://support.izgateway.org/browse/IGDD-311) IZ Gateway Running as Docker
   - IZ Gateway will no longer utilize EC2 Servers and will now deploy using ECS Docker images. 
- [IGDD-848](https://support.izgateway.org/browse/IGDD-848) Horizontal Scalability Testing
   - Horizontal Scalability Testing has been included in the IZ Gateway Release process to ensure that IZ Gateway can scale appropriately to load.

### NIST Certified FIPS 140-2 Cipher Module
Release 1.3 implements the NIST Certified encryption module, which is needed so that the IZ Gateway can maintain the authority to operate.

In addition, the NIST Certified encryption capabilities are moved out of individual services and put in front of the IZ Gateway application infrastructure, simplifying implementation and reducing code complexity.

This release candidate includes the following Epic:
- [IGDD-732](https://support.izgateway.org/browse/IGDD-732) NIST Certified Encryption Module

### Quality and Performance Enhancements
As part of Audacious Inquiry’s ongoing development efforts, static analysis tools are run to identify areas of code that are: 1) difficult to maintain, 2) could cause application instability, or 3) do not follow established best practices. IZ Gateway Release 1.3 addresses several high-priority issues identified by these tools. 

This release candidate includes the following Features:
- [IGDD-465](https://support.izgateway.org/browse/IGDD-465) Best Practices
    - The IZ Gateway code base was cleaned up so that "best practice" development standards are maintained within the code.
- [IGDD-503](https://support.izgateway.org/browse/IGDD-503) Miscellaneous Improvements
    - Database connects with TLS V1.2
    - Refactor phiz.data, destination and messageheader to simplify code
