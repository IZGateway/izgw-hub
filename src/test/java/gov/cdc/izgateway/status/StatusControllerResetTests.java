package gov.cdc.izgateway.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;

import gov.cdc.izgateway.common.ResourceNotFoundException;
import gov.cdc.izgateway.db.RefreshQueueService;
import gov.cdc.izgateway.db.RefreshQueueService.RefreshRequest;
import gov.cdc.izgateway.dynamodb.model.Destination;
import gov.cdc.izgateway.dynamodb.model.EndpointStatus;
import gov.cdc.izgateway.hub.service.DestinationService;
import gov.cdc.izgateway.hub.service.StatusCheckerService;
import gov.cdc.izgateway.model.IEndpointStatus;
import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.service.impl.EndpointStatusService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.security.RolesAllowed;

/**
 * Tests for the reset endpoints (IGDD-3599).
 * <p>
 * The messaging service is mocked, so nothing here reaches SQS.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatusControllerResetTests {

	@Mock
	private EndpointStatusService endpointStatusService;
	@Mock
	private StatusCheckerService checkerService;
	@Mock
	private AccessControlRegistry registry;
	@Mock
	private DestinationService destinationService;
	@Mock
	private RefreshQueueService refreshQueueService;

	@InjectMocks
	private StatusController controller;

	private static EndpointStatus statusFor(String destId) {
		EndpointStatus s = new EndpointStatus();
		s.setDestId(destId);
		s.setStatus(IEndpointStatus.CONNECTED);
		return s;
	}

	/**
	 * Hides the jurisdiction properties during serialization. They resolve through a
	 * Spring-managed singleton that no unit test starts. This test is about where
	 * Jackson places the status fields, not about jurisdictions.
	 */
	abstract static class NoJurisdictionLookup {
		@JsonIgnore
		abstract Object getJurisdictionService();

		@JsonIgnore
		abstract String getJurisdictionName();

		@JsonIgnore
		abstract String getJurisdictionDesc();
	}

	private static ObjectMapper mapperWithoutJurisdictionLookup() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.addMixIn(EndpointStatus.class, NoJurisdictionLookup.class);
		return mapper;
	}

	/** Make distribute() fill the outcome map the way a real fan-out would. */
	private void distributeYields(Map<String, String> remoteOutcomes) {
		when(refreshQueueService.distribute(any(RefreshRequest.class), any()))
				.thenAnswer(call -> {
					Map<String, String> results = call.getArgument(1);
					results.putAll(remoteOutcomes);
					return results;
				});
	}

	// ---------- Task 4.2: reset every destination

	@Test
	void resetAllReturnsDestinationsHostsAndRegions() {
		doReturn(List.of(statusFor("md"), statusFor("ca"))).when(endpointStatusService).findAll();
		distributeYields(Map.of("us-west-1:host-b", "OK"));

		StatusController.ResetAllResponse response = controller.resetCircuitBreakers();

		assertEquals(2, response.destinations().size());
		assertNotNull(response.destinations().get("md"));
		assertTrue(response.hosts().containsValue("OK (Local)"), "the local instance is recorded");
		assertTrue(response.hosts().containsKey("us-west-1:host-b"), "the remote instance is recorded");
		assertEquals("complete", response.regions().get("us-west-1"));
	}

	@Test
	void resetAllDistributesToEveryOtherInstance() {
		doReturn(List.of()).when(endpointStatusService).findAll();
		distributeYields(Map.of());

		controller.resetCircuitBreakers();

		ArgumentCaptor<RefreshRequest> sent = ArgumentCaptor.forClass(RefreshRequest.class);
		verify(refreshQueueService).distribute(sent.capture(), any());
		assertTrue(sent.getValue().reset(), "the command must reset");
		assertFalse(sent.getValue().refreshConfig(), "a reset must not also reload configuration");
		assertEquals(null, sent.getValue().destId(), "a null destination means every destination");
	}

	// ---------- Task 4.3: reset one destination, additively

	@Test
	void resetOneKeepsTheExistingFieldsAndAddsTheNewMembers() {
		when(destinationService.findByDestId("md")).thenReturn(new Destination());
		when(endpointStatusService.resetCircuitBreakerById("md")).thenReturn(statusFor("md"));
		distributeYields(Map.of("us-west-1:host-b", "OK"));

		StatusController.ResetOneResponse response = controller.resetCircuitBreakerById("md");

		assertEquals("md", response.getStatus().getDestId(), "destId keeps its name and value");
		assertEquals(IEndpointStatus.CONNECTED, response.getStatus().getStatus());
		assertNotNull(response.getHosts());
		assertNotNull(response.getRegions());
	}

	@Test
	void resetOneSerializesTheStatusFieldsAtTheTopLevel() throws Exception {
		// The Configuration Console reads destId and status from the top level. The
		// @JsonUnwrapped on the status member is what keeps them there.
		Map<String, String> hosts = new TreeMap<>();
		hosts.put("us-east-1:host-a", "OK (Local)");
		StatusController.ResetOneResponse response = new StatusController.ResetOneResponse(
				statusFor("md"), hosts, RefreshQueueService.summarizeByRegion(hosts));

		String json = mapperWithoutJurisdictionLookup().writeValueAsString(response);

		assertTrue(json.contains("\"destId\":\"md\""), "destId must stay at the top level: " + json);
		assertTrue(json.contains("\"hosts\""), "hosts must be present for an authorised caller");
		assertTrue(json.contains("\"regions\""), "regions must be present");
		assertFalse(json.contains("\"status\":{"), "the status object must not be nested under a status key");
	}

	@Test
	void resetOneDistributesForThatDestinationOnly() {
		when(destinationService.findByDestId("md")).thenReturn(new Destination());
		when(endpointStatusService.resetCircuitBreakerById("md")).thenReturn(statusFor("md"));
		distributeYields(Map.of());

		controller.resetCircuitBreakerById("md");

		ArgumentCaptor<RefreshRequest> sent = ArgumentCaptor.forClass(RefreshRequest.class);
		verify(refreshQueueService).distribute(sent.capture(), any());
		assertEquals("md", sent.getValue().destId());
		assertTrue(sent.getValue().reset());
	}

	// ---------- Task 4.4: an unknown destination sends nothing

	@Test
	void anUnknownDestinationIsNotFoundAndSendsNoMessage() {
		when(destinationService.findByDestId("nosuchdest")).thenReturn(null);

		assertThrows(ResourceNotFoundException.class, () -> controller.resetCircuitBreakerById("nosuchdest"));

		verify(refreshQueueService, never()).distribute(any(), any());
		verify(endpointStatusService, never()).resetCircuitBreakerById(anyString());
	}

	@Test
	void aDestinationWithNoStatusIsNotFoundAndSendsNoMessage() {
		when(destinationService.findByDestId("md")).thenReturn(new Destination());
		when(endpointStatusService.resetCircuitBreakerById("md")).thenReturn(null);

		assertThrows(ResourceNotFoundException.class, () -> controller.resetCircuitBreakerById("md"));

		verify(refreshQueueService, never()).distribute(any(), any());
	}

	// ---------- Task 4.5: timeout and partial failure still answer 200

	@Test
	void aSilentInstanceIsMarkedTimedOutAndTheCallStillSucceeds() {
		doReturn(List.of()).when(endpointStatusService).findAll();
		distributeYields(Map.of("us-west-1:host-b", RefreshQueueService.TIMED_OUT));

		// No exception means the controller answers 200. A thrown fault would be a 4xx or 5xx.
		StatusController.ResetAllResponse response = controller.resetCircuitBreakers();

		assertEquals(RefreshQueueService.TIMED_OUT, response.hosts().get("us-west-1:host-b"));
		assertEquals("failed", response.regions().get("us-west-1"));
	}

	@Test
	void aPartlyFailedRegionIsReportedAsPartial() {
		doReturn(List.of()).when(endpointStatusService).findAll();
		Map<String, String> remote = new TreeMap<>();
		remote.put("us-west-1:host-b", "OK");
		remote.put("us-west-1:host-c", RefreshQueueService.TIMED_OUT);
		distributeYields(remote);

		StatusController.ResetAllResponse response = controller.resetCircuitBreakers();

		assertEquals("partial", response.regions().get("us-west-1"));
		assertEquals("complete", response.regions().values().stream()
				.filter("complete"::equals).findFirst().orElse(null),
				"the local region reset cleanly and must read complete");
	}

	@Test
	void aMissingQueueIsCarriedThroughToTheCaller() {
		doReturn(List.of()).when(endpointStatusService).findAll();
		distributeYields(Map.of("us-west-1:host-gone", RefreshQueueService.NO_QUEUE));

		StatusController.ResetAllResponse response = controller.resetCircuitBreakers();

		assertEquals(RefreshQueueService.NO_QUEUE, response.hosts().get("us-west-1:host-gone"));
	}

	// ---------- Both endpoints stay restricted to administrators

	@Test
	void bothResetEndpointsRequireTheAdminRole() throws NoSuchMethodException {
		// A reset now reaches every instance in every region, so losing this annotation
		// would widen the blast radius fleet-wide. These tests call the controller
		// directly and bypass Spring Security, so nothing else here would notice.
		for (Method m : List.of(
				StatusController.class.getMethod("resetCircuitBreakers"),
				StatusController.class.getMethod("resetCircuitBreakerById", String.class))) {
			RolesAllowed roles = m.getAnnotation(RolesAllowed.class);
			assertNotNull(roles, m.getName() + " must carry @RolesAllowed");
			assertTrue(List.of(roles.value()).contains(Roles.ADMIN),
					m.getName() + " must require the admin role, found " + List.of(roles.value()));
		}
	}

	// ---------- Task 4.8: the documented scope matches the behavior

	@Test
	void theSwaggerTextNoLongerClaimsASingleHost() throws NoSuchMethodException {
		Method all = StatusController.class.getMethod("resetCircuitBreakers");
		Method one = StatusController.class.getMethod("resetCircuitBreakerById", String.class);

		for (Method m : List.of(all, one)) {
			Operation op = m.getAnnotation(Operation.class);
			assertNotNull(op, m.getName() + " must stay documented");
			String text = op.summary() + " " + op.description();
			assertFalse(text.contains("on this host"),
					m.getName() + " must not claim a single host: " + text);
			assertTrue(text.contains("every"),
					m.getName() + " must say the reset reaches every instance: " + text);
		}
	}
}
