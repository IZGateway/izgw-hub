## Context

Hub uses Springdoc (`springdoc-openapi-starter-webmvc-ui:3.1.0`, brought in transitively via `izgw-core`) to
expose Swagger UI at `/swagger/ui.html` and the OpenAPI document at `/swagger/api-docs`. Springdoc builds the
swagger-ui static resource handler from `SwaggerUiConfigProperties.version`, read in this precedence order:

1. The `springdoc.swagger-ui.version` property in `application.yml` / Spring environment.
2. A fallback baked into `springdoc.config.properties` inside the springdoc starter jar.

`izgw-bom` overrides `org.webjars:swagger-ui` to a newer version than either of those (currently `5.32.14`;
`application.yml` is pinned to `5.32.13`), and a nightly dependency-bump workflow in the BOM keeps moving that
version forward. The webjar's static assets live under `META-INF/resources/webjars/swagger-ui/<actual-version>/`,
so when Springdoc serves `/swagger/swagger-ui/index.html` it asks for
`classpath:META-INF/resources/webjars/swagger-ui/5.32.13/index.html` — a path that does not exist in the
`5.32.14` jar — and the request 404s.

Reproduced directly against a local build (`gov.cdc.izgateway.security.AccessControlValve` grants the request,
then Spring's `ResourceHttpRequestHandler` returns `{"error":"No static resource index.html for request",
"path":"/swagger/swagger-ui/index.html"}`), confirming this is not just a theoretical drift risk but is
actively broken on `IGDD-2353_spring_upgrade` right now.

### Why this lives in `izgw-core`, not `izgw-hub`

`izgw-core` already declares `springdoc-openapi-starter-webmvc-ui` itself — it's what brings Springdoc to every
consumer in the first place (it's also why Hub has to exclude `webflux-ui` from it in `pom.xml`). Both known
consumers already component-scan `izgw-core`'s `gov.cdc.izgateway.configuration` package with zero extra wiring:
- `izgw-hub`'s `@SpringBootApplication` is rooted at `gov.cdc.izgateway` (covers it by default).
- `izgw-transform`'s `Application.java` has an explicit
  `@ComponentScan(basePackages={"gov.cdc.izgateway.xform", ..., "gov.cdc.izgateway.configuration", ...})` that
  already lists this exact package.

Placing the fix there means every current and future consumer inherits it the moment they bump their
`izgw-core` version, instead of each service needing to remember to hand-write its own copy — which is
precisely the failure mode that produced this ticket (IGDD-2976 fixed it in `izgw-transform` only; IGDD-3084 is
that same gap resurfacing in `izgw-hub`).

The full alternatives analysis (per-service duplication vs. `izgw-core` vs. removing `izgw-bom`'s independent
override) is recorded in
[`docs/superpowers/specs/2026-08-26-swagger-ui-version-sync-design.md`](../../../docs/superpowers/specs/2026-08-26-swagger-ui-version-sync-design.md).
This document covers Hub's consuming side only.

## Goals / Non-Goals

**Goals:**
- Swagger UI works at `/swagger/ui.html` regardless of which `org.webjars:swagger-ui` version the BOM ships,
  with no manual yaml edit required on each BOM bump.
- No new dependencies, no `izgw-bom` changes, no CI workflow changes.
- The existing admin-only access-control rule for `/swagger/**` continues to be enforced unchanged.

**Non-Goals:**
- Changing the OpenAPI spec output or any controller annotations.
- Changing the Swagger UI URL paths.
- Modifying `izgw-bom` or its nightly dependency-update workflow.
- Making the actual code change in `izgw-core` or `izgw-transform` — those are tracked by their own PRs (see
  the design doc's rollout sequence). This change covers only what `izgw-hub` does to consume the fix.

## Decisions

### Decision 1: Consume the fix from `izgw-core`; do not re-implement it locally.

**Choice:** Bump the `gov.cdc.izgw:izgw-core` dependency version in `pom.xml` once `izgw-core` publishes the
`SwaggerUiVersionConfig` fix, and remove Hub's local yaml pin. Add no new Java class in `izgw-hub`.

**Alternatives considered:**
- *Duplicate the `izgw-transform` fix directly into `izgw-hub`* — would close IGDD-3084 in isolation but
  repeats exactly the pattern that created it: a second service now has to remember to maintain its own copy,
  and a third future service would have to as well. Rejected — this is the status quo failure mode, not a fix
  for it.
- *Remove `izgw-bom`'s independent `org.webjars:swagger-ui` override* — would eliminate the drift at the source
  but throws away the reason the override exists (faster UI/CVE patches than Springdoc's own release cadence),
  and requires a policy decision from whoever owns `izgw-bom`, a different repo/team. Rejected as the first
  move; runtime detection makes it unnecessary regardless of what `izgw-bom` does.

**Rationale:** Hub's side of this fix should be as small as possible — a dependency version bump and a yaml
deletion — precisely because the actual logic is shared infrastructure now, not something Hub owns or needs to
test in depth itself.

### Decision 2: Add a regression test in Hub that proves the *inherited* wiring works, not just that the shared class exists.

**Choice:** A `@SpringBootTest` (following the existing pattern in `ApplicationTests.java`) that autowires the
real `SwaggerUiConfigProperties` bean and asserts its `version` matches
`WebJarVersionLocator.version("swagger-ui")`.

**Rationale:** `izgw-core`'s own tests (unit tests + an `ApplicationContextRunner`-based context test) already
prove the `BeanPostProcessor` mechanism works in isolation. The one thing they *can't* prove is that Hub's own
`@SpringBootApplication` actually picks the bean up via component scanning the way this design assumes. This
test is Hub-specific insurance against that one assumption silently breaking (e.g., if Hub's component scan
configuration ever narrows).

### Decision 3: Delete the yaml key outright; do not leave a placeholder comment about it.

**Choice:** Remove `springdoc.swagger-ui.version: 5.32.13` and its explanatory comment entirely from
`application.yml`, replacing it with a one-line pointer to `SwaggerUiVersionConfig` (in `izgw-core`) and the
Jira ticket — not a restatement of the old comment's now-false claim that this value "must match" the BOM.

**Rationale:** The prior comment already documented a broken assumption (that a human would keep the pin in
sync); leaving any version of that comment behind invites the same mistake again. A pointer to the mechanism
that actually keeps it in sync is more durable.

## Risks / Trade-offs

- **Risk: the `izgw-core` PR isn't merged/published yet when this change is worked.** → Mitigation: this
  change's tasks depend on a specific `izgw-core` SNAPSHOT version being available first (see `tasks.md`); if
  it isn't, the dependency bump step blocks until it is. No workaround needed — this is a straightforward
  sequencing dependency, not a design risk.
- **Risk: `izgw-transform` keeps its own duplicate copy for a while after this lands.** → **Confirmed NOT
  harmless** (corrected after verification): both classes are named `SwaggerUiVersionConfig`, and Spring's
  default `@Configuration` bean-naming derives from the simple class name, so once `izgw-transform` bumps
  to a fixed `izgw-core` while its local copy still exists, boot fails outright with
  `ConflictingBeanDefinitionException: Annotation-specified bean name 'swaggerUiVersionConfig' ... conflicts
  with existing, non-compatible bean definition`. Verified directly: `mvn test` in `izgw-transform` with
  both classes present errors on `XformApplicationTests`-style context loads. This means the local-copy
  removal in `izgw-transform` is **mandatory in the same PR** that bumps its `izgw-core` dependency, not
  deferrable cleanup — it just doesn't block `izgw-hub`'s own PR from landing first.
- **Trade-off: Hub now depends on `izgw-core` for something that used to be entirely self-contained (a yaml
  value).** Accepted — that's the entire point of the fix: a value that can't be kept in sync by hand
  shouldn't be hand-maintained per service.

## Migration Plan

1. `izgw-core` PR lands and publishes a new SNAPSHOT (tracked outside this change).
2. This change bumps Hub's `izgw-core` dependency to that SNAPSHOT, removes the yaml pin, adds the regression
   test. Closes IGDD-3084.
3. Standard CI (build, OWASP dependency-check, Newman/Postman smoke tests including `TC_92a`/`TC_92b`) is
   sufficient; no extra rollout steps.
4. Roll back, if needed, by reverting the commit — the `application.yml` line can be re-added in the revert if
   Swagger UI breakage reappears (though reverting only un-does Hub's consumption; the `izgw-core` fix itself
   would need its own revert if it were the actual problem).

## Open Questions

None blocking.
