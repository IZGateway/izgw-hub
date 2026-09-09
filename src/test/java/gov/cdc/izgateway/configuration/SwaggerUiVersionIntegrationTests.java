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

// Keep this annotation identical to ApplicationTests so both share one cached context.
// Adding attributes here (e.g. properties) forks a second context, which cannot bind the
// fixed server.local-port 9081 that RANDOM_PORT does not randomize.
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

    @Test
    void resolvedVersionMapsToRealWebjarResources() {
        String version = swaggerUiConfigProperties.getVersion();
        String indexHtml = "META-INF/resources/webjars/swagger-ui/" + version + "/index.html";
        assertNotNull(getClass().getClassLoader().getResource(indexHtml),
                "swagger-ui " + version + " has no resources on the classpath; springdoc would serve 404 for " + indexHtml);
    }
}
