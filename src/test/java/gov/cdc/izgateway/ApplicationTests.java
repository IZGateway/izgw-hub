package gov.cdc.izgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentest4j.AssertionFailedError;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import gov.cdc.izgateway.common.HealthService;
import gov.cdc.izgateway.dynamodb.DynamoDbRepositoryFactory;
import gov.cdc.izgateway.hub.repository.IAccessControlRepository;
import gov.cdc.izgateway.hub.repository.IAccessGroupRepository;
import gov.cdc.izgateway.hub.service.accesscontrol.AccessControlService;
import gov.cdc.izgateway.logging.MemoryAppender;
import gov.cdc.izgateway.logging.event.Health;
import gov.cdc.izgateway.logging.event.LogEvent;
import gov.cdc.izgateway.model.IAccessControl;
import gov.cdc.izgateway.model.IAccessGroup;
import gov.cdc.izgateway.security.AccessController;
import gov.cdc.izgateway.security.Roles;
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
	AccessControlService accessControlService;
	@Autowired(required = true)
	AccessController accessController;
	@Autowired(required = true)
	DynamoDbRepositoryFactory repositoryFactory;

	private static Boolean migrated = null;
	static {
		Application.setAbortOnNoIIS(false);
		Application.skipMigrations(false);
	}
	

    @BeforeAll
    void setupParameters() {
    	// We want a larger memory appender for these tests, because some tests log more info.
        MemoryAppender.getInstance("memory").setSize(100);
    }

	void setMigrated(boolean migrated) {
		if (ApplicationTests.migrated == null || ApplicationTests.migrated != migrated) {
			ApplicationTests.migrated = migrated;
			accessControlService.setMigrated(migrated);
		}
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

	// Skip ADS Roles, they are not handled via Access Control Groups, instead via AllowedUsers
	Set<String> accessControlRoles = Set.of(Roles.ADMIN, Roles.BLACKLIST, Roles.INTERNAL, Roles.OPERATIONS, Roles.SOAP, Roles.USERS);
	Set<String> accessControlGroups = new TreeSet<>();
	Set<String> accessControlEvent = new TreeSet<>();
	Set<String> accessControlUsers = new TreeSet<>();
	Set<String> adsUsers = new TreeSet<>();

	private void initData() {
		if (!accessControlGroups.isEmpty()) {
			return;
		}
		IAccessControlRepository acr = repositoryFactory.accessControlRepository();
		IAccessGroupRepository agr = repositoryFactory.accessGroupRepository();
		Set<String> wildcardUsers = new TreeSet<>();
		acr.findAll().forEach(ac -> {
			switch (ac.getCategory()) {
			case AccessControlService.GROUP_CATEGORY:
				String memberName = ac.getMember();
				if (ac.getName().startsWith("ads")) {
					// ADS groups are no longer roles, that access is controlled
					// by the AllowedUser list.
					adsUsers.add(memberName);
				} else {
					accessControlGroups.add(ac.getName());
				}
				if (IAccessControl.isGroup(memberName)) {
					if (memberName.startsWith("ads")) {
						adsUsers.add(memberName);
					} else {
						accessControlGroups.add(memberName);
					}
				} else if (!memberName.startsWith("*")) {	// Skip all wildcard users
					accessControlUsers.add(memberName);
				} else {
					wildcardUsers.add(memberName);
				}
				break;
			case AccessControlService.ROUTE_CATEGORY:
				accessControlEvent.add(ac.getName());
				break;
			}
		});
		for (IAccessGroup group : agr.findAll()) {
			for (String member : wildcardUsers) {
				String endPart = member.substring(1); // Remove the *
				for (String user : group.getUsers()) {
					if (user.endsWith(endPart)) {
						accessControlUsers.add(user); // These are the real users to test with
					}
				}
			}
		}
	}

	@Test
	@Disabled("Disabled because this is more of a one-time test to verify migration correctness.")
	void accessControlServiceTest() throws JsonProcessingException {
		initData();

		setMigrated(false);
		Map<String, Object> unmigratedData = accessController.getAccess();

		// Pretty print unmigratedData as JSON code
		ObjectWriter mapper = new ObjectMapper().writerWithDefaultPrettyPrinter();
		String unmigrated = mapper.writeValueAsString(unmigratedData);

		setMigrated(true);
		Map<String, Object> migratedData = accessController.getAccess();
		String migrated = mapper.writeValueAsString(migratedData);
		assertEquals(unmigrated, migrated);
	}
	
	@ParameterizedTest
	@MethodSource
	void testUsersAndRoles(String user, String role, Boolean result) {
		accessControlService.setMigrated(true);
		assertEquals(result, accessControlService.isUserInRole(user, role), 
			String.format("user %s %s in role %s", user, result ? "IS" : "IS NOT", role));
	}
	List<Object[]> testUsersAndRoles() {
		initData();
		accessControlService.setMigrated(false);
		List<Object[]> params = new ArrayList<>();
		for (String user : accessControlUsers) {
			for (String role : accessControlRoles) {
				params.add(new Object[] { user, role, accessControlService.isUserInRole(user, role) } );
			}
		}
		return params;
	}
	
	@ParameterizedTest
	@MethodSource
	void testGroupMembership(String user, String group, Boolean result) {
		setMigrated(true);
		assertEquals(result, accessControlService.isUserInGroup(user, group), 
			String.format("user %s %s in group %s", user, result ? "IS" : "IS NOT", group));
	}
	List<Object[]> testGroupMembership() {
		initData();
		accessControlService.setMigrated(false);
		List<Object[]> params = new ArrayList<>();
		for (String user : accessControlUsers) {
			for (String group : accessControlGroups) {
				params.add(new Object[] {user, group, accessControlService.isUserInGroup(user, group)} );
			}
		}
		return params;
	}
	
	@ParameterizedTest
	@MethodSource
	void testFileTypes(String fileType) {
		setMigrated(true);
		assertTrue(accessControlService.getEventTypes().contains(fileType), "File type " + fileType + " should exist");
	}
	
	Set<String> testFileTypes() {
		initData();
		return accessControlEvent;
	}

	@ParameterizedTest
	@MethodSource
	void testPositiveAdsUsers(String adsUser) {
		setMigrated(true);
		assertTrue(
			accessControlService.canAccessDestination(adsUser, "dex"),
			"ADS user " + adsUser + " should be able to access dex"
		);
		assertTrue(
			accessControlService.canAccessDestination(adsUser, "dex"),
			"ADS user " + adsUser + " should be able to access dex-dev"
		);
	}
	
	Set<String> testPositiveAdsUsers() {
		initData();
		return adsUsers;
	}
	
	@ParameterizedTest
	@MethodSource
	void testNegativeAdsUsers(String adsUser) {
		setMigrated(true);
		assertFalse(
			accessControlService.canAccessDestination(adsUser, "dex"),
			"ADS user " + adsUser + " should NOT be able to access dex"
		);
		assertFalse(
			accessControlService.canAccessDestination(adsUser, "dex-dev"),
			"ADS user " + adsUser + " should NOT be able to access dex-dev"
		);
	}
	
	Set<String> testNegativeAdsUsers() {
		initData();
		TreeSet<String> nonAdsUsers = new TreeSet<>(accessControlUsers);
		nonAdsUsers.removeAll(adsUsers);
		return nonAdsUsers;
	}
	

}	
