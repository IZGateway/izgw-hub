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
