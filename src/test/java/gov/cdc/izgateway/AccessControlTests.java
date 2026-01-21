package gov.cdc.izgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.ComponentScan;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import gov.cdc.izgateway.ads.ADSController;
import gov.cdc.izgateway.ads.MetadataFault;
import gov.cdc.izgateway.dynamodb.DynamoDbRepositoryFactory;
import gov.cdc.izgateway.dynamodb.model.AccessControl;
import gov.cdc.izgateway.dynamodb.model.AccessGroup;
import gov.cdc.izgateway.hub.repository.IAccessControlRepository;
import gov.cdc.izgateway.hub.repository.IAccessGroupRepository;
import gov.cdc.izgateway.hub.service.accesscontrol.AccessControlService;
import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.logging.event.Health;
import gov.cdc.izgateway.logging.event.LogEvent;
import gov.cdc.izgateway.logging.event.TransactionData;
import gov.cdc.izgateway.model.IAccessControl;
import gov.cdc.izgateway.model.IAccessGroup;
import gov.cdc.izgateway.security.AccessController;
import gov.cdc.izgateway.security.IzgPrincipal;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.soap.fault.Fault;
import gov.cdc.izgateway.soap.fault.SecurityFault;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
	useMainMethod = SpringBootTest.UseMainMethod.ALWAYS
)
@ComponentScan("gov.cdc.izgateway")
@Disabled("Disabled because this is more of a one-time test to verify migration correctness.")
class AccessControlTests {
	static JsonFactory jf = new JsonFactory(); 
	@Autowired(required = true)
	private AccessControlService accessControlService;
	@Autowired(required = true)
	private AccessController accessController;
	@Autowired(required = true)
	private DynamoDbRepositoryFactory repositoryFactory;
	@Autowired(required = true)
	private ADSController adsController;

	private static Boolean migration = null;
	static {
		Application.setAbortOnNoIIS(false);
		Application.skipMigrations(true);
	}

	void setMigrated(boolean migration) {
		if (AccessControlTests.migration == null || AccessControlTests.migration != migration) {
			AccessControlTests.migration = migration;
			accessControlService.setMigrated(migration);
		}
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
		IAccessControlRepository<AccessControl> acr = repositoryFactory.accessControlRepository();
		IAccessGroupRepository<AccessGroup> agr = repositoryFactory.accessGroupRepository();
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
			default:
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
		// Add some users for testing
		accessControlUsers.add("localhost"); 
		accessControlUsers.add("dev.izgateway.org"); 
		accessControlUsers.add("example.com");
		accessControlUsers.add("unknown");
	}

	@Test
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
				// Make some adjustments for known special cases under the new model
				if (role.equals("internal") && List.of("dev.xform.izgateway.org", "test.izgateway.org", "dev.izgateway.org").contains(user)) {
					params.add(new Object[] { user, role, true } );
				} else if (List.of("ehealthsign.com", "securityrs.com").contains(user) && !role.equals("blacklist")) {
					params.add(new Object[] { user, role, true } );
				} else if ("epicenter.stchealthops.com".equals(user) && role.equals("blacklist")) {
					params.add(new Object[] { user, role, false } );
				} else {
					params.add(new Object[] { user, role, accessControlService.isUserInRole(user, role) } );
				}
			}
		}
		System.out.println("Generated \n");
		params.forEach(p -> {
			System.out.printf("  { \"%s\", \"%s\", %s },%n", p[0], p[1], p[2]);
		});
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
		try {
			fakeTheContext(adsUser);
			adsController.postADSFile("dex", adsUser + " Test to dex", null, null, "XXA", "invalid report", null, null, adsUser, false);
		} catch (Fault e) {
			assertFalse(e instanceof SecurityFault, "ADS user " + adsUser + " should NOT receive SecurityFault when posting to dex");
		}
	}
	
	Set<String> testPositiveAdsUsers() {
		initData();
		setMigrated(true);
		TreeSet<String> allAdsUsers = new TreeSet<>(adsUsers);
		Iterator<String> iter = accessControlUsers.iterator();
		while (iter.hasNext()) {
			String user = iter.next();
			if (accessControlService.isUserInRole(user, Roles.ADMIN)) {
				allAdsUsers.add(user);
			}
		}
		
		return allAdsUsers;
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
		try {
			fakeTheContext(adsUser);
			adsController.postADSFile("dex", adsUser + " Test to dex", null, null, "XXA", "invalid report", null, null, adsUser, false);
		} catch (Fault e) {
			assertTrue(e instanceof MetadataFault, "ADS user " + adsUser + " should receive MetadataFault when posting to dex");
		}
	}

	private void fakeTheContext(String adsUser) {
		TransactionData tData = new TransactionData();
		tData.getSource().setPrincipal(new IzgPrincipal() {
			@Override
			public String getSerialNumberHex() {
				return null;
			}
			@Override
			public String getName() {
				return adsUser;
			}
		}
		);
		RequestContext.setTransactionData(tData);
	}
	
	Set<String> testNegativeAdsUsers() {
		initData();
		setMigrated(true);
		TreeSet<String> nonAdsUsers = new TreeSet<>(accessControlUsers);
		nonAdsUsers.removeAll(adsUsers);
		Iterator<String> iter = nonAdsUsers.iterator();
		while (iter.hasNext()) {
			if (accessControlService.isUserInRole(iter.next(), Roles.ADMIN)) {
				iter.remove();
			}
		}
		return nonAdsUsers;
	}
}	
