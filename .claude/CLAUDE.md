# izgw-hub — Project Instructions

SOAP/HL7v2 data exchange hub routing immunization messages between national providers
and state/territorial IIS. Also supports CDC ADS monthly/quarterly reports.

Spring Boot + Tomcat, mutual TLS on all connections, Bouncy Castle FIPS crypto, AWS ECS + DynamoDB.

**Skills:** `java-maven-style`

**Public repo** — follow IZ Gateway Public Repo Policy (in global CLAUDE.md).

---

## Build & Test

### Prerequisites
- Java 21 (`JAVA_HOME` set)
- Maven 3.9+
- `COMMON_PASS` env var (keystore password) — required for SSL builds
- GitHub Packages credentials in `~/.m2/settings.xml`

```cmd
rem Compile and run unit tests (no Docker, no AWS)
mvn clean package

rem Full build including Docker image
set COMMON_PASS=<password>
mvn clean install

rem Single test class / method
mvn test -Dtest=AccessControlTests
mvn test -Dtest=MetadataBuilderComputationTests#testSomething

rem Generate site/Javadoc
mvn site

rem OWASP CVE check
mvn verify -DskipTests
```

In CI: `SPRING_DATABASE=jpa` for H2/JPA unit tests. Locally with AWS: `SPRING_DATABASE=dynamodb`, `AMAZON_DYNAMODB_TABLE=izgateway-devalb`.

**Test class names must end in `Tests`** — Surefire is configured for `**/*Tests.java`.

---

## Architecture

### Request Flow

```
Provider/IIS → ALB (mTLS termination) → ECS Hub (port 443)
    → BaseGatewayController
    → DestinationService (DynamoDB lookup)
    → MessageSender (outbound mTLS)
    → IIS / Mock IIS
```

### Package Layout (`gov.cdc.izgateway.*`)

| Package | Purpose |
|---------|---------|
| `hub` | `HubWSDLController`, `CDCWSDLController` (both extend `BaseGatewayController`) |
| `hub.service` | `DestinationService`, `CertificateStatusService`, etc. |
| `hub.repository` | `RepositoryFactory` — swap DynamoDB ↔ JPA via `SPRING_DATABASE` |
| `hub.service.accesscontrol` | IP- and certificate-based access control |
| `ads` | CDC ADS — TUS protocol file upload, ships to DEX |
| `dynamodb` | DynamoDB models and Spring Data repositories |
| `elastic` | Elasticsearch client for structured logging |
| `soap.mock` | Mock IIS stub for tests |
| `status` | `/status` REST endpoints and health checks |

### Repository Abstraction

`RepositoryFactory` selects backend at startup based on `SPRING_DATABASE`:
- `dynamodb` (production) — AWS DynamoDB via enhanced client
- `jpa` (CI) — Hibernate JPA with H2

> **MySQL is deprecated.** Do not add new MySQL code paths.

---

## Cryptography — Bouncy Castle FIPS

All TLS and keystore operations use **BC-FIPS** (`bc-fips`, `bcpkix-fips`, `bctls-fips`).
Keystores use `bcfks` format with provider `BCFIPS`. Do not use JDK `JKS`/`PKCS12` or
standard `javax.crypto` where FIPS compliance is required.

When updating BC-FIPS jars in `pom.xml`, also update the matching jars in `docker/data/lib/bcfips/`.

---

## SSL Keystores

`conf/ssl/` — **unit tests and local CI only.** In deployed environments:
- Keystores on AWS EFS (`fs-0c76fe796cfc1d1e8`, access point `fsap-0c8cf40dbde770a68`)
  mounted at `/usr/share/phiz-web-ws/conf/ssl`
- `security.ssl-path` controlled by `SSL_SHARE` env var

---

## Key Configuration Variables

| Variable | Purpose |
|----------|---------|
| `COMMON_PASS` | Password for all keystores |
| `SPRING_DATABASE` | `dynamodb` (prod) or `jpa` (test) |
| `PHIZ_MODE` | `prod` masks PHI; `dev` shows HL7 content |
| `ELASTIC_API_KEY` | Elasticsearch logging |
| `AMAZON_DYNAMODB_TABLE` | DynamoDB table prefix (e.g., `izgateway-devalb`) |
| `SSL_SHARE` | Path prefix for EFS keystore directory |
| `PHIZ_SERVER_HOSTNAME` | Public hostname (default `dev.izgateway.org`) |

---

## Docker Image

Base: `ghcr.io/izgateway/alpine-node-openssl-fips:latest`
Exposes: `443` (HTTPS), `9081` (management), `8000` (local DynamoDB)
Includes Filebeat and Metricbeat for Elastic logging.

---

## CI/CD Pipeline (`maven.yml`)

- **build**: Compile, unit tests, OWASP check, Docker image → ECR (`izgateway-dev-phiz-web-ws`) + GHCR → deploy to ECS `izgateway-dev-izgateway-services`
- **verify**: Wait for ECS stability → Newman integration tests against `dev.izgateway.org` → tag `:good` on success
- **push-to-aphl**: Release branches only — promotes image to APHL environment

Triggers: push/PR to `Release*`, push/PR to `develop`, nightly.

---

## Versioning

- SNAPSHOT: `{version}-IZGW-SNAPSHOT`
- Release: `{version}-IZGW-RELEASE` (auto-set on `Release*` branch push)
- Image tags include run number: `{version}-SNAPSHOT-{run_number}`

---

## OWASP

CVE failures at CVSS ≥ 7 block the build. Add suppressions to `dependency-suppression.xml`, not `pom.xml`.

---

## Newman Integration Tests

Require `TESTING_CERT`, `TESTING_KEY`, `TESTING_PASS`. Run in CI only.
Uses `enable-keepalive.js` patch for ALB idle timeout on GitHub runners.

---

## Shared Libraries

- `izgw-core` — shared types, interfaces, utilities (published to GitHub Packages)
- `izgw-bom` — dependency version management (published to GitHub Packages)
