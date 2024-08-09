package gov.cdc.izgateway.elastic;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.FastDateFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;

import gov.cdc.izgateway.common.Constants;
import gov.cdc.izgateway.db.model.EndpointStatus;
import gov.cdc.izgateway.soap.fault.FaultSupport;
import gov.cdc.izgateway.soap.fault.MessageSupport;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IEndpointStatus;
import gov.cdc.izgateway.repository.EndpointStatusRepository;
import gov.cdc.izgateway.service.IDestinationService;
import gov.cdc.izgateway.utils.SystemUtils;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.HttpsURLConnection;

/**
 * A repository collecting data from a Search of an Elastic index.
 * 
 * Technically, this is a @@Repository rather than a @@Component, but Spring wraps 
 * repositories with proxies that make it harder to debug the code, and they
 * aren't needed here.
 * 
 * @author Audacious Inquiry
 */
@Slf4j
@Component
public class ElasticStatusRepository extends ElasticRepository implements EndpointStatusRepository {
	private static final Duration QUARTER_HOUR = Duration.ofMinutes(15);
	private static final String STATUS_QUERY = "statusquery.json";
	private static final FastDateFormat FORMATTER = FastDateFormat.getInstance(Constants.TIMESTAMP_FORMAT);
	
	private final ObjectMapper mapper = new ObjectMapper();
	private Map<String, IEndpointStatus> cache = new ConcurrentHashMap<>();
    @Value("${hub.statuscheck.maxfailures:3}")
	private int maxFailuresBeforeCircuitBreaker;
    @Value("${hub.statuscheck.period:5}")
    private int statusCheckPeriodInMinutes;
    
	private final IDestinationService destinationService;

	/**
	 * Create a new ElasticStatusRepository
	 * 
	 * @param config The configuration for the elastic search service
	 * @param destinationService	The destination service this works with
	 */
	@Autowired
	public ElasticStatusRepository(ElasticConfiguration config, IDestinationService destinationService) {
		super(config, STATUS_QUERY);
		if (!config.isConfigured()) {
			log.warn("Status checking not configured with ElasticSearch endpoint, API Key or index");
		}
		this.destinationService = destinationService;
	}

	@Override
	public List<IEndpointStatus> findAll() {
		return find(1, INCLUDE_ALL);
	}
	
	@Override
	public List<IEndpointStatus> find(int maxQuarterHours, String[] include) {
		if (cache.isEmpty()) {
			refresh();
		}
		if (maxQuarterHours < 0) {
			maxQuarterHours = 1;
		} else if (maxQuarterHours > 4) {
			maxQuarterHours = 4;
		}
		List<IEndpointStatus> l = new ArrayList<>();
		if (include.length == 0) {
			l.addAll(cache.values());
		} else {
			for (String inc: include) {
				IEndpointStatus s = cache.get(inc);
				if (s != null) {
					l.add(s);
				}
			}
		}
		// Drop fractional part of 15 minutes from time
		long from = (System.currentTimeMillis() / QUARTER_HOUR.toMillis()) * QUARTER_HOUR.toMillis(); 
		while (--maxQuarterHours > 0) {
			try {
				l.addAll(getData(new Date(from), include).values());
			} catch (NoSuchAlgorithmException | IOException e) {
				// Ignore this, error is already logged.
			}
			from -= QUARTER_HOUR.toMillis();
		}
		
		return l;
	}

	@Override
	public IEndpointStatus findById(String id) {
		if (cache.isEmpty()) {
			refresh();
		}
		return cache.get(id);
	}
	
	/**
	 * Request status from ElasticSearch
	 * @return  true if refreshed, false otherwise
	 */
	public boolean refresh() {
		boolean refreshed = false;
		try {
			Map<String, IEndpointStatus> m = getData(new Date(), INCLUDE_ALL);
			// Add any values for which we previously had a status, but we didn't compute one.
			// These at least keeps the system safe from a complete failure to track status
			// if it cannot reach the data in the repository (ElasticSearch).
			cache.values().forEach(s -> m.computeIfAbsent(s.getDestId(), (String j) -> s));
			// And replace the map.
			cache = m;
			refreshed = true;
		} catch (IOException | NoSuchAlgorithmException e) {
			log.error("Exception initializing status service: {}", e.getMessage(), e);
		}
		return refreshed;
	}
	
