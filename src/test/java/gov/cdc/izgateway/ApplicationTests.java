package gov.cdc.izgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opentest4j.AssertionFailedError;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import gov.cdc.izgateway.common.HealthService;
import gov.cdc.izgateway.logging.MemoryAppender;
import gov.cdc.izgateway.logging.event.Health;
import gov.cdc.izgateway.logging.event.LogEvent;
import gov.cdc.izgateway.soap.net.SoapMessageConverter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
	useMainMethod = SpringBootTest.UseMainMethod.ALWAYS
)
@ComponentScan("gov.cdc.izgateway")
class ApplicationTests {
	static JsonFactory jf = new JsonFactory(); 
	@Autowired(required = true)
	AppController appController;
	@Autowired(required = true)
	LogController logController;
	@Autowired(required = true)
	RequestMappingHandlerAdapter handlerAdapter;

	static {
		Application.setAbortOnNoIIS(false);
		Application.skipMigrations(true);
	}
	

    @BeforeAll
    void setupParameters() {
    	// We want a larger memory appender for these tests, because some tests log more info.
        MemoryAppender.getInstance("memory").setSize(100);
    }

	@Test
	void applicationIsHealthy() {
		assertTrue(HealthService.getHealth().isHealthy());
	}
	
	@Test
	void buildNoIsValid() {
		String build = Application.getBuild();
		assertNotNull(build);
		assertFalse(build.contains("%"), "Build number should not have %:" + build);
	}
	
	@Test
	void buildAndHealthAreReportedInLogs() {
		MemoryAppender mem = MemoryAppender.getInstance("memory");
		testLoggingEvents(mem.getLoggedEvents().stream().map(LogEvent::new).toList());
	}
	
	void testLoggingEvents(List<LogEvent> list) {
		boolean hasBuild = false;
		boolean hasHeartbeat = false;
		Health health = null;
		boolean isHealthy = true;
		for (LogEvent event : list) {
			if (Strings.CS.contains(event.getMessage(), "Build:")) {
				hasBuild = true;
			} else if (Strings.CS.contains(event.getMessage(), "Heartbeat")) {
				hasHeartbeat = true;
			}
			
			
			Health h = event.getHealth();
			if (h != null) {
				health = h;		// Health was reported.
				if (!h.isHealthy()) {
					isHealthy = false;	
				}
			}
		}
		assertTrue(hasBuild, "Build not found");
		assertTrue(hasHeartbeat, "Heartbeat not found");
		assertNotNull(health, "Health was reported");
		assertTrue(isHealthy, "Instance is not healthy");
	}
	
	@Test
	void appControllerHealthTest() {
		assertNotNull(appController);
		assertTrue(appController.getHealth().isHealthy());
		MockHttpServletResponse m = new MockHttpServletResponse(); 
		assertTrue(appController.isHealthy(m).isHealthy());
		assertEquals(HttpStatus.OK.value(), m.getStatus());
		
		Application.shutdown();
		m = new MockHttpServletResponse(); 
		assertFalse(appController.isHealthy(m).isHealthy());
		assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), m.getStatus());
		HealthService.setHealthy(true, "Reset");
	}
	
	@Test void logControllerTest() {
		assertNotNull(logController);
		List<LogEvent> list = logController.getLogs(null);
		testLoggingEvents(list);
	}
	
	@Test
	void testLogEvents() {
		int count = 0;
		for (LogEvent event: logController.getLogs(null)) {
			count++;
			if ("DEBUG".equals(event.getLevel()) || Strings.CS.startsWith(event.getLoggerName(), "gov.cdc.izgateway.db.RefreshQueueService"))  {
				// Skip these, we don't care about them right now.
				continue;
			}
			Object eventId = event.getEventId();
			try {
				assertNotNull(eventId);
				assertTrue(eventId instanceof String);
				assertTrue(((String)eventId).matches("\\d+\\.\\d+"));
			} catch (AssertionFailedError err) {
				System.out.println("Failing Event["+count+"]: " + event.toString());
				throw err;
			}
		}
	}

	/**
	 * Guards {@code Application.configureMessageConverters(HttpMessageConverters.ServerBuilder)}.
	 * That hook runs after Framework 7's registerDefaults(), so the defaults must still be
	 * present alongside our custom SoapMessageConverter. The legacy
	 * configureMessageConverters(List) overload silently dropped every default converter
	 * app-wide once the list was populated; this fails loudly if that ever recurs.
	 * @throws IOException if writing through a converter fails
	 */
	@Test
	void messageConverterDefaultsSurviveCustomRegistration() throws IOException {
		assertNotNull(handlerAdapter);
		List<HttpMessageConverter<?>> converters = handlerAdapter.getMessageConverters();

		assertTrue(converters.stream().anyMatch(SoapMessageConverter.class::isInstance),
				"SoapMessageConverter was not registered: " + describe(converters));

		// A real JSON write of an existing non-SOAP endpoint's return value, through the same
		// converter list the handler adapter actually uses. If the defaults are ever dropped,
		// nothing claims Health and this fails here rather than breaking JSON app-wide at runtime.
		String json = writeWith(converters, appController.getHealth(), MediaType.APPLICATION_JSON);
		assertNotNull(json, "No registered converter writes Health as JSON: " + describe(converters));
		try (JsonParser parser = jf.createParser(json)) {
			assertEquals(JsonToken.START_OBJECT, parser.nextToken(),
					"Health did not serialize as a JSON object: " + json);
			assertNotNull(parser.nextFieldName(), "Health serialized as an empty JSON object: " + json);
		}

		// The old regression dropped *every* default, so cover the String/text-plain path too.
		String build = appController.getBuild();
		assertNotNull(build, "AppController.getBuild() returned no content to convert");
		String text = writeWith(converters, build, MediaType.TEXT_PLAIN);
		assertNotNull(text, "No registered converter writes String as text/plain: " + describe(converters));
		assertEquals(build, text);
	}

	/**
	 * Write a value through the first registered converter that claims it, the way
	 * RequestResponseBodyMethodProcessor selects one at runtime.
	 * @return the written body, or null if no registered converter claimed the value
	 */
	private String writeWith(List<HttpMessageConverter<?>> converters, Object value, MediaType mediaType)
			throws IOException {
		for (HttpMessageConverter<?> converter : converters) {
			if (converter.canWrite(value.getClass(), mediaType)) {
				@SuppressWarnings("unchecked")
				HttpMessageConverter<Object> writer = (HttpMessageConverter<Object>) converter;
				MockHttpOutputMessage out = new MockHttpOutputMessage();
				writer.write(value, mediaType, out);
				return out.getBodyAsString();
			}
		}
		return null;
	}

	private String describe(List<HttpMessageConverter<?>> converters) {
		return converters.stream().map(c -> c.getClass().getSimpleName()).toList().toString();
	}
}	
