package gov.cdc.izgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;



import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import javax.sql.DataSource;

@Slf4j
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
	}
	
	@Autowired ApplicationTests() {
		log.info("Started");
	}
	
	@BeforeEach void initData() {
		log.info("Before Each");
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
	void buildAndHealthAreReportedInLogs() throws IOException {
		MemoryAppender mem = MemoryAppender.getInstance("memory");
		testLoggingEvents(mem.getLoggedEvents().stream().map(event -> new LogEvent(event)).toList());
	}
	
	void testLoggingEvents(List<LogEvent> list) {
		boolean hasBuild = false;
		boolean hasHeartbeat = false;
		Health health = null;
		boolean isHealthy = true;
		for (LogEvent event : list) {
			if (StringUtils.contains(event.getMessage(), "Build:")) {
				hasBuild = true;
			} else if (StringUtils.contains(event.getMessage(), "Heartbeat")) {
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
	
	@Test void logControllerTest() throws IOException {
		assertNotNull(logController);
		List<LogEvent> list = logController.getLogs(null);
		testLoggingEvents(list);
	}
	
	@Test
	void testLogEvents() throws IOException {
		int count = 0;
		for (LogEvent event: logController.getLogs(null)) {
			count++;
			if ("DEBUG".equals(event.getLevel()) || StringUtils.startsWith(event.getLoggerName(), "gov.cdc.izgateway.db.RefreshQueueService"))  {
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
