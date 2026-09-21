package gov.cdc.izgateway;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverters;

import gov.cdc.izgateway.configuration.AppProperties;
import gov.cdc.izgateway.logging.event.Health;
import gov.cdc.izgateway.soap.net.SoapMessageConverter;
import gov.cdc.izgateway.soap.net.SoapMessageWriter;

/**
 * Guards {@code Application}'s message-converter registration (IGDD-2353).
 * <p>
 * Deliberately context-free so it runs locally: {@code ApplicationTests} is a
 * {@code @SpringBootTest} whose {@code DynamoDbConfig} needs a reachable table even under
 * {@code SPRING_DATABASE=jpa}, and aborts the JVM without one, so anything added there is
 * only ever executed by CI.
 * </p>
 */
class MessageConverterRegistrationTests {

	private boolean originalFixNewLines;

	@BeforeAll
	static void bootstrapAppProperties() {
		if (AppProperties.getInstance() == null) {
			new AppProperties();
		}
	}

	@BeforeEach
	void captureStaticState() {
		// The hook writes a static on SoapMessageWriter, and a hand-built Application has
		// fixNewlines=false rather than the @Value default of true. Restore it afterwards so
		// this test cannot perturb any other test's SOAP output.
		originalFixNewLines = SoapMessageWriter.getFixNewLines();
	}

	@AfterEach
	void restoreStaticState() {
		SoapMessageWriter.setFixNewLines(originalFixNewLines);
	}

	/**
	 * The ServerBuilder hook must only ever add to the defaults. Framework 7's
	 * {@code WebMvcConfigurationSupport.createMessageConverters()} calls
	 * {@code registerDefaults()} before handing the builder over; that ordering is
	 * replicated here so the assertion is about the hook's behaviour, not the framework's.
	 */
	@Test
	void configureMessageConvertersAddsSoapConverterWithoutDroppingDefaults() {
		HttpMessageConverters.ServerBuilder builder = HttpMessageConverters.forServer().registerDefaults();

		new Application().configureMessageConverters(builder);

		List<HttpMessageConverter<?>> converters = new ArrayList<>();
		builder.build().forEach(converters::add);

		assertTrue(converters.stream().anyMatch(SoapMessageConverter.class::isInstance),
				"SoapMessageConverter was not added: " + describe(converters));
		// The old List-based overload dropped *every* default once the list was populated,
		// so assert both the JSON and the String defaults survive alongside our converter.
		assertTrue(canWrite(converters, Health.class, MediaType.APPLICATION_JSON),
				"No registered converter writes JSON: " + describe(converters));
		assertTrue(canWrite(converters, String.class, MediaType.TEXT_PLAIN),
				"No registered converter writes text/plain: " + describe(converters));
	}

	/**
	 * The deprecated List-based hooks are the actual regression mechanism:
	 * {@code WebMvcConfigurationSupport} only adds the defaults when the list is left empty,
	 * so overriding either one and populating it silently drops every default app-wide.
	 * Reintroducing an override would not be caught by the builder test above, which calls
	 * the ServerBuilder overload directly.
	 */
	@Test
	void applicationDoesNotOverrideTheListBasedConverterHooks() {
		assertThrows(NoSuchMethodException.class,
				() -> Application.class.getDeclaredMethod("configureMessageConverters", List.class),
				"Application must not override configureMessageConverters(List): populating that list "
						+ "suppresses the default converters app-wide. Use the ServerBuilder overload.");
		assertThrows(NoSuchMethodException.class,
				() -> Application.class.getDeclaredMethod("extendMessageConverters", List.class),
				"Application must not override extendMessageConverters(List): it is deprecated for "
						+ "removal in Framework 7. Use the ServerBuilder overload.");
	}

	private boolean canWrite(List<HttpMessageConverter<?>> converters, Class<?> type, MediaType mediaType) {
		return converters.stream().anyMatch(c -> c.canWrite(type, mediaType));
	}

	private String describe(List<HttpMessageConverter<?>> converters) {
		return converters.stream().map(c -> c.getClass().getSimpleName()).toList().toString();
	}
}
