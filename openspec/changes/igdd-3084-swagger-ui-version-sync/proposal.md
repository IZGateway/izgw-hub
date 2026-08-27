## Why

Springdoc serves the Swagger UI static assets from `classpath:META-INF/resources/webjars/swagger-ui/<version>/`,
where `<version>` comes from the `springdoc.swagger-ui.version` property. `izgw-hub` hand-pins that property to
a literal in `src/main/resources/application.yml` (currently `5.32.13`). `izgw-bom` independently manages the
`org.webjars:swagger-ui` artifact version — deliberately, so UI fixes/CVE patches land ahead of Springdoc's own
release cadence — and a nightly automated dependency-bump workflow keeps moving that version forward. Every time
the BOM-managed version passes the hand-typed literal, Springdoc asks the resource handler for a webjar
directory that no longer exists, and `/swagger/ui.html` / `/swagger/swagger-ui/index.html` returns
`404 — No static resource index.html`.

This has already recurred and been manually re-patched multiple times in `izgw-hub` (May–August 2026, well
before any Spring Boot migration work touched this file), and was fixed once before under
[IGDD-2976](https://izgateway.atlassian.net/browse/IGDD-2976) — but as a **per-service** fix duplicated into
`izgw-transform` only. That fix's own design doc explicitly anticipated this: *"the fix is xform-local and does
not benefit other izgw consumers of the BOM ... when there's a second consumer the right move is to lift this
into izgw-core, not pre-emptively."* [IGDD-3084](https://izgateway.atlassian.net/browse/IGDD-3084) is exactly
that second consumer arriving, confirmed still live on `IGDD-2353_spring_upgrade`
(`mvn dependency:tree` resolves `org.webjars:swagger-ui:5.32.14`; `application.yml` is pinned to `5.32.13`).

Full design rationale, alternatives considered, and the cross-repo rollout sequence are recorded in
`design.md` alongside this proposal.

## What Changes

- Consume a new `SwaggerUiVersionConfig` class from `izgw-core` (package `gov.cdc.izgateway.configuration`) that
  detects the actual `org.webjars:swagger-ui` version on the classpath at startup via
  `org.webjars.WebJarVersionLocator` and force-sets it on Springdoc's `SwaggerUiConfigProperties` bean. `izgw-hub`
  already component-scans this package by default (its `@SpringBootApplication` is rooted at `gov.cdc.izgateway`),
  so no wiring change is needed on Hub's side beyond bumping the `izgw-core` dependency version.
- Remove the hand-typed `springdoc.swagger-ui.version: 5.32.13` line (and its stale comment) from
  `src/main/resources/application.yml` — there is no longer a second number to keep in sync.
- Add a regression test proving Hub's own application context inherits the fix from `izgw-core`.
- No change to the Swagger UI URL paths (`/swagger/ui.html`, `/swagger/api-docs`,
  `/swagger/swagger-ui/index.html`) or to the existing admin-only access-control rule enforced by
  `AccessControlValve.isSwagger()` / `IAccessControlService.isUserInRole(name, "admin")`.

## Capabilities

### New Capabilities

- `api-documentation`: Behavior of the Swagger UI / OpenAPI endpoints that document Hub's REST/SOAP surface —
  the paths, the version-detection mechanism that keeps the UI working across `izgw-bom` dependency bumps, and
  the existing admin-only access-control behavior. (Mirrors the capability of the same name already specified
  in `izgw-transform` under IGDD-2976, since the mechanism is now the same shared `izgw-core` code.)

### Modified Capabilities

(None — no existing spec in this repo covers the Swagger UI today.)

## Impact

- **Code**: Bumps the `gov.cdc.izgw:izgw-core` dependency version in `pom.xml`. Deletes one line (plus its
  comment) from `src/main/resources/application.yml`. No changes to controllers, SOAP routing, repository
  backends, or security filters.
- **Dependencies**: No new dependency declarations. Uses `org.webjars.WebJarVersionLocator` (already transitive
  via `springdoc-openapi-starter-webmvc-ui`, which `izgw-core` itself depends on) and
  `org.springdoc.core.properties.SwaggerUiConfigProperties` (same).
- **SOAP/HL7v2 message routing and ADS submission paths are unaffected** — only the developer-facing
  `/swagger/**` endpoints are touched.
- **Access control**: The existing admin-only rule for `/swagger/**` (`AccessControlValve.isSwagger()`) is
  preserved unchanged.
- **Other repos**: `izgw-core` gains the shared fix (new file, no breaking change to any existing public type);
  `izgw-transform` gets a follow-up, non-blocking cleanup PR removing its now-redundant local duplicate
  (`SwaggerUiVersionConfig` + 2 test classes) once it bumps to the new `izgw-core` version. Neither is part of
  this change's task list — see the design doc's rollout sequence.
- **No database, CI workflow, or environment-variable changes.**
