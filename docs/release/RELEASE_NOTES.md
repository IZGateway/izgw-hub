# IZ Gateway Release 2.1.0
The IZ Gateway Hub 2.1.0 is in preparation for code sharing across IZ Gateway Hub and the IZ Gateway Transformation Service.

* Split IZ Gateway into separate repositories for core capabilities [izgw-core](https://github.com/izgateway/izgw-core) and the
IZ Gateway Hub [izgw-hub](https://github.com/izgateway/izgw-hub)
* Update access control table to support multiple environments and certificate blacklisting
* Fix two NPEs in Message Sender
* Added Security Checks on Message Sending to Verify Endpoint is for correct Environment
* Fixed issue with filebeat and metricbeat race condition on startup
* Address data validation for ADS uploads from distant timezones

