#IZ Gateway Configuration

IZ Gateway is configured through bean properties stored in [application.yml](src/main/resources/application.yml). Configurable properties are enabled by references to environment
variables within the application.yml file.


## Task Configuration Properties
Task configuration properties can be passed in environment variables.

* __PHIZ_MODE__  prod

  Set to dev to view HL7 Message Content, e.g., in onboarding (possibly containing PHI), or prod to mask HL7 Content in production.

* __COMMON_PASS__

  Common password for key and trust store

* __ELASTIC_API_KEY__

  Key for elastic search endpoint

* __ELASTIC_ENV_TAG__ prod

  Tag for environment, should contain onboard, prod, or stage

* __MYSQL_DB_NAME__ phiz

  Name of database schema

* __MYSQL_HOST__ localhost

  DNS name of MySQL or RDS Database host

* __MYSQL_HUB_NAME__ phizuser

  Username for access to MySQL Database

* __MYSQL_HUB_PASS__

  Password for access to MySQL Database

* __PHIZ_DATA_DB_PORT__ 3306

  Port for database connection

* __PHIZ_SERVER_HOSTNAME__ dev.phiz-project.org
  Hostname of server

* __PHIZ_SERVER_PORT__ 443

  External port of server


* __COMMON_PASS__

  Environment setting to use to set default password for all key and trust stores.

* __PHIZ_CRYPTO_STORE_TRUST_WS_CLIENT_FILE__ /usr/share/phiz-web-ws/conf/ssl/phiz_store_trust_ws_client.bcfks

  Location of trust store file for IIS outbound connections (merged trust for onboarding and production).

* __PHIZ_CRYPTO_STORE_KEY_TOMCAT_SERVER_FILE__ /usr/share/phiz-web-ws/conf/ssl/dev_store_key.bcfks

  Location of server Key store for inbound connections. Use dev_store_key.bcfks for onboarding, or prod_store_key.bcfks for production.

* __PHIZ_WS_IIS_HUB_MAX_MESSAGE_SIZE__ 4194304

  The maximum message size accepted by the IZ Gateway Hub on inbound or outbound messages.
  Change to 4194304 (4Mb) for production, set to 65536 for development.

* __PHIZ_WS_IIS_MAX_MESSAGE_SIZE__ 4194304

  The maximum message size produced by the IZ Gateway Mock for inbound or outbound messages.
  Change to 4194304 (4Mb) for production, set to 32768 for development.

* __PHIZ_WS_ADS_VERSION__ V2023-09-01

  The source version to use for ADS Metadata.  The V2023-09-01 version is for the version
  supporting RSV reporting, previously this was set to V2022-12-31.

* __HUB_SECURITY_IP_FILTER_ENABLED__

Turns IP filtering on or off in the application. If set to true, it is expected that HUB_SECURITY_IP_FILTER_ALLOWED_CIDR has been configured with allowed IP CIDR blocks. If set to false, any connection from any IP address will be able to connect (assuming they have satisfied the other security requirements of the application).

By default, this is set to false.

* __HUB_SECURITY_IP_FILTER_ALLOWED_CIDR__

A comma separate list of IP CIDR blocks which are allowed to connect to the application. This is ignored if HUB_SECURITY_IP_FILTER_ENABLED is set to false.

IP CIDRs can be specified for IPv4 and IPv6.

To specify, for example, allowing localhost for both IPv4 and IPv6 you would set this to: 127.0.0.1/32,::1/128
