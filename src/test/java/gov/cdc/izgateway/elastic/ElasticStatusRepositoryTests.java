package gov.cdc.izgateway.elastic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

import org.junit.jupiter.api.Test;

import gov.cdc.izgateway.service.IDestinationService;

/**
 * Tests for the status reads behind a circuit breaker reset (IGDD-3599).
 * <p>
 * A reset clears breakers held in the status cache. These tests cover what the
 * repository does when it cannot fill that cache. No test reaches Elasticsearch: the
 * connection is a mock.
 * </p>
 */
class ElasticStatusRepositoryTests {

	/**
	 * A response with no status records, which is a valid answer.
	 * <p>
	 * The path has to be the one {@code statusquery.json} aggregates on, {@code
	 * histogram}. A body of any other shape makes the parser raise, which is a failed
	 * read and a different test.
	 * </p>
	 */
	private static final String EMPTY_RESPONSE =
			"{\"aggregations\":{\"histogram\":{\"buckets\":[]}}}";

	/** HTTP 200 with a body that is not the shape the status query returns. */
	private static final String WRONG_SHAPE = "{\"aggregations\":{\"0\":{\"buckets\":[]}}}";

	private static ElasticConfiguration configuredWith(HttpsURLConnection connection) throws Exception {
		ElasticConfiguration config = mock(ElasticConfiguration.class);
		when(config.isConfigured()).thenReturn(true);
		when(config.getUrl()).thenReturn(URI.create("https://elastic.test/index/_search").toURL());
		when(config.getConnection()).thenReturn(connection);
		return config;
	}

	private static HttpsURLConnection connectionReturning(String body) throws Exception {
		HttpsURLConnection connection = mock(HttpsURLConnection.class);
		when(connection.getOutputStream()).thenReturn(new ByteArrayOutputStream());
		when(connection.getResponseCode()).thenReturn(200);
		when(connection.getInputStream())
				.thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
		return connection;
	}

	private static ElasticStatusRepository repositoryOn(ElasticConfiguration config) {
		return new ElasticStatusRepository(config, mock(IDestinationService.class));
	}

	@Test
	void aResetOfEveryDestinationRaisesWhenTheStatusCannotBeRead() throws Exception {
		// refresh() reports a read failure by returning false and leaving the cache
		// empty. Ignoring that cleared nothing and reported OK, so a region could read
		// complete while every breaker stayed thrown.
		ElasticConfiguration config = mock(ElasticConfiguration.class);
		when(config.isConfigured()).thenReturn(true);
		when(config.getUrl()).thenReturn(URI.create("https://elastic.test/index/_search").toURL());
		when(config.getConnection()).thenThrow(new IOException("elastic unreachable"));

		StatusUnavailableException thrown = assertThrows(StatusUnavailableException.class,
				() -> repositoryOn(config).resetCircuitBreakers());

		assertTrue(thrown.getMessage().contains("status"), "the outcome must name what failed");
	}

	@Test
	void aResetOfOneDestinationRaisesWhenTheStatusCannotBeRead() throws Exception {
		// Without this the caller reads null and reports "no status for this
		// destination", which is a different cause with the same wording.
		ElasticConfiguration config = mock(ElasticConfiguration.class);
		when(config.isConfigured()).thenReturn(true);
		when(config.getUrl()).thenReturn(URI.create("https://elastic.test/index/_search").toURL());
		when(config.getConnection()).thenThrow(new IOException("elastic unreachable"));

		assertThrows(StatusUnavailableException.class,
				() -> repositoryOn(config).resetCircuitBreakerById("md"));
	}

	@Test
	void aSuccessfulReadWithNoStatusIsNotAFailure() throws Exception {
		// An instance that holds no status has no thrown breaker, so there is nothing to
		// clear and the reset succeeds. This must stay distinguishable from a failed read.
		ElasticConfiguration config = configuredWith(connectionReturning(EMPTY_RESPONSE));

		assertDoesNotThrow(() -> repositoryOn(config).resetCircuitBreakers());
	}

	@Test
	void anUnconfiguredInstanceReportsNoFailure() {
		// No Elasticsearch endpoint means status checking is switched off. That is a
		// deployment choice rather than a fault, so a reset must not report a failure.
		ElasticConfiguration config = mock(ElasticConfiguration.class);
		when(config.isConfigured()).thenReturn(false);

		assertDoesNotThrow(() -> repositoryOn(config).resetCircuitBreakers());
	}

	@Test
	void aResponseThatCannotBeParsedIsAFailedRead() throws Exception {
		// The status parser used to log a bad response and return the existing cache,
		// which made refresh() report success. A reset of an empty cache then cleared
		// nothing and replied OK, which is the same defect in the method that the fix
		// above depends on.
		ElasticConfiguration config = configuredWith(connectionReturning(WRONG_SHAPE));

		assertThrows(StatusUnavailableException.class,
				() -> repositoryOn(config).resetCircuitBreakers());
	}

	@Test
	void aDestinationWithNoStatusStillReadsAsNoStatus() throws Exception {
		// The null return is the "no status held" answer and must survive, because the
		// reset endpoint answers 404 from it.
		ElasticConfiguration config = configuredWith(connectionReturning(EMPTY_RESPONSE));

		assertNull(repositoryOn(config).resetCircuitBreakerById("md"));
	}
}
