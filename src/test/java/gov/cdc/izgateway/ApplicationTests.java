package gov.cdc.izgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.mock.web.MockHttpServletResponse;
import com.fasterxml.jackson.core.JsonFactory;
import gov.cdc.izgateway.common.HealthService;
import gov.cdc.izgateway.logging.MemoryAppender;
import gov.cdc.izgateway.logging.event.Health;
import gov.cdc.izgateway.logging.event.LogEvent;
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
			
			
			Health h = (Health) event.getProperties().get("health");
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
}	
