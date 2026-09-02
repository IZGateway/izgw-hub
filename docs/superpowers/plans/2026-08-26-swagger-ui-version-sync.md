# Swagger UI Webjar Version Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop `springdoc.swagger-ui.version` from drifting out of sync with the actual `org.webjars:swagger-ui` version on the classpath, for every current and future consumer of `izgw-core`, by detecting the real version at runtime instead of hand-pinning a literal in each service's `application.yml`.

**Architecture:** A single `BeanPostProcessor`-based `SwaggerUiVersionConfig` class lives in `izgw-core` (package `gov.cdc.izgateway.configuration`), where both `izgw-hub` and `izgw-transform` already component-scan it with zero wiring changes. It intercepts Springdoc's `SwaggerUiConfigProperties` bean during context startup and overwrites its `version` field with whatever `org.webjars:swagger-ui` version `org.webjars.WebJarVersionLocator` finds on the classpath.

**Tech Stack:** Java 21, Spring Boot 4 / Spring Framework 7, Springdoc OpenAPI 3.1.0, `webjars-locator-lite`, JUnit 5, Spring's `ApplicationContextRunner` (for library-level context testing), Maven.

**Spec:** `docs/superpowers/specs/2026-08-26-swagger-ui-version-sync-design.md`

## Global Constraints

