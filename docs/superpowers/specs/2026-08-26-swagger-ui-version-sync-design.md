# Swagger UI Webjar Version Sync — Design

**Ticket:** IGDD-3084 ("IZG Hub - swagger ui giving 404"), mirrors IGDD-2976 (already fixed in `izgw-transform`)
**Status:** Approved
**Repos affected:** `izgw-core`, `izgw-hub`, `izgw-transform`

## Problem

Springdoc serves the Swagger UI's static assets from
`classpath:META-INF/resources/webjars/swagger-ui/<version>/`, where `<version>` comes from the
`springdoc.swagger-ui.version` Spring property. Every consumer of `izgw-core` currently hand-pins that
property to a literal in its own `application.yml`.

`izgw-bom` independently manages the `org.webjars:swagger-ui` artifact version (to get UI fixes/CVE
patches ahead of springdoc's own release cadence — this is deliberate, not an oversight), and a nightly
automated dependency-bump workflow keeps moving that version forward. Every time the BOM-managed version
moves past a service's hand-typed literal, Springdoc asks the resource handler for a webjar directory
that no longer exists in the jar, and `/swagger/ui.html` / `/swagger/swagger-ui/index.html` returns 404.

This has already happened and been manually re-patched multiple times in `izgw-hub` (see git history:
May–August 2026, well before any Spring Boot migration work), and was fixed once already in
`izgw-transform` under IGDD-2976 by hand-writing a per-service `SwaggerUiVersionConfig` class. IGDD-3084
is the same bug recurring in `izgw-hub`, confirmed still live on the `IGDD-2353_spring_upgrade` branch
(`mvn dependency:tree` resolves `org.webjars:swagger-ui:5.32.14`; `application.yml` is pinned to
`5.32.13`).

## Decision

Move the fix from being a per-service, hand-copied class into `izgw-core`, the shared library both
`izgw-hub` and `izgw-transform` already depend on for Springdoc itself. This eliminates the "which
services remembered to copy the fix" failure mode for good, for every current and future consumer,
without requiring any change to how `izgw-bom` manages the webjar version.

Rejected alternatives:
- **Keep duplicating the fix per service** — this is the status quo, and it's exactly the pattern that
  has already failed twice (IGDD-2976, now IGDD-3084).
- **Stop `izgw-bom` from independently pinning `swagger-ui`** — throws away the reason the override
  exists (faster patches than springdoc's own release cadence), requires a policy decision from a
  different repo/team, and is moot anyway once the version is detected at runtime instead of hardcoded.

## Architecture

A single `SwaggerUiVersionConfig` class, placed in `izgw-core` under package
`gov.cdc.izgateway.configuration`. It registers a `BeanPostProcessor` that intercepts Springdoc's
`SwaggerUiConfigProperties` bean during context initialization and overwrites its `version` field with
whatever `org.webjars:swagger-ui` version is actually present on the classpath at startup (detected via
`org.webjars.WebJarVersionLocator`). There is no longer a hand-typed literal to keep in sync — the
value is read from the one place that can't drift from itself.

This package is already scanned by every known consumer without any wiring changes:
- `izgw-hub`'s `@SpringBootApplication` is rooted at `gov.cdc.izgateway`, which covers
  `gov.cdc.izgateway.configuration` by default.
- `izgw-transform`'s `Application.java` has an explicit
  `@ComponentScan(basePackages={"gov.cdc.izgateway.xform", ..., "gov.cdc.izgateway.configuration", ...})`
  that already lists this exact package.

No `pom.xml` changes are needed in any repo: `izgw-core` already depends on
`springdoc-openapi-starter-webmvc-ui` (which is what brings `SwaggerUiConfigProperties` and the
`webjars-locator-lite`-backed `WebJarVersionLocator` class to every consumer in the first place).

## Consumer changes

- **`izgw-hub`**: remove the hand-typed `springdoc.swagger-ui.version: 5.32.13` line (and its stale
  comment) from `application.yml`; bump the `izgw-core` dependency version once the fix is published.
- **`izgw-transform`**: delete its now-redundant local copy (`SwaggerUiVersionConfig` +
  `SwaggerUiVersionConfigTests` + `SwaggerUiVersionContextTests`, all under
  `gov.cdc.izgateway.xform.configuration`) and bump its `izgw-core` dependency version. Its
  `application.yml` already has no version pin (removed under IGDD-2976), so nothing changes there.

## Testing

- **Unit tests** (`izgw-core`): call `SwaggerUiVersionConfig.alignVersionFromWebjar` /
  `alignVersion` directly against a bare `SwaggerUiConfigProperties` instance — no Spring context needed.
  Covers: version gets set to the real webjar version; overrides a pre-existing bogus pin; a `null`,
  blank, or exception-throwing detector leaves the existing value untouched and does not propagate.
- **Context test** (`izgw-core`): uses Spring's `ApplicationContextRunner` (not `@SpringBootTest` — a
  library module has no `@SpringBootApplication` of its own to bootstrap) to prove the
  `BeanPostProcessor` actually fires against a real `SwaggerUiConfigProperties` bean inside a live
  `ApplicationContext`, not just via direct static calls.
- **Integration test** (`izgw-hub`): a `@SpringBootTest` (following the existing pattern in
  `ApplicationTests.java`) autowiring the real `SwaggerUiConfigProperties` bean and asserting its
  `version` matches the actual resolved webjar version — proves the component-scan inheritance from
  `izgw-core` actually wires up inside Hub's own application context, which is the one assumption specific
  to Hub that `izgw-core`'s own tests can't cover.

## Rollout sequence

1. Land the fix in `izgw-core` (new class + tests), publish a SNAPSHOT.
2. Bump `izgw-hub`'s `izgw-core` dependency to that SNAPSHOT, remove the yaml pin, add the integration
   test. This closes IGDD-3084.
3. Separately, bump `izgw-transform`'s `izgw-core` dependency and delete its now-redundant local copy in
   the same commit. Not blocking step 2, but **not deferrable once `izgw-transform` does bump its `izgw-core`
   version** — verified directly that leaving the duplicate in place causes a hard
   `ConflictingBeanDefinitionException` at boot (both classes are named `SwaggerUiVersionConfig`, and Spring's
   default `@Configuration` bean-naming collides on the simple class name), not a harmless redundant no-op
   as originally assumed here.
