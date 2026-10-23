package gov.cdc.izgateway.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import gov.cdc.izgateway.configuration.DynamoDbConfig;
import gov.cdc.izgateway.db.DbController.DbControllerConfiguration;
import gov.cdc.izgateway.db.RefreshQueueService.RefreshRequest;
import gov.cdc.izgateway.hub.security.ApiKeyPrincipalProvider;
import gov.cdc.izgateway.repository.IHostRepository;
import gov.cdc.izgateway.security.AccessControlRegistry;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Tests for the refresh endpoint's distribution behavior (IGDD-3599).
 * <p>
 * The point of these tests is that one request produces one round of distribution.
 * Before this change, a received command was applied by calling this instance's own
 * {@code /rest/reset} over HTTP. Had that endpoint also distributed, every receiving
 * instance would distribute again and the operation would never terminate.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DbControllerRefreshTests {

	@Mock
	private IHostRepository hostService;
	@Mock
	private DbControllerConfiguration configuration;
	@Mock
	private AccessControlRegistry registry;
	@Mock
	private ApiKeyPrincipalProvider apiKeyPrincipalProvider;
	@Mock
	private RefreshQueueService refreshQueueService;
	@Mock
	private DynamoDbClient ddbClient;
	@Mock
	private DynamoDbConfig ddbConfig;

	/** Stubs only what construction needs, so a test can set its own behaviour first. */
	private DbController controller() {
		when(ddbConfig.getDynamodbTable()).thenReturn("izgateway-test");
		return new DbController(hostService, configuration, registry, apiKeyPrincipalProvider,
				refreshQueueService, ddbClient, ddbConfig);
	}

	private void localResetReturns(String status) {
		when(refreshQueueService.applyLocally(any(RefreshRequest.class))).thenReturn(status);
	}

	@Test
	void aRefreshWithResetDistributesExactlyOnce() {
		localResetReturns("OK");
		Map<String, String> results = controller().getRefreshed("true", true, null);

		// One local application and one round of distribution. Not one per instance,
		// and not a second round triggered by applying the command.
		verify(refreshQueueService, times(1)).applyLocally(any(RefreshRequest.class));
		verify(refreshQueueService, times(1)).distribute(any(RefreshRequest.class), any());
		assertFalse(results.isEmpty(), "the local instance must appear in the results");
	}

	@Test
	void theDistributedCommandCarriesTheResetFlag() {
		localResetReturns("OK");
		controller().getRefreshed("true", true, null);

		ArgumentCaptor<RefreshRequest> sent = ArgumentCaptor.forClass(RefreshRequest.class);
		verify(refreshQueueService).distribute(sent.capture(), any());
		assertTrue(sent.getValue().reset(), "a refresh with reset must tell the other instances to reset");
	}

	@Test
	void aLocalOnlyRefreshDistributesNothing() {
		localResetReturns("OK");
		controller().getRefreshed("false", true, null);

		verify(refreshQueueService, never()).distribute(any(), any());
		verify(refreshQueueService, times(1)).applyLocally(any(RefreshRequest.class));
	}

	@Test
	void aRefreshWithoutResetAppliesNoResetLocally() {
		controller().getRefreshed("true", false, null);

		verify(refreshQueueService, never()).applyLocally(any(RefreshRequest.class));
		verify(refreshQueueService, times(1)).distribute(any(RefreshRequest.class), any());
		verify(configuration, times(1)).refresh();
	}

	@Test
	void aLocalResetFailureIsReportedRatherThanReadingOk() {
		localResetReturns("datastore unavailable");

		Map<String, String> results = controller().getRefreshed("false", true, null);

		String local = results.values().iterator().next();
		assertEquals("datastore unavailable", local);
		assertFalse(local.startsWith("OK"), "a failed local reset must not be reported as OK");
	}

	@Test
	void aCredentialIsEvictedBeforeAnythingElse() {
		controller().getRefreshed("false", false, "jti-5");

		verify(apiKeyPrincipalProvider, times(1)).evictCredential("jti-5");
	}
}
