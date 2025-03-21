# IZ Gateway Release 2.2.1
The IZ Gateway Hub 2.2.1 release
* IGDD-1934 Add support for Measles reporting to ADS
* IGDD-1938 Circuit Breakers are Not Being Reset
    - Improve throw and reset logic for circuit breakers
    - Add /rest/reset API on a single host
    - Add ?reset=true parameter on /rest/refresh API to enable a reset during a refresh on all hosts
  
* IGDD-1942 Address CVE-2025-24813 in IZG Hub
    - Upgraded to spring-boot 3.4.3
  

