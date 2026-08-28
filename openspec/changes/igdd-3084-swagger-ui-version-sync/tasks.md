# Tasks: igdd-3084-swagger-ui-version-sync

Jira: IGDD-3084

## 0. Prerequisite (tracked in `izgw-core`, not this repo — coordinate before starting section 1)

- [x] 0.1 `izgw-core`'s `SwaggerUiVersionConfig` (package-visible `SWAGGER_UI_WEBJAR_NAME`,
  `alignVersionFromWebjar(SwaggerUiConfigProperties)`, `alignVersion(SwaggerUiConfigProperties,
  Supplier<String>)`, static `@Bean BeanPostProcessor swaggerUiVersionAligner()`) is implemented and
  passing (7/7 tests) — see `izgw-core`'s own `igdd-3084-swagger-ui-version-sync/tasks.md` Stage 1.
- [x] 0.2 `izgw-core` PR opened and its SNAPSHOT published to GitHub Packages:
  `gov.cdc.izgw:izgw-core:3.6.0-IGDD-3084_swagger_ui_version_sync-SNAPSHOT`
  (build `3.6.0-IGDD-3084_swagger_ui_version_sync-20260828.152613-3`). **Correction:** an earlier version
  of this task claimed no `pom.xml` bump was needed because the coordinate happened to already match the
  migration branch's SNAPSHOT — that was only true transiently, before the branch-placement decision (see
  `izgw-core` tasks.md task 1.0) moved the fix to its own branch off `develop` with its own version. `pom.xml`
  here has since been updated to the real coordinate above.

## 1. Code changes

- [x] 1.1 `pom.xml`'s `gov.cdc.izgw:izgw-core` dependency `<version>` set to
  `3.6.0-IGDD-3084_swagger_ui_version_sync-SNAPSHOT`. Verified this actually resolves from GitHub Packages
  (not just local `.m2`): cleared the local cache for that exact coordinate, ran
  `mvn dependency:tree -Dincludes=gov.cdc.izgw:izgw-core -U`, and confirmed a fresh download from
  `github-bom` matching the exact published build number above.
- [x] 1.2 Removed the `version: 5.32.13` line and its two-line explanatory comment under
  `springdoc.swagger-ui` in `src/main/resources/application.yml`, replaced with a one-line pointer to
  `SwaggerUiVersionConfig` in `izgw-core` and this ticket.

## 2. Tests

- [x] 2.1 Added `SwaggerUiVersionIntegrationTests` under `src/test/java/gov/cdc/izgateway/configuration/`
  — a `@SpringBootTest` following the existing `ApplicationTests.java` pattern, autowiring
  `SwaggerUiConfigProperties` and asserting its `version` equals
  `new WebJarVersionLocator().version(SwaggerUiVersionConfig.SWAGGER_UI_WEBJAR_NAME)`.
- [x] 2.2 Ran this test against the pre-fix `izgw-core` (temporarily reverted via `git stash` in that
  checkout, rebuilt, reinstalled to local `.m2`) with the yaml pin still present: **confirmed it fails**
  — `AssertionFailedError: expected: <5.32.14> but was: <5.32.13>`. Clean reproduction of IGDD-3084 via a
  real assertion, not a compile error (the test temporarily used the literal `"swagger-ui"` instead of
  `SwaggerUiVersionConfig.SWAGGER_UI_WEBJAR_NAME` for this run only, since that class didn't exist in the
  reverted artifact).
- [x] 2.3 Restored `izgw-core`'s fix (`git stash pop`, reinstalled), applied task 1's changes, switched the
  test back to referencing `SwaggerUiVersionConfig.SWAGGER_UI_WEBJAR_NAME`, ran again: **passes**.
- [x] 2.4 Ran `mvn clean test` (full suite, with `COMMON_PASS`/`AMAZON_DYNAMODB_*`/etc. set to match
  `.vscode/hub.env`): **174 tests run, 0 failures, 0 errors, 6 pre-existing skips (unrelated), BUILD
  SUCCESS.**
