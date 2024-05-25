package gov.cdc.izgateway.elastic;

import java.io.IOException;
import java.net.MalformedURLException;
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

@Configuration
public class ElasticConfiguration {
	@Value("${elastic.api.key}")
	private String apiKey;
	@Value("${elastic.host:https://audacioussearchtest.es.us-east-1.aws.found.io:9243}")
	private String hostName;
	@Value("${elastic.index:izgw-dev-logstash}")
	private String indexName;
	
	public String getApiKey() {
		return apiKey;
	}
	public String getHostName() {
		return hostName;
	}
	public String getIndexName() {
		return indexName;
	}
	
	public URL getUrl() throws MalformedURLException {
		return new URL(getHostName() + "/" + getIndexName() + "/_search");
	}
	
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