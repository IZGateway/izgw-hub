package gov.cdc.izgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import gov.cdc.izgateway.common.HealthService;
import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.logging.event.Health;
import gov.cdc.izgateway.logging.event.IHealth;
import gov.cdc.izgateway.logging.event.PublicHealth;
import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.security.IzgPrincipal;
import gov.cdc.izgateway.security.UnauthenticatedPrincipal;
import jakarta.servlet.http.HttpServletResponse;

/**
 * IGDD-3178: /rest/health and /rest/healthy must not disclose infrastructure detail to a
 * caller that has not authenticated.
 *
 * @see AppController
 */
class AppControllerHealthTests {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String SERVER_NAME = "phiz-web-ws-7.prod.internal";
	private static final String DATABASE = "jdbc:mysql://izgw-prod.cluster-abc123.us-east-1.rds.amazonaws.com:3306/izgw";

	private AppController controller;

	/** Stands in for a caller that authenticated by certificate or API key. */
	private static class AuthenticatedTestPrincipal extends IzgPrincipal {
		AuthenticatedTestPrincipal() {
			setName("cn=test.example.org");
		}

		@Override
		public String getSerialNumberHex() {
			return "00";
		}
	}

	/** HealthService holds one static report; give it detail worth withholding. */
	@BeforeAll
	static void populateHealth() {
		HealthService.setHealthy(true, "Started");
		HealthService.setServerName(SERVER_NAME);
		HealthService.setBuildName("2.18.0-1234");
		HealthService.setDatabase(DATABASE);
		HealthService.setEgressDnsAddress("54.87.148.103");
		HealthService.setIngressDnsAddress(new String[] { "3.232.82.52" });
		HealthService.setAccessControlAction("warn");
	}

	@BeforeEach
	void setUp() {
		controller = new AppController(mock(AccessControlRegistry.class));
	}

	@AfterEach
	void clearContext() {
		RequestContext.clear();
	}

	private static Set<String> keysOf(IHealth body) {
		JsonNode json = MAPPER.valueToTree(body);
		Set<String> keys = new TreeSet<>();
		json.fieldNames().forEachRemaining(keys::add);
		return keys;
	}

	@Test
	void getHealthDisclosesOnlyLivenessToUnauthenticatedCaller() {
		RequestContext.setPrincipal(new UnauthenticatedPrincipal());

		IHealth body = controller.getHealth();

		assertInstanceOf(PublicHealth.class, body);
		assertEquals(new TreeSet<>(Set.of("healthy", "statusAt")), keysOf(body));
	}

	/**
	 * The ALB target group probes /rest/health with no credential. Before LoggingValve has set
	 * a principal there is none at all, which must be treated as unauthenticated rather than
	 * falling through to the full report.
	 */
	@Test
	void getHealthDisclosesOnlyLivenessWhenThereIsNoPrincipal() {
		RequestContext.clear();

		IHealth body = controller.getHealth();

		assertInstanceOf(PublicHealth.class, body);
		assertEquals(new TreeSet<>(Set.of("healthy", "statusAt")), keysOf(body));
	}

	@Test
	void getHealthGivesTheFullReportToAnAuthenticatedCaller() throws Exception {
		RequestContext.setPrincipal(new AuthenticatedTestPrincipal());

		IHealth body = controller.getHealth();

		assertInstanceOf(Health.class, body);
		String json = MAPPER.writeValueAsString(body);
		assertTrue(json.contains(SERVER_NAME), "Authenticated callers keep the detail they have today");
		assertTrue(json.contains(DATABASE));
	}

	@Test
	void isHealthyDisclosesOnlyLivenessToUnauthenticatedCaller() throws Exception {
		RequestContext.setPrincipal(new UnauthenticatedPrincipal());
		HttpServletResponse resp = mock(HttpServletResponse.class);

		IHealth body = controller.isHealthy(resp);

		assertInstanceOf(PublicHealth.class, body);
		String json = MAPPER.writeValueAsString(body);
		assertFalse(json.contains(SERVER_NAME));
		assertFalse(json.contains("rds.amazonaws.com"));
		assertFalse(json.contains("54.87.148.103"));
		assertFalse(json.contains("warn"));
	}

	@Test
	void isHealthyGivesTheFullReportToAnAuthenticatedCaller() {
		RequestContext.setPrincipal(new AuthenticatedTestPrincipal());

		assertInstanceOf(Health.class, controller.isHealthy(mock(HttpServletResponse.class)));
	}

	/**
	 * The status code is what load balancer and container probes act on, so it must not depend
	 * on who is asking.
	 */
	@Test
	void isHealthyStatusCodeDoesNotDependOnTheCaller() {
		HealthService.setHealthy(false, "Database unreachable");
		try {
			RequestContext.setPrincipal(new UnauthenticatedPrincipal());
			HttpServletResponse anonymous = mock(HttpServletResponse.class);
			controller.isHealthy(anonymous);
			verify(anonymous).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);

			RequestContext.setPrincipal(new AuthenticatedTestPrincipal());
			HttpServletResponse authenticated = mock(HttpServletResponse.class);
			controller.isHealthy(authenticated);
			verify(authenticated).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		} finally {
			HealthService.setHealthy(true, "Recovered");
		}
	}

	/**
	 * The controller tests above assert on the returned object. These two go through Spring's own
	 * JSON serialization, because the declared return type is now the {@link IHealth} interface --
	 * if Spring ever pinned the writer to the declared type instead of the runtime type, every
	 * response would serialize as an empty object and none of the assertions above would notice.
	 * <p>
	 * {@code standaloneSetup} rather than {@code @SpringBootTest}: a full context here needs a real
	 * DynamoDB. See {@code HubWSDLControllerSourceAttackTests} for the same approach.
	 */
	@Test
	void theSerializedResponseWithholdsDetailFromAnUnauthenticatedCaller() throws Exception {
		RequestContext.setPrincipal(new UnauthenticatedPrincipal());

		String json = MockMvcBuilders.standaloneSetup(controller).build()
				.perform(get("/rest/health"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		JsonNode body = MAPPER.readTree(json);
		Set<String> keys = new TreeSet<>();
		body.fieldNames().forEachRemaining(keys::add);

		assertEquals(new TreeSet<>(Set.of("healthy", "statusAt")), keys,
				"Serialized body was: " + json);
	}

	@Test
	void theSerializedResponseCarriesDetailForAnAuthenticatedCaller() throws Exception {
		RequestContext.setPrincipal(new AuthenticatedTestPrincipal());

		String json = MockMvcBuilders.standaloneSetup(controller).build()
				.perform(get("/rest/health"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertTrue(json.contains(SERVER_NAME), "Serialized body was: " + json);
		assertTrue(json.contains(DATABASE), "Serialized body was: " + json);
	}

	/** The unhealthy reason can carry internal detail, so it is withheld along with the rest. */
	@Test
	void unhealthyReasonIsNotDisclosedToUnauthenticatedCaller() throws Exception {
		HealthService.setHealthy(false, "Cannot reach 10.0.3.44:3306");
		try {
			RequestContext.setPrincipal(new UnauthenticatedPrincipal());

			String json = MAPPER.writeValueAsString(controller.isHealthy(mock(HttpServletResponse.class)));

			assertFalse(json.contains("10.0.3.44"));
			assertTrue(json.contains("\"healthy\":false"));
		} finally {
			HealthService.setHealthy(true, "Recovered");
		}
	}
}
