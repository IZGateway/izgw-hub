# IZ Gateway Hub — Copilot Instructions

## Project overview

`izgw-hub` is a SOAP/HL7v2 data exchange hub that routes immunization messages
between national provider organizations and state/territorial Immunization
Information Systems (IIS). It also supports CDC Automated Data Submission (ADS)
for monthly/quarterly vaccination reports.

The hub is a **Spring Boot + Tomcat** application that requires mutual TLS (mTLS)
on every inbound and outbound connection, uses **Bouncy Castle FIPS** for all
cryptographic operations, and runs in **AWS ECS** backed by **DynamoDB** as its
primary datastore.

This repo is part of the IZ Gateway GitHub organization. Shared types, interfaces,
and utilities live in [`izgw-core`](https://github.com/IZGateway/izgw-core);
dependency versions are managed in [`izgw-bom`](https://github.com/IZGateway/izgw-bom).
Both are published to GitHub Packages and referenced in `pom.xml`.

---

## Build and test

### Prerequisites

- Java 21 JDK (`JAVA_HOME` must be set)
- Maven 3.9+
- `COMMON_PASS` env var (keystore password) — required for any build that touches SSL
- GitHub Packages credentials in `~/.m2/settings.xml` (for `izgw-core` and `izgw-bom`)

### Commands

```cmd
rem Compile and run unit tests (no Docker, no AWS)
mvn clean package

rem Full build including Docker image (requires COMMON_PASS and AWS credentials)
set COMMON_PASS=<password>
mvn clean install

rem Run a single test class
mvn test -Dtest=AccessControlTests

rem Run a single test method
mvn test -Dtest=MetadataBuilderComputationTests#testSomething

rem Generate site/Javadoc
mvn site

rem Check for CVEs (OWASP)
mvn verify -DskipTests
```

> In CI, the build sets `SPRING_DATABASE=jpa` to run unit tests against an
> in-memory H2/JPA store. Locally, if you have AWS credentials and a live
> DynamoDB table, set `SPRING_DATABASE=dynamodb` and `AMAZON_DYNAMODB_TABLE=izgateway-devalb`.

### Test conventions

- Test class names **must** end in `Tests` (e.g., `AccessControlTests`), not `Test`.
  Surefire is configured for `**/*Tests.java`.
- The mock IIS service in `gov.cdc.izgateway.soap.mock` provides a local stub for
  integration testing without a live IIS endpoint.

### Newman integration tests

End-to-end tests run against `dev.izgateway.org` using Newman (Postman CLI).
These require the `TESTING_CERT`, `TESTING_KEY`, and `TESTING_PASS` secrets and
are only run in CI. To run manually:

```bash
cd testing/testdata
newman run ../scripts/IZGW_2.0_Integration_Test.postman_collection.json \
  --folder "Working" \
  --environment ../scripts/dev.postman_environment.json \
  --ssl-extra-ca-certs certs/izgwroot.pem \
  --ssl-client-cert ../newman.pem \
  --ssl-client-key ../newman.key \
  --ssl-client-passphrase $TESTING_PASS \
  --insecure
```

---

## Architecture

### Request flow

```
Provider / IIS → ALB (mTLS termination) → ECS Hub container (port 443)
    → BaseGatewayController (routing)
    → DestinationService (lookup in DynamoDB)
    → MessageSender (outbound mTLS to IIS)
    → IIS / Mock IIS
```

### Package layout (`gov.cdc.izgateway.*`)

| Package | Purpose |
|---------|---------|
| `hub` | Main gateway controllers (`HubWSDLController`, `CDCWSDLController`) both extending `BaseGatewayController` → `SoapControllerBase` (from `izgw-core`) |
| `hub.service` | Business logic: `DestinationService`, `CertificateStatusService`, etc. |
| `hub.repository` | `RepositoryFactory` interface — swap DynamoDB ↔ JPA via `SPRING_DATABASE` env var |
| `hub.service.accesscontrol` | IP- and certificate-based access control |
| `ads` | CDC Automated Data Submission — accepts large files via TUS protocol, ships to DEX |
| `dynamodb` | DynamoDB models (`AccessControl`, `Destination`, `MessageHeader`, …) and Spring Data repositories |
| `elastic` | Elastic Search client for structured logging (complements Filebeat) |
| `soap.mock` | Mock IIS stub used by unit/integration tests |
| `status` | `/status` REST endpoints and scheduled health checks |
| `gov.cdc.perf.histogram` | Internal latency histogram for metrics |

### Repository abstraction

`RepositoryFactory` selects the backend at startup based on `SPRING_DATABASE`:
- `dynamodb` (production) — AWS DynamoDB via the enhanced client
- `jpa` (CI unit tests) — Hibernate JPA, typically with H2 or the deprecated MySQL config

> **MySQL is deprecated.** All MySQL environment variables (`MYSQL_HUB_PASS`,
> `MYSQL_HOST`, `MYSQL_DB_NAME`, etc.) and `DATABASE_URL` are on a path to
> removal. Do not add new MySQL code paths.

### Cryptography — Bouncy Castle FIPS

All TLS and keystore operations use **BC-FIPS** (`bc-fips`, `bcpkix-fips`,
`bctls-fips`). Keystores use `bcfks` format with provider `BCFIPS`. This is a
NIST-certified FIPS 140-2 validated provider. Do not use the standard JDK
`JKS`/`PKCS12` keystore types or standard `javax.crypto` operations where FIPS
compliance is required.

When updating BC-FIPS jars in `pom.xml`, also update the matching jars copied
into the Docker image from `docker/data/lib/bcfips/`.

### SSL keystores

`conf/ssl/` in this repository contains keystores **only for unit tests and local CI**.
See `conf/ssl/Certificates.md` for details. In deployed environments:

- Keystores are provisioned on **AWS EFS** (`fs-0c76fe796cfc1d1e8`, access point
  `fsap-0c8cf40dbde770a68`) mounted at `/usr/share/phiz-web-ws/conf/ssl` inside
  each ECS container.
- TLS certificates for `dev.izgateway.org` are managed by **AWS Certificate Manager**
  and attached to the ALB.
- The `security.ssl-path` property (set via `SSL_SHARE` env var) controls which
  path the application uses. Defaults to the local `conf/ssl` when `SSL_SHARE` is unset.

---

## Key conventions

### Configuration

All tuneable behaviour is in `src/main/resources/application.yml`, parameterised
via environment variables. The pattern is `${ENV_VAR:default}`. Key variables:

| Variable | Purpose |
|----------|---------|
| `COMMON_PASS` | Password for all keystores (inbound + outbound) |
| `SPRING_DATABASE` | `dynamodb` (prod) or `jpa` (test) |
| `PHIZ_MODE` | `prod` masks PHI in logs; `dev` shows HL7 content |
| `ELASTIC_API_KEY` | Shared key for Elastic Search logging |
| `AMAZON_DYNAMODB_TABLE` | DynamoDB table prefix (e.g., `izgateway-devalb`) |
| `SSL_SHARE` | Path prefix for EFS keystore directory |
| `PHIZ_SERVER_HOSTNAME` | Public hostname (default `dev.izgateway.org`) |

### Docker image

Base image: `ghcr.io/izgateway/alpine-node-openssl-fips:latest` (Alpine + Node.js +
OpenSSL in FIPS mode). Exposes ports `443` (HTTPS), `9081` (management), `8000`
(local DynamoDB). Includes Filebeat and Metricbeat for Elastic logging.

### CI/CD pipeline (`maven.yml`)

Two-job pipeline: `build` then `verify`.

- **build**: Compiles, runs unit tests, runs OWASP dependency check, builds Docker
  image, pushes to ECR (`izgateway-dev-phiz-web-ws`) and GHCR, deploys to
  ECS cluster `izgateway-dev-izgateway-services` (service `izgateway-devalb-service`).
- **verify**: Waits for ECS stability, then runs Newman integration tests against
  `dev.izgateway.org`. On success, tags the image `:good` in ECR.
- **push-to-aphl**: Release-branch pushes only — promotes image to APHL environment.

Triggers: push/PR to `Release*` branches, push/PR to `develop`, scheduled nightly.

### Versioning

SNAPSHOT builds: `{version}-IZGW-SNAPSHOT`  
Release builds: `{version}-IZGW-RELEASE` (set automatically when pushing to a `Release*` branch)

Image tags include the run number: `{version}-SNAPSHOT-{run_number}`

### OWASP dependency check

CVE failures at CVSS ≥ 7 block the build. Add false-positive suppressions to
`dependency-suppression.xml`, **not** to `pom.xml`.

### TCP keepalive in CI

Newman runs set `NODE_OPTIONS=--require .github/scripts/enable-keepalive.js` to
patch Node.js HTTP sockets with `setKeepAlive(true, 15000)`. This prevents ETIMEDOUT
on long requests through the ALB (60 s idle timeout) on GitHub-hosted runners.
The runner also sets `net.ipv4.tcp_keepalive_time=15` via `sysctl`.