	private Map<String, IEndpointStatus> getData(Date from, String[] include) throws IOException, NoSuchAlgorithmException {
		
		if (config.getUrl() == null) {
			return new ConcurrentHashMap<>(); 
		}
		String request = getRequest(from, include);
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
		} catch (IOException e) {  // NOSONAR Exception handling is OK
			log.error("IOException getting destination status: {}", e.getMessage());
			throw e;
		} catch (NoSuchAlgorithmException e) { // NOSONAR Exception handling is OK
			log.error("TLS Configuration Exception getting destination status: {}", e.getMessage());
			throw e;
		}
	}

	/**
	 * Data Collector for parsing ElasticSearch response.
	 */
	static class ParsedResponse {
		String destinationId;
		long timeRange;
		int[] count = { 0, 0 };
		long[] minTimes = { 0, 0 };
		long[] maxTimes = { 0, 0 };

		private String url;
		private String faultCode;
		private String faultName;
		private String errorSummary;
		private String errorDetail;
		private String destVersion;
		private String tag;
		public ParsedResponse(long histogramTime, String destId, int count, boolean hasError, long latestTxTime, long firstTxTime) {
			timeRange = histogramTime;
			destinationId = destId;
			this.count[hasError ? 1 : 0] = count;
			minTimes[hasError ? 1 : 0] = firstTxTime;
			maxTimes[hasError ? 1 : 0] = latestTxTime;
		}
		
		public boolean updateResponse(long histogramTime, int count, boolean hasError, long latestTxTime, long firstTxTime, int maxFailures) {
			// Accumulate errors if there aren't enough already
			if (histogramTime < timeRange && hasError && this.count[1] < maxFailures) {
				timeRange = histogramTime;
				this.count[1] += count;
				minTimes[1] = firstTxTime;
				maxTimes[1] = latestTxTime;
				return true;
			} else if (histogramTime > timeRange && count > 0) {
				timeRange = histogramTime;
				this.count[hasError ? 1 : 0] = count;
				minTimes[hasError ? 1 : 0] = firstTxTime;
				maxTimes[hasError ? 1 : 0] = latestTxTime;
				return true;
			}
			return false;
		}
		
		/**
		 * Compute whether the destination is available based on parsed response.
		 * A destination is available if its most recent positive status occurs after its most recent
		 * negative status.
		 * 
		 * @return	true if the destination appears to be available, false otherwise.
		 */
		public boolean isAvailable() {
			return maxTimes[0] > maxTimes[1];
		}
		
		public boolean isCircuitBroken(int threshold) {
			// If there are no successes, or the time of the last success is before the time of the last failure
			if (this.count[0] == 0 || this.maxTimes[0] < this.minTimes[1]) {
				return this.count[1] >= threshold;
			}
			return false;
		}

		public long getMaxTime() {
			return Math.max(maxTimes[0], maxTimes[1]);
		}

		public void setFaultCode(String value) {
			faultCode = value;
		}
		public String getFaultCode() {
			return faultCode;
		}

		public String getFaultName() {
			return faultName;
		}

		public void setFaultName(String value) {
			faultName = value;
		}

		public String getUrl() {
			return url;
		}

		public void setUrl(String value) {
			url = value;
		}

		public String getErrorSummary() {
			return errorSummary;
		}
		
		public void setErrorSummary(String value) {
			errorSummary = value;
		}

		public String getErrorDetail() {
			return errorDetail;
		}

		public void setErrorDetail(String value) {
			errorDetail = value;
		}

		public String getDestVersion() {
			return destVersion;
		}
		
		public void setDestVersion(String value) {
			destVersion = value;
		}
		
		public String getTag() {
			return tag;
		}
		
		public void setTag(String value) {
			tag = value;
		}

	}
	
	/**
	 * Parse aggregate status data from ElasticSearch into endpoint status.
	 * 
	 * Look at recent status to see if any destinations are failing, and if so, throw the circuit breaker on them.
	 * Look at older status to see if circuit breaker was thrown or should be thrown.
	 * 
	 * Anything that has had the circuit breaker thrown on it should be rechecked every few minutes, and if it's running again, the circuit breaker should be reset.
	 * Anything worth looking at that is failing more than three times in a row should have the circuit breaker thrown.
	 * @param result	The query result from ElasticSearch.  This is a JSON String in the form
	 * 					of a response with multiple aggregates.
	 * 					rawResponse.aggregations.histogram: This aggregate tracks the timestamp associated with the histogram buckets.
	 * 						histogram.buckets[].key is a long value giving the start of the histogram bucket.
	 * 					destination: This aggregate collects counts by destination identifier.
	 * 						histogram.buckets[].destination.buckets[].key is a string giving the destination identifier.
	 * 					destination.buckets[].hasProcessError: This aggregate collects counts by hasProcessError value.
	 * 						hasProcessError.buckets[].key_as_string is a string giving the value of hasProcessError.
	 * 					hasProcessError.buckets[].latestTxTime: This aggregate reports the last transaction time for the destination.
	 * 						latestTxTime.buckets[].key is a long value giving the time of the last transaction to this destination with the given status.
	 * 					hasProcessError.buckets[].firstTxTime: This aggregate reports the last transaction time for the destination.
	 * 						firstTxTime.buckets[].key is a long value giving the time of the last transaction to this destination with the given status.
	 * @return
	 */
	private Map<String, IEndpointStatus> parseResult(String result) {
		Map<String, ParsedResponse> map = new ConcurrentHashMap<>();
		try {
			JsonNode node = getBucket(result);
			if (node.getNodeType() != JsonNodeType.ARRAY) {
				log.error("Expected array at rawResponse.aggregations.histogram.buckets");
				throw new IllegalStateException("Array expected at rawResponse.aggregations.histogram.buckets");
			} else {
				parseHistogram(map, node);
			}
		} catch (Exception e ) {
			log.error(Markers2.append(e), "Error reading status response from ElasticSearch: {}", e.getMessage());
			return cache;
		}
		Map<String, IEndpointStatus> newCache = new HashMap<>();
		for (ParsedResponse r: map.values()) {
			IEndpointStatus s = convertToStatus(r);
            if ( s != null) {
            	newCache.put(s.getDestId(), s);
            }
		}
		return newCache;
	}

	private JsonNode getBucket(String result) throws JsonProcessingException {
		JsonNode node = mapper.readTree(result);
		try {
			node = getNode("rawResponse.aggregations.histogram.buckets", node);
		} catch (IllegalArgumentException e) {
			node = getNode("aggregations.histogram.buckets", node);
		}
		return node;
	}

	private IEndpointStatus convertToStatus(ParsedResponse r) {
		IEndpointStatus s = newEndpointStatus();

        IDestination d = destinationService.findByDestId(r.destinationId);
        if (d == null) {
        	// This can occur when status result from elastic has a destination in
        	// it that is no longer active.
            log.warn("Destination not found for {}.", r.destinationId);
            return null;
        }

		s.setDestId(r.destinationId);
		s.setJurisdictionId(d.getJurisdictionId());
		if (r.isAvailable()) {
			s.setStatus(IEndpointStatus.CONNECTED);
		} else if (r.isCircuitBroken(maxFailuresBeforeCircuitBreaker)) {
			s.setStatus(IEndpointStatus.CIRCUIT_BREAKER_THROWN);
		} else {
			s.setStatus(r.getErrorSummary());
		}
		s.setStatusAt(new Date(r.getMaxTime()));
		s.setStatusBy(SystemUtils.getHostname());
		s.setDestUri(r.getUrl());
		s.setDestVersion(r.getDestVersion());
		s.setDetail(r.getErrorDetail());
		s.setDiagnostics(getDiagnostics(r.getFaultCode(), r.getFaultName()));
		return s;
	}
	
    /**
     *	Bridges the diagnostic lookup in the repository.
     * @param faultName The name of the fault
     * @param faultCode The code for the fault
     * @return The diagnostic for the fault
     */
    public String getDiagnostics(String faultName, String faultCode) {
        FaultSupport s = MessageSupport.getTemplate(faultCode, faultName);
        return s == null ? null : s.getDiagnostics();
    }

	private void parseHistogram(Map<String, ParsedResponse> map, JsonNode node) {
		int errors = 0;
		int destinationErrors = 0;
		for (int i = node.size() - 1; i >= 0; --i) {
			JsonNode histBucket = node.get(i);
			long histogramTime = histBucket.get("key").asLong();
			if (histogramTime == 0) {
				// Key does not exist.
				continue;
			}
			JsonNode dests = getNode("destination.buckets", histBucket);
			if (dests.getNodeType() != JsonNodeType.ARRAY) {
				if (errors++ == 0) {	// Report the first error of this type
					log.error("Expected array at destination.buckets");
				}
				continue;
			}
			destinationErrors = parseDestination(map, destinationErrors, node, histogramTime, dests);
		}
	}

	private int parseDestination(Map<String, ParsedResponse> map, int errors, JsonNode node, long histogramTime,
			JsonNode dests) {
		for (JsonNode destBucket: dests) {
			String destId = destBucket.get("key").asText();
			if (StringUtils.isEmpty(destId)) {
				continue;
			}
			JsonNode statii = getNode("hasProcessError.buckets", destBucket);
			if (node.getNodeType() != JsonNodeType.ARRAY) {
				if (errors++ == 0) {	// Report the first error of this type
					log.error("Expected array at hasProcessError.buckets");
				}
				continue;
			}
			errors = parseStatus(map, errors, histogramTime, destId, statii);
		}
		return errors;
	}

	private int parseStatus( // NOSONAR Cognitive complexity OK
		Map<String, ParsedResponse> map, int errors, long histogramTime, String destId, JsonNode statii
	) {  
		for (JsonNode statusBucket: statii) {
			String hasErrorStr = statusBucket.get("key_as_string").asText();
			if (StringUtils.isEmpty(hasErrorStr)) {
				// Missing value
				if (errors++ == 0) {
					log.error("Missing boolean value at key_as_string");
				}
				continue;
			}
			boolean hasError = statusBucket.get("key_as_string").asBoolean();
			if (!hasErrorStr.equalsIgnoreCase(Boolean.toString(hasError))) {
				// Not really a boolean value.
				if (errors++ == 0) {
					log.error("Expected boolean at key_as_string, got {}", hasErrorStr);
				}
				continue;
			}
			int count = statusBucket.get("doc_count").asInt();
			if (count == 0) {
				// Either there are no documents (no error, no foul),
				// or the count field is missing.
				continue;
			}
			JsonNode n = getNode("latestTxTime.value", statusBucket);
			long latestTxTime = n.asLong();
			if (latestTxTime == 0 && errors++ == 0 && log.isErrorEnabled()) {
				log.error("latestTxTime.value invalid: {}", n.asText());
			}
			n = getNode("firstTxTime.value", statusBucket);
			long firstTxTime = n.asLong();
			if (firstTxTime == 0 && errors++ == 0 && log.isErrorEnabled()) {
				log.error("firstTxTime.value invalid: {}", n.asText());
			}
			
			ParsedResponse p = map.get(destId);
			// If there's no records matching this time frame, status, and destination, don't bother updating status.
			if (count != 0) {
				// If there's no original record, create one.
				boolean update = true;
				if (p == null) {
					p = new ParsedResponse(histogramTime, destId, count, hasError, latestTxTime, firstTxTime);
					map.put(destId, p);
				} else {
					// Otherwise update the existing one.
					update = p.updateResponse(histogramTime, count, hasError, latestTxTime, firstTxTime, maxFailuresBeforeCircuitBreaker);
				}
				if (update) {
					parseExtraValues(statusBucket, p);
				}
			}
		}
		return errors;
	}

	/**
	 * Get all of the other metadata about the status, the url, faultCode, faultName, et cetera.
	 * @param statusBucket	The bucket to parse these values from
	 * @param p	The parsed response to update with the values.
	 */
	private void parseExtraValues(JsonNode statusBucket, ParsedResponse p) {
		String value = getTopValue("url", statusBucket);
		if (!StringUtils.isEmpty(value)) {
			p.setUrl(value);
		}
		value = getTopValue("faultCode", statusBucket);
		if (!StringUtils.isEmpty(value)) {
			p.setFaultCode(value);
		}
		value = getTopValue("faultName", statusBucket);
		if (!StringUtils.isEmpty(value)) {
			p.setFaultName(value);
		}
		value = getTopValue("errorDetail", statusBucket);
		if (!StringUtils.isEmpty(value)) {
			p.setErrorDetail(value);
		}
		value = getTopValue("errorSummary", statusBucket);
		if (!StringUtils.isEmpty(value)) {
			p.setErrorSummary(value);
		}
		value = getTopValue("destVersion", statusBucket);
		if (!StringUtils.isEmpty(value)) {
			p.setDestVersion(value);
		}
		value = getTopValue("tags", statusBucket);
		if (!StringUtils.isEmpty(value)) {
			p.setTag(value);
		}
	}

	/**
	 * Get the top value of a bucket
	 * @param key	The name of the value being retrieved.
	 * @param statusBucket	The bucket to retrieve values from
	 * @return	The string value associated with the bucket.
	 */
	private String getTopValue(String key, JsonNode statusBucket) {
		String find = key + "-bucket." + key + "-metric.top.1.metrics";
		try {
			JsonNode node = getNode(find, statusBucket);
			return node.fields().next().getValue().asText();
		} catch (NoSuchElementException | IllegalArgumentException e) {
			log.warn("Could not find {}", find);
			return null;
		}
	}

	/**
	 * Compute the request we need to submit from the template and return it as a String.
	 * @param now 
	 * @param include 
	 * @return	The request that needs to be submitted to elastic.
	 */
	private String getRequest(Date now, String[] include) {
		Calendar cal = new GregorianCalendar();
		cal.setTime(now);
		// Look back twice as far as we need to in case the server checking before us dropped out of the rota
		cal.add(Calendar.MINUTE, -2 * statusCheckPeriodInMinutes);
		Date startTime = cal.getTime();
		Map<String, String> map = new HashMap<>();
		map.put("start", FORMATTER.format(startTime));
		map.put("end", FORMATTER.format(now));
		map.put("startLong", Long.toString(startTime.getTime()));
		map.put("endLong", Long.toString(now.getTime()));
		map.put("environment", SystemUtils.getDestTag());
		map.put("include", include.length == 0 ? "" : computeIncludeString(include));
		return populateTemplate(map);
	}

	private String computeIncludeString(String[] include) {
		StringBuilder b = new StringBuilder();
		b.append(",\"include\": [ ");
		for (String inc: include) {
			b.append("\"").append(inc).append("\", ");
		}
		// Remove trailing ", " at end 
		b.setLength(b.length() -2);
		b.append("]");
		return b.toString();
	}

	@Override
	public IEndpointStatus saveAndFlush(IEndpointStatus status) {
		if (status != null) {
			cache.put(status.getDestId(), status);
		}
		return status;
	}

	@Override
	public boolean removeById(String id) {
		return cache.remove(id) != null;
	}

	@Override
	public IEndpointStatus newEndpointStatus() {
		return new EndpointStatus();
	}

	@Override
	public IEndpointStatus newEndpointStatus(IDestination dest) {
		// TODO Auto-generated method stub
		return new EndpointStatus(dest);
	}
}
