package gov.cdc.izgateway.elastic;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

/**
 * Connection settings for the Elasticsearch index that holds the hub logs.
 * The values come from the elastic.api.key, elastic.host and elastic.index properties.
 */
@Configuration
public class ElasticConfiguration {
	@Value("${elastic.api.key}")
	private String apiKey;
	@Value("${elastic.host:https://audacioussearchtest.es.us-east-1.aws.found.io:9243}")
	private String hostName;
	@Value("${elastic.index:izgw-dev-logstash}")
	private String indexName;
	
	/**
	 * Creates the configuration. Spring sets the fields from the properties.
	 */
	public ElasticConfiguration() {
		// Spring sets the fields.
	}

	/**
	 * Gets the API key for Elasticsearch.
	 * @return the API key
	 */
	public String getApiKey() {
		return apiKey;
	}
	/**
	 * Gets the base URL of the Elasticsearch host, with the scheme and the port.
	 * @return the host URL
	 */
	public String getHostName() {
		return hostName;
	}
	/**
	 * Gets the name of the index (or index pattern) to search.
	 * @return the index name
	 */
	public String getIndexName() {
		return indexName;
	}
	
	/**
	 * Gets the URL of the search endpoint of the index.
	 * @return the URL host/index/_search
	 * @throws MalformedURLException if the host and the index do not make a valid absolute URL
	 */
	public URL getUrl() throws MalformedURLException {
		String url = getHostName() + "/" + getIndexName() + "/_search";
		try {
			// new URL(String) is deprecated (IGDD-2974).
			return new URI(url).toURL();
		} catch (URISyntaxException | IllegalArgumentException e) {
			// Keep the exception type that the callers handle.
			MalformedURLException ex = new MalformedURLException("Invalid Elasticsearch URL: " + e.getMessage());
			ex.initCause(e);
			throw ex;
		}
	}
	
	/**
	 * Checks that the API key, the host and the index are set, and that they make a valid URL.
	 * @return true if the configuration is complete
	 */
	public boolean isConfigured() {
		if (StringUtils.isAnyEmpty(getApiKey(), getHostName(), getIndexName())) {
			return false;
		}

		try {
			return getUrl() != null;
		} catch (MalformedURLException e) {
			return false;
		}
	}
	
	/**
	 * Opens a POST connection to the search endpoint, with the API key in the Authorization header.
	 * @return the open connection
	 * @throws IOException if the connection cannot be opened
	 * @throws NoSuchAlgorithmException if the default SSL context is not available
	 */
	public HttpsURLConnection getConnection() throws IOException, NoSuchAlgorithmException {
		HttpsURLConnection con = (HttpsURLConnection) getUrl().openConnection();
		con.setRequestMethod("POST");
		con.setDoInput(true);
		con.setDoOutput(true);
		con.setUseCaches(false);
		con.setSSLSocketFactory(SSLContext.getDefault().getSocketFactory());
		con.addRequestProperty(HttpHeaders.AUTHORIZATION, "ApiKey " + Base64.getEncoder().encodeToString(getApiKey().getBytes(StandardCharsets.UTF_8))); 
		con.addRequestProperty(HttpHeaders.CONTENT_TYPE, "application/json");
		con.setHostnameVerifier((a, b) -> true);  // NOSONAR Using Certificate based validation only
		con.connect();
		return con;
	}
}