- New production code lives only in `izgw-core`, package `gov.cdc.izgateway.configuration` — this exact package is what makes it auto-apply to both known consumers without any `@Import`/`@ComponentScan` changes on their end.
- No `pom.xml` dependency additions are needed in any of the three repos — `springdoc-openapi-starter-webmvc-ui` (already a dependency everywhere in this chain) already brings `SwaggerUiConfigProperties`, `WebJarVersionLocator`, and the `swagger-ui` webjar itself.
- `izgw-core` is a library, not a deployable Spring Boot app — its context test must use `ApplicationContextRunner`, not `@SpringBootTest` (which requires a `@SpringBootApplication`/`@SpringBootConfiguration` that a library module doesn't have).
- Rollout order is `izgw-core` → `izgw-hub` → `izgw-transform`. The `izgw-transform` cleanup is a separate PR and does not block `izgw-hub`'s fix, but is **not deferrable** once `izgw-transform` bumps its own `izgw-core` dependency: verified that leaving both `SwaggerUiVersionConfig` classes on the classpath together (same simple class name, different packages) causes a hard `ConflictingBeanDefinitionException` at boot, not a harmless redundant no-op as originally assumed.

---

## Part 1 — `izgw-core`: add the shared fix

### Task 1: Implement `SwaggerUiVersionConfig` with full test coverage

**Files:**
- Create: `src/main/java/gov/cdc/izgateway/configuration/SwaggerUiVersionConfig.java`
- Create: `src/test/java/gov/cdc/izgateway/configuration/SwaggerUiVersionConfigTests.java`
- Create: `src/test/java/gov/cdc/izgateway/configuration/SwaggerUiVersionContextTests.java`

**Interfaces:**
- Produces: `gov.cdc.izgateway.configuration.SwaggerUiVersionConfig` with:
  - `static final String SWAGGER_UI_WEBJAR_NAME = "swagger-ui"` (package-visible constant)
  - `static void alignVersionFromWebjar(SwaggerUiConfigProperties props)` (package-visible)
  - `static void alignVersion(SwaggerUiConfigProperties props, Supplier<String> detector)` (package-visible)
  - `@Bean public static BeanPostProcessor swaggerUiVersionAligner()`

- [ ] **Step 1: Clone `izgw-core` and create a branch**

```bash
cd C:/Users/youngto/workspaces/izg
git clone https://github.com/IZGateway/izgw-core.git
cd izgw-core
git checkout -b IGDD-3084-swagger-ui-version-sync
```

- [ ] **Step 2: Write the failing unit test file**

Create `src/test/java/gov/cdc/izgateway/configuration/SwaggerUiVersionConfigTests.java`:

```java
package gov.cdc.izgateway.configuration;

import org.junit.jupiter.api.Test;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.webjars.WebJarVersionLocator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwaggerUiVersionConfigTests {

    private static final String LEGACY_HARDCODED_PIN = "5.32.13";
    private static final String SPRINGDOC_BUNDLED_DEFAULT = "5.32.2";

    @Test
    void alignerSetsVersionToActualWebjarVersion() {
        String expected = new WebJarVersionLocator().version(SwaggerUiVersionConfig.SWAGGER_UI_WEBJAR_NAME);
        assertNotNull(expected, "swagger-ui webjar must be on the test classpath");

        SwaggerUiConfigProperties props = new SwaggerUiConfigProperties();
        SwaggerUiVersionConfig.alignVersionFromWebjar(props);

        assertEquals(expected, props.getVersion());
        assertTrue(props.getVersion().matches("\\d+\\.\\d+\\.\\d+(?:[-+].+)?"),
                "detected version must look like a semver (X.Y.Z with optional qualifier); got " + props.getVersion());
    }

    @Test
    void resolvedVersionIsNotLegacyOrSpringdocFallback() {
        String resolvedWebjarVersion = new WebJarVersionLocator().version(SwaggerUiVersionConfig.SWAGGER_UI_WEBJAR_NAME);
        assertNotNull(resolvedWebjarVersion);

        SwaggerUiConfigProperties props = new SwaggerUiConfigProperties();
        SwaggerUiVersionConfig.alignVersionFromWebjar(props);

        assertFalse(LEGACY_HARDCODED_PIN.equals(props.getVersion())
                        && !LEGACY_HARDCODED_PIN.equals(resolvedWebjarVersion),
                "version field should not be stuck on the legacy " + LEGACY_HARDCODED_PIN + " pin");
        assertFalse(SPRINGDOC_BUNDLED_DEFAULT.equals(props.getVersion())
                        && !SPRINGDOC_BUNDLED_DEFAULT.equals(resolvedWebjarVersion),
                "version field should not be stuck on Springdoc's bundled default "
                        + SPRINGDOC_BUNDLED_DEFAULT + " when the webjar is a different version");
    }

    @Test
    void alignerOverridesPreExistingConfiguredVersion() {
        SwaggerUiConfigProperties props = new SwaggerUiConfigProperties();
        props.setVersion("bogus-pre-existing-pin");

        SwaggerUiVersionConfig.alignVersionFromWebjar(props);

        String expected = new WebJarVersionLocator().version(SwaggerUiVersionConfig.SWAGGER_UI_WEBJAR_NAME);
        assertEquals(expected, props.getVersion(),
                "an existing springdoc.swagger-ui.version value must be overridden by the on-classpath webjar version");
    }

    @Test
    void nullDetectionLeavesVersionUntouched() {
        SwaggerUiConfigProperties props = new SwaggerUiConfigProperties();
        props.setVersion("preserved-on-failure");

        SwaggerUiVersionConfig.alignVersion(props, () -> null);

        assertEquals("preserved-on-failure", props.getVersion());
    }

    @Test
    void blankDetectionLeavesVersionUntouched() {
        SwaggerUiConfigProperties props = new SwaggerUiConfigProperties();
        props.setVersion("preserved-on-failure");

        SwaggerUiVersionConfig.alignVersion(props, () -> "   ");

        assertEquals("preserved-on-failure", props.getVersion());
    }

    @Test
    void exceptionDuringDetectionLeavesVersionUntouchedAndDoesNotPropagate() {
        SwaggerUiConfigProperties props = new SwaggerUiConfigProperties();
        props.setVersion("preserved-on-failure");

        SwaggerUiVersionConfig.alignVersion(props, () -> {
            throw new IllegalStateException("simulated webjar lookup failure");
        });

        assertEquals("preserved-on-failure", props.getVersion());
    }
}
```

- [ ] **Step 3: Write the failing context test file**

Create `src/test/java/gov/cdc/izgateway/configuration/SwaggerUiVersionContextTests.java`:

```java
package gov.cdc.izgateway.configuration;

import org.junit.jupiter.api.Test;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.webjars.WebJarVersionLocator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SwaggerUiVersionContextTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SwaggerUiVersionConfig.class, SwaggerUiConfigPropertiesTestConfig.class);

    @Test
    void postProcessorAlignsSwaggerUiVersionInRealContext() {
        String expected = new WebJarVersionLocator().version(SwaggerUiVersionConfig.SWAGGER_UI_WEBJAR_NAME);
        assertNotNull(expected, "swagger-ui webjar must be on the test classpath");

        contextRunner.run(context -> {
            SwaggerUiConfigProperties props = context.getBean(SwaggerUiConfigProperties.class);
            assertEquals(expected, props.getVersion(),
                    "BeanPostProcessor must override SwaggerUiConfigProperties.version during context init");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class SwaggerUiConfigPropertiesTestConfig {
        @Bean
        SwaggerUiConfigProperties swaggerUiConfigProperties() {
            SwaggerUiConfigProperties props = new SwaggerUiConfigProperties();
            props.setVersion("0.0.0-test-pin");
            return props;
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail to compile**

Run: `mvn test -Dtest=SwaggerUiVersionConfigTests,SwaggerUiVersionContextTests -pl .`
Expected: FAIL — `SwaggerUiVersionConfig` does not exist yet, so both test files fail to compile.

- [ ] **Step 5: Implement `SwaggerUiVersionConfig`**

Create `src/main/java/gov/cdc/izgateway/configuration/SwaggerUiVersionConfig.java`:

```java
package gov.cdc.izgateway.configuration;

import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.webjars.WebJarVersionLocator;

/**
 * Aligns Springdoc's swagger-ui resource-handler version with the actual
 * {@code org.webjars:swagger-ui} version present on the classpath.
 *
 * <p>Springdoc reads its version from {@code springdoc.swagger-ui.version}
 * and falls back to a value baked into {@code springdoc.config.properties}
 * inside the springdoc starter jar. {@code izgw-bom} independently overrides
 * the {@code org.webjars:swagger-ui} version, so the two drift and the UI
 * 404s on {@code /swagger/swagger-ui/index.html} after a webjar bump.
 *
 * <p>Implemented as a {@link BeanPostProcessor} returned from a static
 * {@code @Bean} factory so it runs even with
 * {@code spring.main.lazy-initialization=true} (BPPs are always eagerly
 * registered) and so the version is mutated during the
 * {@link SwaggerUiConfigProperties} bean's own initialization — before any
 * other bean (e.g. Springdoc's resource-handler configurer) reads it.
 *
 * <p>Lives in {@code izgw-core} (rather than being duplicated per service,
 * as it originally was in {@code izgw-transform} under IGDD-2976) so every
 * consumer that component-scans this package inherits the fix automatically.
 * See IGDD-3084 and the {@code swagger-ui-version-sync} design doc.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public final class SwaggerUiVersionConfig {

    static final String SWAGGER_UI_WEBJAR_NAME = "swagger-ui";

    private SwaggerUiVersionConfig() {
        // Utility-style @Configuration: only a static @Bean factory and static helpers.
    }

    @Bean
    public static BeanPostProcessor swaggerUiVersionAligner() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof SwaggerUiConfigProperties props) {
                    alignVersionFromWebjar(props);
                }
                return bean;
            }
        };
    }

    static void alignVersionFromWebjar(SwaggerUiConfigProperties props) {
        alignVersion(props, () -> new WebJarVersionLocator().version(SWAGGER_UI_WEBJAR_NAME));
    }

    static void alignVersion(SwaggerUiConfigProperties props, Supplier<String> detector) {
        try {
            String detected = detector.get();
            if (detected == null || detected.isBlank()) {
                log.warn("Could not detect {} webjar version; leaving existing Springdoc swagger-ui version in place", SWAGGER_UI_WEBJAR_NAME);
                return;
            }
            props.setVersion(detected);
            log.info("Detected {} webjar version: {}", SWAGGER_UI_WEBJAR_NAME, detected);
        } catch (RuntimeException e) {
            log.warn("Failed to detect {} webjar version; leaving existing Springdoc swagger-ui version in place", SWAGGER_UI_WEBJAR_NAME, e);
        }
    }
}
```

- [ ] **Step 6: Run the tests again to verify they pass**

Run: `mvn test -Dtest=SwaggerUiVersionConfigTests,SwaggerUiVersionContextTests -pl .`
Expected: PASS — all 6 unit tests and the 1 context test pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/gov/cdc/izgateway/configuration/SwaggerUiVersionConfig.java \
        src/test/java/gov/cdc/izgateway/configuration/SwaggerUiVersionConfigTests.java \
        src/test/java/gov/cdc/izgateway/configuration/SwaggerUiVersionContextTests.java
git commit -m "IGDD-3084: auto-detect swagger-ui webjar version instead of hand-pinning it"
```

### Task 2: Open the `izgw-core` PR and record the published SNAPSHOT version

**Files:** none (process task)

- [ ] **Step 1: Push the branch and open a PR**

```bash
git push -u origin IGDD-3084-swagger-ui-version-sync
gh pr create --repo IZGateway/izgw-core \
  --title "IGDD-3084: auto-detect swagger-ui webjar version instead of hand-pinning it" \
  --body "Moves the IGDD-2976 fix (previously duplicated in izgw-transform) into izgw-core so every consumer inherits it automatically. See docs/superpowers/specs/2026-08-26-swagger-ui-version-sync-design.md in izgw-hub for the full design."
```

- [ ] **Step 2: Record the resulting SNAPSHOT version**

Once CI builds the branch, check the published SNAPSHOT version (mirrors the existing pattern already
visible in this machine's `~/.m2/repository/gov/cdc/izgw/izgw-core/` — e.g.
`3.5.1-IGDD-2353_spring_upgrade-SNAPSHOT` for the Spring upgrade branch). Confirm it either by checking
the GitHub Actions run log for the Maven `deploy` step, or by running:

```bash
mvn -q help:evaluate -Dexpression=project.version -DforceStdout
```

Write this exact version string down — it is the `<version>` value used in Task 3, Step 1.

---

## Part 2 — `izgw-hub`: consume the fix and close IGDD-3084

### Task 3: Add a regression test, then consume the fix and remove the stale pin

**Files:**
- Create: `src/test/java/gov/cdc/izgateway/configuration/SwaggerUiVersionIntegrationTests.java`
- Modify: `pom.xml` (the `gov.cdc.izgw:izgw-core` dependency `<version>`)
- Modify: `src/main/resources/application.yml:44-50`

**Interfaces:**
- Consumes: `gov.cdc.izgateway.configuration.SwaggerUiVersionConfig.SWAGGER_UI_WEBJAR_NAME` (produced in Task 1; package-visible, reachable because this test lives in the same package name)

- [ ] **Step 1: Write the failing integration test**

Create `src/test/java/gov/cdc/izgateway/configuration/SwaggerUiVersionIntegrationTests.java`:

```java
package gov.cdc.izgateway.configuration;

import org.junit.jupiter.api.Test;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.ComponentScan;
import org.webjars.WebJarVersionLocator;

import gov.cdc.izgateway.Application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS
)
@ComponentScan("gov.cdc.izgateway")
class SwaggerUiVersionIntegrationTests {

    @Autowired
    private SwaggerUiConfigProperties swaggerUiConfigProperties;

    static {
        Application.setAbortOnNoIIS(false);
        Application.skipMigrations(true);
    }

    @Test
    void swaggerUiVersionMatchesActualWebjarOnClasspath() {
        String expected = new WebJarVersionLocator().version(SwaggerUiVersionConfig.SWAGGER_UI_WEBJAR_NAME);
        assertNotNull(expected, "swagger-ui webjar must be on the test classpath");
        assertEquals(expected, swaggerUiConfigProperties.getVersion(),
                "Hub's application context must inherit izgw-core's version-aligning BeanPostProcessor");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails against the current (unfixed) code**

Run: `mvn test -Dtest=SwaggerUiVersionIntegrationTests`
Expected: FAIL — with the `izgw-core` dependency still pinned to
`3.5.1-IGDD-2353_spring_upgrade-SNAPSHOT` (no `SwaggerUiVersionConfig` bean exists yet) and
`application.yml` still hand-pinning `springdoc.swagger-ui.version: 5.32.13`, the autowired
`swaggerUiConfigProperties.getVersion()` returns `5.32.13`, which does not equal the actual
resolved webjar version (`5.32.14` as of this writing). This failure is the reproduction of IGDD-3084.

- [ ] **Step 3: Bump the `izgw-core` dependency version**

In `pom.xml`, find:

```xml
			<version>3.5.1-IGDD-2353_spring_upgrade-SNAPSHOT</version>
```

(the `izgw-core` dependency, immediately preceding the `webflux-ui` exclusion comment). Replace the
version string with the exact SNAPSHOT version recorded in Task 2, Step 2.

- [ ] **Step 4: Remove the stale version pin from `application.yml`**

In `src/main/resources/application.yml`, change:

```yaml
springdoc:
    swagger-ui:
        path: /swagger/ui.html
        # Must match org.webjars:swagger-ui version pinned in izgw-bom (currently 5.32.13).
        # springdoc's bundled default differs, so it serves resources from the wrong version
        # path and index.html 404s unless this is set explicitly.
        version: 5.32.13
    api-docs:
        path: /swagger/api-docs
```

to:

```yaml
springdoc:
    swagger-ui:
        path: /swagger/ui.html
        # Version is auto-detected at startup from the actual org.webjars:swagger-ui webjar on the
        # classpath (see SwaggerUiVersionConfig in izgw-core) — do not hand-pin this. IGDD-3084.
    api-docs:
        path: /swagger/api-docs
```

- [ ] **Step 5: Run the test again to verify it passes**

Run: `mvn test -Dtest=SwaggerUiVersionIntegrationTests`
Expected: PASS.

- [ ] **Step 6: Run the full unit test suite to confirm nothing else broke**

Run: `mvn clean package`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/resources/application.yml \
        src/test/java/gov/cdc/izgateway/configuration/SwaggerUiVersionIntegrationTests.java
git commit -m "IGDD-3084: consume izgw-core's auto-detected swagger-ui version, drop the stale yaml pin"
```

---

## Part 3 — `izgw-transform`: remove the now-redundant duplicate (separate PR, non-blocking)

### Task 4: Delete the local copy and consume the shared fix

**Files:**
- Delete: `src/main/java/gov/cdc/izgateway/xform/configuration/SwaggerUiVersionConfig.java`
- Delete: `src/test/java/gov/cdc/izgateway/xform/configuration/SwaggerUiVersionConfigTests.java`
- Delete: `src/test/java/gov/cdc/izgateway/xform/configuration/SwaggerUiVersionContextTests.java`
- Modify: `pom.xml` (the `gov.cdc.izgw:izgw-core` dependency `<version>`)

- [ ] **Step 1: Clone `izgw-transform` and create a branch**

```bash
cd C:/Users/youngto/workspaces/izg
git clone https://github.com/IZGateway/izgw-transform.git
cd izgw-transform
git checkout -b IGDD-3084-remove-duplicate-swagger-fix
```

- [ ] **Step 2: Bump the `izgw-core` dependency version**

In `pom.xml`, update the `gov.cdc.izgw:izgw-core` dependency `<version>` to the exact SNAPSHOT version
recorded in Part 1, Task 2, Step 2 (the same version used in `izgw-hub`'s `pom.xml`).

- [ ] **Step 3: Run the existing local tests to confirm the shared fix produces the same result**

Run: `mvn test -Dtest=SwaggerUiVersionConfigTests,SwaggerUiVersionContextTests`
Expected: PASS — both the local duplicate class and the newly-inherited `izgw-core` class are active at
this point and agree on the detected version, so these tests still pass unchanged.

- [ ] **Step 4: Delete the redundant local files**

```bash
git rm src/main/java/gov/cdc/izgateway/xform/configuration/SwaggerUiVersionConfig.java
git rm src/test/java/gov/cdc/izgateway/xform/configuration/SwaggerUiVersionConfigTests.java
git rm src/test/java/gov/cdc/izgateway/xform/configuration/SwaggerUiVersionContextTests.java
```

- [ ] **Step 5: Run the full test suite to confirm the inherited fix alone is sufficient**

Run: `mvn clean package`
Expected: BUILD SUCCESS — with the local class gone, `izgw-core`'s `SwaggerUiVersionConfig` (picked up
via the existing `@ComponentScan(basePackages={..., "gov.cdc.izgateway.configuration", ...})` in
`Application.java`) is the only thing aligning the version, and the build still passes.

- [ ] **Step 6: Commit and open the PR**

```bash
git add pom.xml
git commit -m "IGDD-3084: remove local swagger-ui version fix now that izgw-core provides it"
git push -u origin IGDD-3084-remove-duplicate-swagger-fix
gh pr create --repo IZGateway/izgw-transform \
  --title "IGDD-3084: remove local swagger-ui version fix now that izgw-core provides it" \
  --body "izgw-core now carries the SwaggerUiVersionConfig fix (originally added here under IGDD-2976) so every consumer inherits it automatically. This removes the now-redundant local copy. See docs/superpowers/specs/2026-08-26-swagger-ui-version-sync-design.md in izgw-hub for the full design."
```
