package gov.cdc.izgateway.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.stereotype.Service;

import gov.cdc.izgateway.db.DbController.DbControllerConfiguration;
import gov.cdc.izgateway.db.RefreshQueueService.RefreshRequest;
import gov.cdc.izgateway.hub.security.ApiKeyPrincipalProvider;
import gov.cdc.izgateway.repository.IHostRepository;
import gov.cdc.izgateway.service.impl.EndpointStatusService;
import jakarta.annotation.PostConstruct;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import gov.cdc.izgateway.db.RefreshQueueService.RefreshResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * Tests for the cross-instance messaging service (IGDD-3599).
 * <p>
 * These tests never touch AWS. Queue creation and the listener thread live in a
 * {@code @PostConstruct} method that Spring calls, not in the constructor, so a plain
 * instance is safe to build here.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefreshQueueServiceTests {

	@BeforeEach
	void shortenTheReplyWait() {
		// The production value is 15 seconds. Tests must not wait for it.
		RefreshQueueService.responseTimeoutMillis = 50L;
	}

	@AfterEach
	void restoreTheReplyWait() {
		RefreshQueueService.responseTimeoutMillis = 15000L;
	}

	@Mock
	private DbControllerConfiguration configuration;
	@Mock
	private EndpointStatusService endpointStatusService;
	@Mock
	private ApiKeyPrincipalProvider apiKeyPrincipalProvider;
	@Mock
	private IHostRepository hostService;

	@InjectMocks
	private RefreshQueueService service;

	private static MessageAttributeValue attr(String value) {
		return MessageAttributeValue.builder().stringValue(value).dataType("String").build();
	}

	// ---------- Task 1.5: one listener, started by Spring rather than the constructor

	@Test
	void constructorDoesNotCreateQueuesOrStartAListener() {
		// Building the bean must do no AWS work. If queue creation moved back into the
		// constructor, every test here would try to reach SQS.
		assertNotNull(service);
		verifyNoInteractions(hostService, endpointStatusService, configuration, apiKeyPrincipalProvider);
	}

	@Test
	void startupIsASinglePostConstructSoOnlyOneListenerRuns() throws NoSuchMethodException {
		Method start = RefreshQueueService.class.getDeclaredMethod("start");
		assertNotNull(start.getAnnotation(PostConstruct.class),
				"start() must be @PostConstruct so Spring runs it once for the singleton");
		assertNotNull(RefreshQueueService.class.getAnnotation(Service.class),
				"the class must be a singleton bean; two instances would compete for one queue");
	}

	// ---------- Task 2.5: the recursion guard

	@Test
	void applyingAReceivedCommandSendsNoFurtherMessage() {
		RefreshRequest received = new RefreshRequest(true, "evt-1", "sender", "us-east-1", null, true, null);

		String status = service.applyLocally(received);

		assertEquals("OK", status);
		verify(endpointStatusService).resetCircuitBreakers();
		verify(configuration).refresh();
		// The host list is what a distribution would read. Applying must never read it.
		verifyNoInteractions(hostService);
	}

	@Test
	void applyLocallyResetsOneDestinationWhenNamed() {
		service.applyLocally(new RefreshRequest(true, "evt-2", "sender", "us-east-1", null, false, "md"));

		verify(endpointStatusService).resetCircuitBreakerById("md");
		verify(endpointStatusService, never()).resetCircuitBreakers();
		verify(configuration, never()).refresh();
	}

	@Test
	void applyLocallyEvictsACredentialWhenPresent() {
		service.applyLocally(new RefreshRequest(false, "evt-3", "sender", "us-east-1", "jti-9", false, null));

		verify(apiKeyPrincipalProvider).evictCredential("jti-9");
	}

	// ---------- Task 3.3: a failure must not be reported as OK

	@Test
	void applyLocallyReportsAFailureRatherThanOk() {
		when(endpointStatusService.resetCircuitBreakerById(anyString()))
				.thenThrow(new IllegalStateException("datastore unavailable"));

		String status = service.applyLocally(
				new RefreshRequest(true, "evt-4", "sender", "us-east-1", null, false, "md"));

		assertFalse(status.startsWith("OK"), "a failed reset must not be reported as OK");
		assertEquals("datastore unavailable", status);
	}

	// ---------- Task 3.4: rolling-deploy compatibility, both directions

	@Test
	void aMessageFromThePreviousReleaseParsesWithTheOldDefaults() {
		// An instance running the previous release sends neither refreshConfig nor destId.
		Message old = Message.builder().body(RefreshRequest.MESSAGE).messageAttributes(Map.of(
				"reset", attr("true"),
				"eventId", attr("evt-5"),
				"senderHost", attr("old-host"),
				"senderRegion", attr("us-west-1"))).build();

		RefreshRequest parsed = RefreshRequest.fromMessage(old);

		assertTrue(parsed.reset());
		assertTrue(parsed.refreshConfig(), "an absent refreshConfig must keep the old behavior");
		assertNull(parsed.destId(), "an absent destId must mean every destination");
		assertEquals("old-host", parsed.senderHost());
	}

	@Test
	void aMessageFromThisReleaseStillCarriesEveryAttributeTheOldParserReads() {
		RefreshRequest current = new RefreshRequest(true, "evt-6", "host", "us-east-1", "jti-1", false, "md");

		Map<String, MessageAttributeValue> map = current.toMap();

		// The previous release reads exactly these names. Renaming one breaks a rolling
		// deployment while mixed versions run.
		for (String name : new String[] { "reset", "eventId", "senderHost", "senderRegion", "jti" }) {
			assertTrue(map.containsKey(name), "attribute " + name + " must survive for older instances");
		}
		assertEquals("md", map.get("destId").stringValue());
		assertEquals("false", map.get("refreshConfig").stringValue());
	}

	@Test
	void aRoundTripThroughMessageAttributesPreservesEveryField() {
		RefreshRequest sent = new RefreshRequest(true, "evt-7", "host", "us-east-1", "jti-2", false, "ca");

		Message wire = Message.builder().body(RefreshRequest.MESSAGE)
				.messageAttributes(sent.toMap()).build();

		assertEquals(sent, RefreshRequest.fromMessage(wire));
	}

	// ---------- Task 4.2 and 5.3: distribution and honest outcomes

	/**
	 * A service whose SQS client is a mock, so the fan-out can be exercised without
	 * reaching AWS. {@code clientFor} is the only seam needed.
	 */
	private RefreshQueueService withMockedSqs(SqsClient sqs) {
		RefreshQueueService spied = spy(
				new RefreshQueueService(configuration, endpointStatusService, apiKeyPrincipalProvider, hostService));
		doReturn(sqs).when(spied).clientFor(anyString());
		return spied;
	}

	@SuppressWarnings("unchecked")
	private static SqsClient sqsAccepting(Message... repliesToReceive) {
		SqsClient sqs = mock(SqsClient.class);
		when(sqs.getQueueUrl(any(java.util.function.Consumer.class)))
				.thenReturn(GetQueueUrlResponse.builder().queueUrl("https://sqs.test/q").build());
		when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(SendMessageResponse.builder().build());
		when(sqs.receiveMessage(any(java.util.function.Consumer.class)))
				.thenReturn(ReceiveMessageResponse.builder().messages(List.of(repliesToReceive)).build());
		return sqs;
	}

	private static SqsClient sqsThatAcceptsSendsAndNeverReplies() {
		SqsClient sqs = mock(SqsClient.class);
		when(sqs.getQueueUrl(any(java.util.function.Consumer.class)))
				.thenReturn(GetQueueUrlResponse.builder().queueUrl("https://sqs.test/q").build());
		when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(SendMessageResponse.builder().build());
		when(sqs.receiveMessage(any(java.util.function.Consumer.class)))
				.thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());
		return sqs;
	}

	@Test
	void distributeSkipsTheCallerAndMessagesEveryOtherInstance() {
		Map<String, String> hosts = new TreeMap<>();
		hosts.put("host-a", "us-east-1");
		hosts.put("host-b", "us-east-1");
		hosts.put("host-c", "us-west-1");
		when(hostService.getHostsAndRegion()).thenReturn(hosts);
		SqsClient sqs = sqsThatAcceptsSendsAndNeverReplies();

		Map<String, String> results = new TreeMap<>();
		results.put("us-east-1:host-a", "OK (Local)");
		withMockedSqs(sqs).distribute(
				new RefreshRequest(true, "evt-8", "host-a", "us-east-1", null, false, null), results);

		assertEquals("OK (Local)", results.get("us-east-1:host-a"), "the caller applies locally, not by message");
		assertTrue(results.containsKey("us-east-1:host-b"));
		assertTrue(results.containsKey("us-west-1:host-c"));
		assertEquals(3, results.size());
		// Two remote instances, so two sends. The caller is not messaged.
		verify(sqs, times(2)).sendMessage(any(SendMessageRequest.class));
	}

	@Test
	void anInstanceIsNeverReportedSuccessfulWithoutAReply() {
		Map<String, String> hosts = new TreeMap<>();
		hosts.put("host-a", "us-east-1");
		hosts.put("host-b", "us-east-1");
		when(hostService.getHostsAndRegion()).thenReturn(hosts);

		Map<String, String> results = new TreeMap<>();
		results.put("us-east-1:host-a", "OK (Local)");
		withMockedSqs(sqsThatAcceptsSendsAndNeverReplies()).distribute(
				new RefreshRequest(true, "evt-9", "host-a", "us-east-1", null, false, null), results);

		String remote = results.get("us-east-1:host-b");
		assertNotNull(remote);
		assertEquals(RefreshQueueService.TIMED_OUT, remote,
				"no reply arrived, so the instance must be reported as timed out rather than OK");
		assertFalse(remote.startsWith("OK"));
	}

	@Test
	void aRemoteInstanceThatRepliesWithAFailureIsNotReportedAsOk() {
		// The receiving instance applied the command and it failed. The reply carries
		// that status, and it must reach the caller intact rather than being flattened
		// to OK. Spec: "Never report a failed reset as success" / "A remote instance fails".
		Message failureReply = Message.builder().body(RefreshResponse.MESSAGE)
				.messageAttributes(new RefreshResponse("host-b", "us-west-1", "evt-20",
						"datastore unavailable").toMap())
				.receiptHandle("rh-9").build();
		when(hostService.getHostsAndRegion()).thenReturn(Map.of("host-a", "us-east-1", "host-b", "us-west-1"));

		Map<String, String> results = new TreeMap<>();
		results.put("us-east-1:host-a", "OK (Local)");
		withMockedSqs(sqsAccepting(failureReply)).distribute(
				new RefreshRequest(true, "evt-20", "host-a", "us-east-1", null, false, null), results);

		assertEquals("datastore unavailable", results.get("us-west-1:host-b"));
		assertEquals("failed", RefreshQueueService.summarizeByRegion(results).get("us-west-1"),
				"a region whose only instance reported a failure must read failed");
		assertEquals("complete", RefreshQueueService.summarizeByRegion(results).get("us-east-1"));
	}

	@Test
	void anInstanceWithNoQueueIsReportedSeparatelyFromASendFailure() {
		when(hostService.getHostsAndRegion()).thenReturn(Map.of("host-a", "us-east-1", "host-gone", "us-east-1"));
		SqsClient sqs = mock(SqsClient.class);
		when(sqs.getQueueUrl(any(java.util.function.Consumer.class)))
				.thenThrow(QueueDoesNotExistException.builder().message("gone").build());

		Map<String, String> results = new TreeMap<>();
		results.put("us-east-1:host-a", "OK (Local)");
		withMockedSqs(sqs).distribute(
				new RefreshRequest(true, "evt-12", "host-a", "us-east-1", null, false, null), results);

		assertEquals(RefreshQueueService.NO_QUEUE, results.get("us-east-1:host-gone"),
				"a retired task must be distinguishable from a real SQS fault");
		verify(sqs, never()).sendMessage(any(SendMessageRequest.class));
	}

	// ---------- Tasks 4.6 and 4.7: the region summary

	@Test
	void everyInstanceSucceedingGivesComplete() {
		Map<String, String> results = new TreeMap<>();
		results.put("us-east-1:host-a", "OK (Local)");
		results.put("us-east-1:host-b", "OK");

		assertEquals("complete", RefreshQueueService.summarizeByRegion(results).get("us-east-1"));
	}

	@Test
	void someInstancesSucceedingGivesPartial() {
		Map<String, String> results = new TreeMap<>();
		results.put("us-west-1:host-c", "OK");
		results.put("us-west-1:host-d", RefreshQueueService.TIMED_OUT);

		assertEquals("partial", RefreshQueueService.summarizeByRegion(results).get("us-west-1"));
	}

	@Test
	void noInstanceSucceedingGivesFailed() {
		Map<String, String> results = new TreeMap<>();
		results.put("us-west-1:host-c", RefreshQueueService.NO_QUEUE);
		results.put("us-west-1:host-d", RefreshQueueService.SEND_FAILURE);

		assertEquals("failed", RefreshQueueService.summarizeByRegion(results).get("us-west-1"));
	}

	@Test
	void oneRegionFailingDoesNotAffectAnother() {
		Map<String, String> results = new TreeMap<>();
		results.put("us-east-1:host-a", "OK (Local)");
		results.put("us-east-1:host-b", "OK");
		results.put("us-west-1:host-c", "OK");
		results.put("us-west-1:host-d", RefreshQueueService.TIMED_OUT);

		Map<String, String> summary = RefreshQueueService.summarizeByRegion(results);

		assertEquals("complete", summary.get("us-east-1"));
		assertEquals("partial", summary.get("us-west-1"));
	}

	@Test
	void theRegionSummaryCarriesNoHostNameAndNoCount() {
		Map<String, String> results = new TreeMap<>();
		results.put("us-east-1:ip-10-9-93-145.ec2.internal", "OK (Local)");
		results.put("us-west-1:ip-10-161-101-98.us-west-1.compute.internal", RefreshQueueService.TIMED_OUT);

		Map<String, String> summary = RefreshQueueService.summarizeByRegion(results);

		// Keys are bare region names, values are one of three fixed words. A consumer can
		// forward this to a browser without redacting it. See IGDD-3599, design D5.
		assertEquals(Map.of("us-east-1", "complete", "us-west-1", "failed"), summary);
		for (String key : summary.keySet()) {
			assertFalse(key.contains(":"), "a region key must not carry a host name");
			assertFalse(key.contains("ip-10-"), "a region key must not carry a host name");
		}
		for (String value : summary.values()) {
			assertTrue(value.matches("complete|partial|failed"), "a region status must disclose no count");
			assertFalse(value.matches(".*\\d.*"), "a region status must disclose no count");
		}
	}

	@Test
	void anEmptyResultMapSummarisesToNothing() {
		assertTrue(RefreshQueueService.summarizeByRegion(new TreeMap<>()).isEmpty());
	}

	// ---------- Guard: no HTTP call path survives

	@Test
	void theControllerNoLongerExposesAnHttpSelfCall() {
		// resetEndpoint posted to this instance's own /rest/reset with the default GET
		// method, which AccessControlValve rejected as an unknown path. IGDD-3599.
		for (Method m : DbController.class.getDeclaredMethods()) {
			assertFalse("resetEndpoint".equals(m.getName()),
					"resetEndpoint must stay removed; commands are applied in process");
			assertFalse("callEndpoint".equals(m.getName()),
					"callEndpoint must stay removed; it is the recursion risk");
		}
	}

	@Test
	void distributeIsNotCalledWhenNoOtherInstanceExists() {
		when(hostService.getHostsAndRegion()).thenReturn(Map.of("host-a", "us-east-1"));

		Map<String, String> results = new TreeMap<>();
		results.put("us-east-1:host-a", "OK (Local)");
		withMockedSqs(sqsThatAcceptsSendsAndNeverReplies()).distribute(
				new RefreshRequest(true, "evt-10", "host-a", "us-east-1", null, false, null), results);

		assertEquals(1, results.size(), "the only instance is the caller, so nothing is sent");
		assertEquals("OK (Local)", results.get("us-east-1:host-a"));
	}

	@Test
	void applyLocallyIgnoresANullCredential() {
		service.applyLocally(new RefreshRequest(false, "evt-11", "sender", "us-east-1", null, true, null));

		verify(apiKeyPrincipalProvider, never()).evictCredential(any());
		verify(configuration).refresh();
	}
}
