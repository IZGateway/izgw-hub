package gov.cdc.izgateway.elastic;
import gov.cdc.izgateway.common.Constants;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.repository.IHostRepository;
import gov.cdc.izgateway.utils.SystemUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.time.FastDateFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.HttpsURLConnection;

@Slf4j
@Repository
public class HostRepository extends ElasticRepository implements IHostRepository {

	private final ObjectMapper mapper = new ObjectMapper();
	private static final String HOSTS_QUERY = "hostsquery.json";
	private static final FastDateFormat FORMATTER = FastDateFormat.getInstance(Constants.TIMESTAMP_FORMAT);
	
	public HostRepository(@Autowired ElasticConfiguration config) {
		super(config, HOSTS_QUERY);
		if (!config.isConfigured()) {
			log.warn("Host reporting not configured with ElasticSearch endpoint, API Key or index");
		}
	}
	
	@Override
	public List<String> findAll() {
		
		Date from = new Date();
		if (!config.isConfigured()) {
			return Collections.emptyList(); 
		}
		String request = getRequest(from);
		try {
			HttpsURLConnection con = config.getConnection();
			OutputStream os = con.getOutputStream();
			os.write(request.getBytes(StandardCharsets.UTF_8));
			if (con.getResponseCode() == 200) {
				InputStream is = con.getInputStream();
				String result = IOUtils.toString(is, StandardCharsets.UTF_8);
				return parseResult(result);
			} else {
				InputStream err = con.getErrorStream();
				String error = IOUtils.toString(err, StandardCharsets.UTF_8);
				throw new IOException(error);
			}
		} catch (IOException e) {
			log.error(Markers2.append(e), "IOException getting host list: \n{}", e.getMessage());
		} catch (NoSuchAlgorithmException e) {
			log.error(Markers2.append(e), "TLS Error getting host list: \n{}", e.getMessage());
		}
		return Collections.emptyList();
	}
	
	/**
	 * Parse aggregate status data from ElasticSearch into host list.
	 * @return
	 */
	private List<String> parseResult(String result) {
		List<String> hosts = new ArrayList<>();
		try {
			JsonNode node = getNode("aggregations.0.buckets", mapper.readTree(result));
			for (int i = 0; i < node.size(); i++) {
				String value = node.get(i).get("key").asText();
				if (value != null) {
					hosts.add(value);
				}
			}
		} catch (Exception e) {
			log.error(Markers2.append(e), "Error retrieving host list: {}", e.getMessage());
		}
		return hosts;
	}

	/**
	 * Compute the request we need to submit from the template and return it as a String.
	 * @param now 
	 * @param include 
	 * @return	The request that needs to be submitted to elastic.
	 */
	private String getRequest(Date now) {
		Calendar cal = new GregorianCalendar();
		cal.setTime(now);
		// Look back three minutes.
		cal.add(Calendar.MINUTE, -3);
		Date startTime = cal.getTime();
		Map<String, String> map = new HashMap<>();
		map.put("start", FORMATTER.format(startTime));
		map.put("environment", SystemUtils.getDestTag());
		return populateTemplate(map);
	}
}
