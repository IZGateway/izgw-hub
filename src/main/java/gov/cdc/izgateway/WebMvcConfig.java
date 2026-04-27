package gov.cdc.izgateway;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC configuration for IZ Gateway.
 *
 * <p>Maps Swagger UI webjar resources under the {@code /swagger/} path prefix so that
 * all Swagger-related URLs are grouped under {@code /swagger/**} for access control.</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** Creates a new WebMvcConfig instance. */
    public WebMvcConfig() {}

    /**
     * Registers a resource handler that serves Swagger UI static assets under
     * {@code /swagger/swagger-ui/**}.
     *
     * <p>SpringDoc redirects {@code /swagger/ui.html} to
     * {@code /swagger/swagger-ui/index.html}. Without this mapping, Spring's default
     * resource handler cannot find the webjar resources at that path and throws a
     * {@code NoResourceFoundException}.</p>
     *
     * @param registry the {@link ResourceHandlerRegistry} to add handlers to
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/swagger/swagger-ui/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/swagger-ui/");
    }
}
