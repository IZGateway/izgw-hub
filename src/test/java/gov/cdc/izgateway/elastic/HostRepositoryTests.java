package gov.cdc.izgateway.elastic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

import org.junit.jupiter.api.Test;

import gov.cdc.izgateway.configuration.AppProperties;

/**
 * Tests for instance discovery (IGDD-3599).
 * <p>
 * A command that goes to every instance is only as honest as this list. These tests
 * cover the three answers the list can give: the instances, no instances, and a failed
 * read. No test reaches Elasticsearch: the connection is a mock.
 * </p>
 */
class HostRepositoryTests {

	/** Two instances in two regions, in the shape the aggregation query returns. */
	private static final String TWO_HOSTS = """
			{"aggregations":{"0":{"buckets":[
			  {"key":"host-a","1":{"buckets":[{"key":"us-east-1"}]}},
			  {"key":"host-b","1":{"buckets":[{"key":"us-west-1"}]}}
			]}}}""";

	/** A valid response that reports no instance, which is a valid answer. */
	private static final String NO_HOSTS = "{\"aggregations\":{\"0\":{\"buckets\":[]}}}";

	/** HTTP 200 with a body that is not the shape this query returns. */
	private static final String WRONG_SHAPE = "{\"error\":{\"type\":\"search_phase_execution_exception\"}}";

	/** HTTP 200 with a body that is not JSON at all. */
	private static final String NOT_JSON = "<html><body>502 Bad Gateway</body></html>";

	private static HostRepository repositoryReturning(String body) throws Exception {
		HttpsURLConnection connection = mock(HttpsURLConnection.class);
		when(connection.getOutputStream()).thenReturn(new ByteArrayOutputStream());
		when(connection.getResponseCode()).thenReturn(200);
		when(connection.getInputStream())
				.thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
		return repositoryOn(connection);
	}

	private static HostRepository repositoryOn(HttpsURLConnection connection) throws Exception {
		ElasticConfiguration config = mock(ElasticConfiguration.class);
		when(config.isConfigured()).thenReturn(true);
		when(config.getConnection()).thenReturn(connection);
		AppProperties appConfig = mock(AppProperties.class);
		when(appConfig.getServerName()).thenReturn("dev.izgateway.org");
		return new HostRepository(config, appConfig);
	}

	@Test
	void aValidResponseListsEveryInstanceAndItsRegion() throws Exception {
		Map<String, String> hosts = repositoryReturning(TWO_HOSTS).getHostsAndRegion();

		assertEquals(Map.of("host-a", "us-east-1", "host-b", "us-west-1"), hosts);
	}

	@Test
	void aResponseWithNoInstanceReadsAsAnEmptyList() throws Exception {
		// A successful read that reports nothing is a valid answer. It must not read as
		// a failure, or every quiet environment would report one.
		assertTrue(repositoryReturning(NO_HOSTS).getHostsAndRegion().isEmpty());
	}

	@Test
	void aResponseOfTheWrongShapeIsAFailedReadRatherThanAShortList() throws Exception {
		// HTTP 200 with a body this query does not recognise. parseResult used to log
		// this and return whatever it had, so a fan-out sent to an incomplete list and
		// reported success.
		HostRepository repository = repositoryReturning(WRONG_SHAPE);

		assertThrows(HostDiscoveryException.class, repository::getHostsAndRegion);
	}

	@Test
	void aResponseThatIsNotJsonIsAFailedRead() throws Exception {
		HostRepository repository = repositoryReturning(NOT_JSON);

		assertThrows(HostDiscoveryException.class, repository::getHostsAndRegion);
	}

	@Test
	void aConnectionFailureIsAFailedRead() throws Exception {
		ElasticConfiguration config = mock(ElasticConfiguration.class);
		when(config.isConfigured()).thenReturn(true);
		when(config.getConnection()).thenThrow(new IOException("elastic unreachable"));
		AppProperties appConfig = mock(AppProperties.class);
		when(appConfig.getServerName()).thenReturn("dev.izgateway.org");
		HostRepository repository = new HostRepository(config, appConfig);

		assertThrows(HostDiscoveryException.class, repository::getHostsAndRegion);
	}

	@Test
	void anUnconfiguredInstanceReadsAnEmptyListWithoutFailing() {
		// Host reporting switched off is a deployment choice rather than a fault.
		ElasticConfiguration config = mock(ElasticConfiguration.class);
		when(config.isConfigured()).thenReturn(false);
		AppProperties appConfig = mock(AppProperties.class);
		when(appConfig.getServerName()).thenReturn("dev.izgateway.org");

		assertTrue(new HostRepository(config, appConfig).getHostsAndRegion().isEmpty());
	}
}
