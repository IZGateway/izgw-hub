package gov.cdc.izgateway.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gov.cdc.izgateway.db.DbController.DbControllerConfiguration;
import gov.cdc.izgateway.db.RefreshQueueService.RefreshRequest;
import gov.cdc.izgateway.db.RefreshQueueService.RefreshResponse;
import gov.cdc.izgateway.hub.security.ApiKeyPrincipalProvider;
import gov.cdc.izgateway.repository.IHostRepository;
import gov.cdc.izgateway.service.impl.EndpointStatusService;
import net.logstash.logback.encoder.LogstashEncoder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * Tests that SQS traffic leaves a usable trail (IGDD-3599, defect 5).
 * <p>
 * Each event is encoded with the same encoder the application uses, so these tests
 * assert the JSON that reaches Elasticsearch rather than the in-memory marker.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefreshQueueLoggingTests {

	@Mock
	private DbControllerConfiguration configuration;
	@Mock
	private EndpointStatusService endpointStatusService;
	@Mock
	private ApiKeyPrincipalProvider apiKeyPrincipalProvider;
	@Mock
	private IHostRepository hostService;

	private Logger logger;
	private ListAppender<ILoggingEvent> appender;
	private LogstashEncoder encoder;

	@BeforeEach
	void captureLogs() {
		RefreshQueueService.responseTimeoutMillis = 50L;
		logger = (Logger) LoggerFactory.getLogger(RefreshQueueService.class);
		appender = new ListAppender<>();
		appender.setContext(logger.getLoggerContext());
		appender.start();
		logger.addAppender(appender);
		logger.setLevel(Level.INFO);

		encoder = new LogstashEncoder();
		encoder.setContext(logger.getLoggerContext());
		// Both settings mirror logback-spring.xml, and both matter.
		// includeTags: marker names collide with Filebeat's environment tag (IGDD-3562).
		// includeContext: logback-spring.xml loads every application.yml key as a context
		// property, and one of them is named "level". Writing those into the record emits a
		// second, empty "level" field that overrides the real one. The application's own
		// appenders disable context for the same reason.
		encoder.setIncludeTags(false);
		encoder.setIncludeContext(false);
		encoder.start();
	}

	@AfterEach
	void releaseLogs() {
		logger.detachAppender(appender);
		RefreshQueueService.responseTimeoutMillis = 15000L;
	}

	/** The JSON Filebeat would ship for the first event whose message contains the text. */
	private JsonNode jsonOf(String messageFragment) throws Exception {
		for (ILoggingEvent event : appender.list) {
			if (event.getFormattedMessage().contains(messageFragment)) {
				String json = new String(encoder.encode(event), StandardCharsets.UTF_8);
				return new ObjectMapper().readTree(json);
			}
		}
		throw new AssertionError("no log record containing: " + messageFragment
				+ " in " + appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
	}

	private boolean anyRecordContains(String fragment) {
		return appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains(fragment));
	}

	private RefreshQueueService serviceWith(SqsClient sqs) {
		RefreshQueueService spied = spy(
				new RefreshQueueService(configuration, endpointStatusService, apiKeyPrincipalProvider, hostService));
		doReturn(sqs).when(spied).clientFor(anyString());
		return spied;
	}

	@SuppressWarnings("unchecked")
	private static SqsClient sqsAccepting(Message... repliesToReceive) {
		SqsClient sqs = mock(SqsClient.class);
		when(sqs.getQueueUrl(any(Consumer.class)))
				.thenReturn(GetQueueUrlResponse.builder().queueUrl("https://sqs.test/q").build());
		when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(SendMessageResponse.builder().build());
		when(sqs.receiveMessage(any(Consumer.class)))
				.thenReturn(ReceiveMessageResponse.builder().messages(List.of(repliesToReceive)).build());
		return sqs;
	}

	// ---------- Task 6.1: a successful send is recorded

	@Test
	void aSuccessfulSendIsRecordedAtInfoWithEveryField() throws Exception {
		when(hostService.getHostsAndRegion()).thenReturn(Map.of("host-a", "us-east-1", "host-b", "us-west-1"));

		Map<String, String> results = new TreeMap<>();
		results.put("us-east-1:host-a", "OK (Local)");
		serviceWith(sqsAccepting()).distribute(
				new RefreshRequest(true, "evt-1", "host-a", "us-east-1", "jti-7", false, null), results);

		JsonNode record = jsonOf("SQS send RefreshRequest");

		assertEquals("INFO", record.path("level").asText(), "a send must be visible at the deployed level");
		assertEquals("SQS_SEND", record.path("eventType").asText());
		assertEquals("send", record.path("direction").asText());
		assertEquals("RefreshRequest", record.path("messageType").asText());
		assertEquals("host-b", record.path("targetHost").asText());
		assertEquals("us-west-1", record.path("targetRegion").asText());
		assertEquals("izgw_host-b_refresh-requests", record.path("queueName").asText());
	}

	@Test
	void theCredentialIdentifierUsesTheSameFieldNameAsTheAuditLog() throws Exception {
		// ApiKeyAuditLogger writes keyId. One query must return the audit records and
		// these records together. IGDD-3599, design D7.
		when(hostService.getHostsAndRegion()).thenReturn(Map.of("host-a", "us-east-1", "host-b", "us-west-1"));

		Map<String, String> results = new TreeMap<>();
		results.put("us-east-1:host-a", "OK (Local)");
		serviceWith(sqsAccepting()).distribute(
				new RefreshRequest(false, "evt-2", "host-a", "us-east-1", "jti-7", true, null), results);

		JsonNode record = jsonOf("SQS send RefreshRequest");

		assertEquals("jti-7", record.path("keyId").asText());
		assertTrue(record.path("jti").isMissingNode(), "the field is named keyId, not jti");
	}

	// ---------- Task 6.2: a receive is recorded at a level that is actually emitted

	@Test
	void aReceivedReplyIsRecordedAtInfo() throws Exception {
		Message reply = Message.builder().body(RefreshResponse.MESSAGE)
				.messageAttributes(new RefreshResponse("host-b", "us-west-1", "evt-3", "OK").toMap())
				.receiptHandle("rh-1").build();
		when(hostService.getHostsAndRegion()).thenReturn(Map.of("host-a", "us-east-1", "host-b", "us-west-1"));

		Map<String, String> results = new TreeMap<>();
		results.put("us-east-1:host-a", "OK (Local)");
		serviceWith(sqsAccepting(reply)).distribute(
				new RefreshRequest(true, "evt-3", "host-a", "us-east-1", null, false, null), results);

		JsonNode record = jsonOf("SQS receive RefreshResponse");

		assertEquals("INFO", record.path("level").asText(),
				"this record was DEBUG before, so it never appeared in a deployed environment");
		assertEquals("SQS_RECEIVE", record.path("eventType").asText());
		assertEquals("receive", record.path("direction").asText());
		assertEquals("OK", record.path("result").asText());
		assertEquals("OK", results.get("us-west-1:host-b"), "the reply must land in the outcome map");
	}

	@Test
	void aReceivedCommandIsRecordedAtInfo() throws Exception {
		RefreshRequest received = new RefreshRequest(true, "evt-4", "host-a", "us-east-1", null, false, "md");

		serviceWith(sqsAccepting()).handleRefreshRequest(received);

		JsonNode record = jsonOf("Refresh Request");
		assertEquals("INFO", record.path("level").asText());
		assertFalse(record.path("message").asText().equals("Refresh Request"),
				"the format string had no placeholder, so the payload was discarded");
		assertTrue(record.path("message").asText().contains("md"),
				"the record must carry the command detail: " + record.path("message").asText());
	}

	// ---------- The instance that applies a command locally says so

	@Test
	void applyingACommandLocallyIsRecordedWithItsOutcome() throws Exception {
		serviceWith(sqsAccepting()).applyLocally(
				new RefreshRequest(true, "evt-30", "host-a", "us-east-1", "jti-3", false, "md"));

		JsonNode record = jsonOf("Applied command on this instance");

		assertEquals("INFO", record.path("level").asText());
		assertEquals("COMMAND_APPLIED", record.path("eventType").asText());
		assertEquals("evt-30", record.path("eventId").asText());
		assertEquals("md", record.path("destId").asText());
		assertEquals("jti-3", record.path("keyId").asText());
		assertEquals("OK", record.path("result").asText());
		assertTrue(record.path("reset").asBoolean());
	}

	@Test
	void aFailedLocalApplicationRecordsTheFailureNotOk() throws Exception {
		when(endpointStatusService.resetCircuitBreakerById(anyString()))
				.thenThrow(new IllegalStateException("datastore unavailable"));

		serviceWith(sqsAccepting()).applyLocally(
				new RefreshRequest(true, "evt-31", "host-a", "us-east-1", null, false, "md"));

		JsonNode record = jsonOf("Applied command on this instance");

		assertEquals("datastore unavailable", record.path("result").asText());
		assertFalse(record.path("result").asText().startsWith("OK"));
	}

	// ---------- Task 6.3: the reply send is recorded on the replying instance

	@Test
	void theReplyIsRecordedOnTheInstanceThatSendsIt() throws Exception {
		RefreshRequest received = new RefreshRequest(true, "evt-5", "host-a", "us-east-1", null, false, null);

		serviceWith(sqsAccepting()).handleRefreshRequest(received);

		JsonNode record = jsonOf("SQS send RefreshResponse");
		assertEquals("INFO", record.path("level").asText());
		assertEquals("SQS_SEND", record.path("eventType").asText());
		assertEquals("RefreshResponse", record.path("messageType").asText());
		assertEquals("host-a", record.path("targetHost").asText(), "the reply goes back to the sender");
		assertEquals("izgw_host-a_refresh-responses", record.path("queueName").asText());
		assertEquals("OK", record.path("result").asText());
	}

	// ---------- Task 6.5: one summary line closes the operation

	@Test
	void oneSummaryRecordClosesTheOperation() throws Exception {
		when(hostService.getHostsAndRegion())
				.thenReturn(Map.of("host-a", "us-east-1", "host-b", "us-west-1", "host-c", "us-west-1"));

		Map<String, String> results = new TreeMap<>();
		results.put("us-east-1:host-a", "OK (Local)");
		serviceWith(sqsAccepting()).distribute(
				new RefreshRequest(true, "evt-6", "host-a", "us-east-1", null, false, null), results);

		JsonNode record = jsonOf("Distributed operation complete");

		assertEquals("SQS_OPERATION_COMPLETE", record.path("eventType").asText());
		assertEquals("evt-6", record.path("eventId").asText());
		assertEquals(3, record.path("instances").asInt());
		assertEquals(1, record.path("succeeded").asInt(), "only the local instance replied");
		assertEquals(2, record.path("failed").asInt());
		// The summary answers "did every region apply the command" from one record.
		assertEquals("complete", record.path("regions").path("us-east-1").asText());
		assertEquals("failed", record.path("regions").path("us-west-1").asText());
	}

	// ---------- A missing queue is recorded distinctly

	@Test
	void aMissingQueueIsRecordedAsSuchRatherThanAsAFailure() throws Exception {
		when(hostService.getHostsAndRegion()).thenReturn(Map.of("host-a", "us-east-1", "host-gone", "us-west-1"));
		SqsClient sqs = mock(SqsClient.class);
		when(sqs.getQueueUrl(any(Consumer.class))).thenThrow(
				software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException.builder().message("x").build());

		Map<String, String> results = new TreeMap<>();
		results.put("us-east-1:host-a", "OK (Local)");
		serviceWith(sqs).distribute(
				new RefreshRequest(true, "evt-7", "host-a", "us-east-1", null, false, null), results);

		JsonNode record = jsonOf("No SQS queue");
		assertEquals("WARN", record.path("level").asText(), "a retired task is not an error");
		assertEquals(RefreshQueueService.NO_QUEUE, record.path("result").asText());
		assertEquals("izgw_host-gone_refresh-requests", record.path("queueName").asText());
	}

	// ---------- One operation is traceable end to end

	@Test
	void oneOperationLeavesASendAReceiveAndASummary() {
		Message reply = Message.builder().body(RefreshResponse.MESSAGE)
				.messageAttributes(new RefreshResponse("host-b", "us-west-1", "evt-8", "OK").toMap())
				.receiptHandle("rh-2").build();
		when(hostService.getHostsAndRegion()).thenReturn(Map.of("host-a", "us-east-1", "host-b", "us-west-1"));

		Map<String, String> results = new TreeMap<>();
		results.put("us-east-1:host-a", "OK (Local)");
		serviceWith(sqsAccepting(reply)).distribute(
				new RefreshRequest(true, "evt-8", "host-a", "us-east-1", null, false, null), results);

		// Before this change a healthy run produced none of these three.
		assertTrue(anyRecordContains("SQS send RefreshRequest"), "the send must be visible");
		assertTrue(anyRecordContains("SQS receive RefreshResponse"), "the reply must be visible");
		assertTrue(anyRecordContains("Distributed operation complete"), "the summary must be visible");
	}
}
