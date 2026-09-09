## ADDED Requirements

### Requirement: Swagger UI endpoint path

Hub SHALL expose Swagger UI for its REST/SOAP API surface at the path `/swagger/ui.html` and SHALL expose the
OpenAPI document at `/swagger/api-docs`. Hub SHALL NOT require any springdoc-related properties beyond
`springdoc.swagger-ui.path` and `springdoc.api-docs.path` in `src/main/resources/application.yml`.

#### Scenario: Swagger UI loads at the configured path

- **GIVEN** Hub is running on `https://<host>:<port>` with the default `application.yml`
- **AND** the requester holds the `admin` role per `AccessControlValve.isSwagger()` /
  `IAccessControlService.isUserInRole(name, "admin")`
- **WHEN** the requester issues `GET /swagger/ui.html`
- **THEN** the response is an HTTP 200 (or a redirect chain terminating at HTTP 200) and the body contains the
  Swagger UI HTML

#### Scenario: OpenAPI document is served

- **GIVEN** Hub is running with the default `application.yml`
- **AND** the requester holds the `admin` role
- **WHEN** the requester issues `GET /swagger/api-docs`
- **THEN** the response is HTTP 200 with a JSON body conforming to the OpenAPI 3 specification

#### Scenario: Configuration is minimal

- **GIVEN** Hub is running with the default `application.yml`
- **WHEN** any `springdoc.swagger-ui.version` property is present or absent in the active Spring environment
- **THEN** Swagger UI behavior MUST NOT depend on that property value (it MAY be absent entirely)

### Requirement: Swagger UI static resources resolve regardless of swagger-ui webjar version

Hub SHALL successfully serve the Swagger UI static resources (`index.html`, `swagger-ui-bundle.js`,
`swagger-initializer.js`, CSS, and fonts) at `/swagger/swagger-ui/**` for **whatever version of
`org.webjars:swagger-ui` is on the application classpath at runtime**. Hub MUST NOT require manual
configuration changes (yaml, pom, or otherwise) when the `org.webjars:swagger-ui` version changes via
`izgw-bom` updates.

#### Scenario: Swagger UI index loads after a webjar bump

- **GIVEN** `izgw-bom` has bumped `org.webjars:swagger-ui` to a new version (e.g., from `5.32.13` to a later
  patch release)
- **AND** Hub has been rebuilt against the updated bom with no other changes to `src/main/resources/application.yml`
- **AND** the requester holds the `admin` role
- **WHEN** the requester issues `GET /swagger/swagger-ui/index.html`
- **THEN** the response is HTTP 200 and the body is the Swagger UI `index.html` for the on-classpath webjar version

#### Scenario: Static asset paths resolve to the actual webjar directory

- **GIVEN** `org.webjars:swagger-ui` version `X.Y.Z` is the version Maven resolves onto the classpath
- **WHEN** Hub starts
- **THEN** Springdoc's swagger-ui static-resource handler MUST be configured to serve from
  `classpath:META-INF/resources/webjars/swagger-ui/X.Y.Z/` (the directory that actually exists in the
  resolved webjar)

### Requirement: Detected swagger-ui version is logged at startup

Hub SHALL emit exactly one log entry at `INFO` level during application startup that reports the detected
`org.webjars:swagger-ui` version.

#### Scenario: Successful detection logs the version

- **WHEN** Hub starts and `org.webjars:swagger-ui` is on the classpath
- **THEN** a single `INFO` log message is written by a logger under `gov.cdc.izgateway.*` containing the
  literal string `swagger-ui` and the detected version

#### Scenario: Detection failure logs a warning

- **WHEN** Hub starts and the detection lookup returns `null` or throws an exception
- **THEN** a single `WARN` log message is written by a logger under `gov.cdc.izgateway.*` describing the failure
- **AND** Hub MUST continue to start (the failure MUST NOT prevent the Spring context from initializing)

### Requirement: Backward compatibility with admin access control

The change MUST preserve the existing `/swagger/**` access-control rule enforced by
`gov.cdc.izgateway.security.AccessControlValve.isSwagger()` (admin-only access, denied requests reported as
404 per the existing "unknown path" deny-by-default behavior of `AccessControlValve.accessAllowed()`).

#### Scenario: Non-admin user receives the existing deny response

- **GIVEN** the requester is authenticated (via mTLS certificate or JWT) but does NOT hold the `admin` role
- **WHEN** the requester issues `GET /swagger/ui.html`, `GET /swagger/api-docs`, or `GET /swagger/swagger-ui/index.html`
- **THEN** the response is the same deny response `AccessControlValve` would have returned before this change
  for the same request (the version-detection change has not relaxed or altered this rule)

#### Scenario: Admin access continues to work

- **GIVEN** the requester is authenticated and holds the `admin` role
- **WHEN** the requester issues any `GET /swagger/**` request
- **THEN** the response is the same status that would have been returned before this change for the same request

### Requirement: Message routing and access-control model are unaffected

The change MUST NOT alter SOAP/HL7v2 message routing (`BaseGatewayController`, `MessageSender`), ADS
submission handling, or the DynamoDB-backed access-control model (`AccessControl`, `AccessGroup`,
`AllowedUser`, `DenyListRecord`).

#### Scenario: Existing message routing continues to work

- **GIVEN** a destination and sender configuration that worked under the previous version of Hub
- **WHEN** the same SOAP/HL7v2 message is replayed against the upgraded Hub
- **THEN** the routing outcome is identical to the pre-change behavior

#### Scenario: Access-control role resolution is unaffected

- **GIVEN** `SPRING_DATABASE` is set to `dynamodb` or `jpa`
- **WHEN** `IAccessControlService.isUserInRole` / `checkAccess` resolve roles for any principal
- **THEN** the result is identical to the pre-change behavior — this change touches only
  `SwaggerUiConfigProperties.version`, not any access-control data model or lookup logic