- [x] 2.5 **Re-verification against the actually-shipped artifact** (2.2–2.4 above ran before the
  branch-placement move, against `izgw-core:3.5.1-IGDD-2353_spring_upgrade-SNAPSHOT` — a code review
  correctly flagged that this no longer matched what's committed in `pom.xml`). Cleared the local `.m2`
  cache for `izgw-core:3.6.0-IGDD-3084_swagger_ui_version_sync-SNAPSHOT`, forced a fresh fetch from
  GitHub Packages (`-U`), and reran `mvn clean test`: **174 tests run, 0 failures, 0 errors, BUILD
  SUCCESS**, including `SwaggerUiVersionIntegrationTests` (1/1 pass) and a startup log line confirming
  `Detected swagger-ui webjar version: 5.32.14` from the real published artifact
  (`izgw-core-3.6.0-IGDD-3084_swagger_ui_version_sync-20260828.152613-3`).

## 3. Validation

- [x] 3.1 Covered by 2.4 above (`mvn clean test`, not `mvn clean package` — equivalent for this purpose
  since no packaging-specific step touches this change).
- [x] 3.2 Restarted the local dev instance (`mvn spring-boot:run` with the same env/vmArgs as
  `.vscode/launch.json`) to pick up the rebuilt `izgw-core` jar and the `application.yml` change. Startup
  log confirms: `Detected swagger-ui webjar version: 5.32.14`. Ran the actual `TC_92a Get Documentation`
  and `TC_92b Get Api Document` Postman requests via newman with a real client certificate (using the
  `admin` DynamoDB seed from earlier in this session): both **200 OK**, `TC_92a` returns `text/html`
  (Swagger UI), `TC_92b` returns the full 64KB OpenAPI JSON document. These are the exact two tests that
  were failing at the start of this investigation. **Note:** this manual pass ran before the
  branch-placement move, against `izgw-core:3.5.1-IGDD-2353_spring_upgrade-SNAPSHOT`, not the
  `3.6.0-IGDD-3084_swagger_ui_version_sync-SNAPSHOT` now in `pom.xml`. Unlike 2.4/2.5, this manual check
  has not been re-run against the real published artifact — the automated `SwaggerUiVersionIntegrationTests`
  in 2.5 covers the same assertion, but a fresh Newman/browser pass against the real artifact would close
  this out fully.
- [x] 3.3 Confirmed by inspection — this change touches only `application.yml` (springdoc block) and adds
  one new test file; no edit to `AccessControlValve`, `AccessControlService`, or `Roles`.

## 4. Postman / CI

- [ ] 4.1 No collection changes expected — `TC_92a Get Documentation` and `TC_92b Get Api Document` in
  `testing/scripts/IZGW_2.0_Integration_Test.postman_collection.json` already assert `200`/`text/html`;
  this change is what makes them pass against a real deployment again. Confirm they pass in CI once this
  PR's build reaches the dev-deploy verify stage in `.github/workflows/maven.yml`.

## 5. Documentation / spec sync

- [ ] 5.1 Verify `specs/api-documentation/spec.md` in this change directory accurately describes the
  shipped behavior (paths, version-detection mechanism, admin-only access-control preservation) before
  archiving.
- [ ] 5.2 Run `openspec validate igdd-3084-swagger-ui-version-sync` and resolve any reported issues.

## 6. Follow-up (tracked separately — not part of this change, does not block it)

- [x] 6.1 `izgw-transform`: local `SwaggerUiVersionConfig` + `SwaggerUiVersionConfigTests` +
  `SwaggerUiVersionContextTests` (all under `gov.cdc.izgateway.xform.configuration`) removed locally
  (uncommitted — `izgw-core`'s dependency version already matched, same as `izgw-hub`, so no pom.xml bump
  was needed for local verification). **Correction to the original plan:** this is NOT harmless-if-delayed
  cleanup as first assumed — before deleting, confirmed the two duplicate classes (both named
  `SwaggerUiVersionConfig`, different packages) cause a hard `ConflictingBeanDefinitionException` at boot
  once both are on the classpath together, because Spring's default `@Configuration` bean-naming collides
  on the simple class name. Ran the full suite after deletion: 245 tests, 0 failures, `BUILD SUCCESS`;
  startup log confirms `Detected swagger-ui webjar version: 5.32.14` from the inherited `izgw-core` class.
  Still a separate PR in that repo (not part of this change), but must land in the same commit as
  `izgw-transform`'s own `izgw-core` version bump, not deferred after it.
