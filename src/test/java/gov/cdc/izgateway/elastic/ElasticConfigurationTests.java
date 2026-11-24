package gov.cdc.izgateway.elastic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.MalformedURLException;
import java.net.URL;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Tests for the URL that ElasticConfiguration builds (IGDD-2974).
 * <p>
 * getUrl() changed from the deprecated new URL(String) to new URI(String).toURL().
 * These tests show that a valid configuration gives the same URL as before, and that
 * an invalid configuration still gives MalformedURLException, which the callers handle.
 * </p>
 */
class ElasticConfigurationTests {

	private static ElasticConfiguration config(String apiKey, String host, String index) {
		ElasticConfiguration config = new ElasticConfiguration();
		ReflectionTestUtils.setField(config, "apiKey", apiKey);
		ReflectionTestUtils.setField(config, "hostName", host);
		ReflectionTestUtils.setField(config, "indexName", index);
		return config;
	}

	@SuppressWarnings("deprecation") // The old constructor is the reference for the comparison.
	private static URL oldUrl(String host, String index) throws MalformedURLException {
		return new URL(host + "/" + index + "/_search");
	}

	@Test
	void validHostWithPortGivesTheSameUrlAsBefore() throws Exception {
		String host = "https://audacioussearchtest.es.us-east-1.aws.found.io:9243";
		String index = "izgw-dev-logstash";
		URL url = config("key", host, index).getUrl();
		assertEquals(oldUrl(host, index).toString(), url.toString());
		assertEquals("audacioussearchtest.es.us-east-1.aws.found.io", url.getHost());
		assertEquals(9243, url.getPort());
		assertEquals("/izgw-dev-logstash/_search", url.getPath());
	}

	@Test
	void indexPatternGivesTheSameUrlAsBefore() throws Exception {
		String host = "https://elastic.example.org";
		String index = "izgw-*-logstash,other-index";
		assertEquals(oldUrl(host, index).toString(), config("key", host, index).getUrl().toString());
	}

	@Test
	void hostWithoutSchemeThrowsMalformedUrlException() {
		ElasticConfiguration config = config("key", "elastic.example.org", "index");
		assertThrows(MalformedURLException.class, config::getUrl);
		assertFalse(config.isConfigured());
	}

	@Test
	void hostWithSpaceThrowsMalformedUrlException() {
		ElasticConfiguration config = config("key", "https://elastic example.org", "index");
		assertThrows(MalformedURLException.class, config::getUrl);
		assertFalse(config.isConfigured());
	}

	@Test
	void completeConfigurationIsConfigured() {
		assertTrue(config("key", "https://elastic.example.org", "index").isConfigured());
	}

	@Test
	void missingApiKeyIsNotConfigured() {
		assertFalse(config("", "https://elastic.example.org", "index").isConfigured());
	}
}
