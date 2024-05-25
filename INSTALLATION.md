# Installation
IZ Gateway 2.0 is released as a Docker image for deployment in Fargate ECS or similar containerized environment.
1. Node Configuration
IZ Gateway 2/0 requires 2 threads to complete a single transaction and runs best on containers with at least ??? Virtual CPUs.  In can operate with fewer processors, but optimal performance requires at least ???.  Load testing has shown that IZ Gateway operating in a 4 vCPU Configuration will consume at least ???Gb of RAM, and should therefore be configured with ??? GB of memory for optimal operation.

2. EFS Configuration
IZ Gateway is designed to access shared storage mounted in the  Configuration folder at /usr/share/phiz-web-ws/conf.  This folder contains one subfolder.  
     
   - ssl
     This folder is used to store SSL configuration data (key and trust stores) for the IZ Gateway Application
     
3. Database Access Control Changes:
The IZ Gateway DB user (see MYSQL_HUB_NAME below) must be granted the following permissions in the database to enable
backups of the database to be made.  Backups will be stored to /usr/share/phiz-web-ws/conf/backups.
NOTE: Change schemaname and username to appropriate values for the database.

```
grant SELECT, LOCK TABLES, SHOW VIEW, EVENT, TRIGGER ON  schemaname.* to username@'%';
grant PROCESS ON  *.* to username@'%';

```

4. Environment Variables
The following environment variables need to be set for proper operation of IZ Gateway in a Docker environment

See [CONFIGURATION.md](CONFIGURATION.md) for a full list of configuration parameters.

```

MYSQL_DB_NAME = phiz // Name of database schema
MYSQL_HOST= masked // DNS name of MySQL or RDS Database host
MYSQL_HUB_NAME = phizuser // Username for access to MySQL Database
MYSQL_HUB_PASS = masked // Password for access to MySQL Database

PHIZ_SERVER_HOSTNAME = dev.phiz-project.org // Hostname of server
PHIZ_SERVER_PORT = 443 // External port of server
PHIZ_MODE = prod // set to dev for onboarding
PHIZ_WS_IIS_HUB_MAX_MESSAGE_SIZE = 4194304 // Maximum message size for server operations
PHIZ_WS_IIS_MAX_MESSAGE_SIZE = 4194304 // Maximum message size for mock IIS operations

COMMON_PASS = masked // Common password for key and trust store
PHIZ_CRYPTO_STORE_KEY_TOMCAT_SERVER_FILE:/usr/share/phiz-web-ws/conf/ssl/???.bcfks
PHIZ_CRYPTO_STORE_TRUST_TOMCAT_SERVER_FILE:/usr/share/phiz-web-ws/conf/ssl/???.bcfks
PHIZ_CRYPTO_STORE_KEY_WS_CLIENT_FILE:/usr/share/phiz-web-ws/conf/ssl/???.bcfks
PHIZ_CRYPTO_STORE_TRUST_WS_CLIENT_FILE:/usr/share/phiz-web-ws/conf/ssl/izgw_client_trust.bcfks



ELASTIC_API_KEY = masked // Key for elastic search endpoint
ELASTIC_ENV_TAG = prod // Tag for environment, should contain onboard, prod, or stage
ELASTIC_HOST = https://audacioussearch.es.us-east-1.aws.found.io:9243
ELASTIC_INDEX = ???

```

3. Recommended Scaling Configuration
IZ Gateway should be configured as follows for appropriate scaling:
```
Availability Zones: 3
Minimum Nodes: 3   // One node per availability zone
Maximum Nodes: 10  // Supports ??? Million Tx / Day
Health Check Grace Period: 0
Deployment Batch Size: 4 // Maximum Number of nodes to deploy at 1 time
Port Mapping: 443->443, 9081->9081
Security Group: 
   443 open to 0.0.0.0/0
   9081 open to SSH Endpoints used by operations staff
Scale up CPU Utilization: 35%
Scale-out cooldown: 30 seconds
Scale-in cooldown: 60 seconds
```

4. The health check endpoint for IZ Gateway is https://localhost:9081/rest/healthy
If the endpoint is healthy, it will return a 200 OK Message.  If the endpoint returns an HTTP 500 error, the endpoint
is NOT healthy.  It may automatically return to a healthy state if an intermittent error occurred (e.g., out of memory, or database access error) and it later recovers.
IZ Gateway should routinely be healthy within 1 minute of being launched. The 95th percentile startup time is < 40s from JVM startup.

Configure health checks for in the first container panel of the task definition in AWS using the following command:
```
CMD-SHELL, curl --fail http://localhost:9081/rest/healthy || exit 1
```
Set the start period for the container health check to 30 seconds.
