package gov.cdc.izgateway.soap.mock.perf;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.http.ResponseEntity;

import gov.cdc.izgateway.logging.event.TransactionData.RequestPayloadType;
import gov.cdc.izgateway.soap.fault.Fault;
import gov.cdc.izgateway.soap.message.SubmitSingleMessageRequest;
import gov.cdc.izgateway.soap.message.SubmitSingleMessageResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;

/**
 * This simulator mocks the behavior of a real IIS, returning RSP messages for a QBP query.
 * It uses test immunization data provided by AIRA during the inception of this project.
 * @author boonek
 *
 */
@Slf4j
public class PerformanceSimulatorMockIIS implements PerformanceSimulatorInterface {
	/** Test data is loaded from a text file containing one RSP message per patient per file line */ 
	private static final Map<String, String> dataById = new LinkedHashMap<>();
	private static final Map<String, List<String>> immunizationData = initMock();

	private static final int QUERY_NAME = 1;
	private static final int QUERY_TAG = 2;
	private static final int PID_LIST = 3;
	private static final int PATIENT_NAME = 4;
	private static final int MOTHERS_MAIDEN_NAME = 5;
	private static final int DATE_OF_BIRTH = 6;
	private static final int PATIENT_SEX = 7;
	private static final int ADDRESS = 8;
	private static final int PATIENT_PHONE = 9;
	// Other values: PATIENT_MBI = 10, PATIENT_MBO = 11, LAST_UPDATED_DATE = 12, and LAST_UPDATED_BY = 13
	
	// PID values used for patient matching.
	private static final int ID_LIST = 3;
	private static final int NAME = 5;
	private static final int MAIDEN_NAME = 6;
	private static final int DOB = 7;
	private static final int SEX = 8;
	private static final int ALIAS = 9;
	private static final int ADDR = 11;
	private static final int HOME_PHONE = 13;
	private static final int WORK_PHONE = 14;
	/** The mock data file resource containing immunization messages uses for the mock */
	public static final String MOCK_DATA_FILE = "RSP-Test-Messages.hl7";

	private static final String MSA_PART = "MSA|AA|{{Original Message Control ID}}\r";
	private static final String QPD_PART = "QPD|{{Original QPD Parts}}";
	private static final String MSH_PART = "MSH|^~\\&amp;|TEST|MOCK|IZGW|IZGW|{{Message Timestamp}}||RSP^K11^RSP_K11|{{Unique Message Identifier}}|P|2.5.1|||NE|NE|||||Z33^CDCPHINVS\r";
	
	private static final String QUERY_NOT_FOUND 
		= MSH_PART
		+ MSA_PART
		+ qakPart("NF")
		+ QPD_PART;
	private static final String QUERY_MULTIPLE
		= MSH_PART
		+ MSA_PART
		+ qakPart("OK")
		+ QPD_PART;
	private static final String QUERY_TOO_MANY
		= MSH_PART
		+ MSA_PART
		+ qakPart("TM")
		+ QPD_PART;
	private static final String QUERY_ERROR
		= "MSH|^~\\&amp;|TEST|MOCK|IZGW|IZGW|{{Message Timestamp}}||ACK^Q11^ACK|{{Unique Message Identifier}}|P|2.5.1|||NE|NE|||||Z23^CDCPHINVS\r"
		+ "MSA|AE|{{Original Message Control ID}}\r"
		+ "ERR||{{Error Location}}|{{Error Code}}|E||||{{Error Message}}\r";
	
	private static String qakPart(String ack) {
		return "QAK|{{QPD-2 value from QBP message}}|" + ack + "|{{QPD-1 value from QBP message}}\r";
	}
	
	private static Map<String, List<String>> initMock() {
		InputStream is = PerformanceSimulatorMockIIS.class.getClassLoader().getResourceAsStream(MOCK_DATA_FILE);
		if (is == null) {
			log.error("Cannot load mock IIS data from " + MOCK_DATA_FILE);
			return Collections.emptyMap();
		}
		Map<String, List<String>> map = new HashMap<>();
		int lines = 0;
		try (
			InputStreamReader ir = new InputStreamReader(is, StandardCharsets.UTF_8);
			BufferedReader br = new BufferedReader(ir);
		) {
			String line;
			while ((line = br.readLine()) != null) {
				lines++;
				if (StringUtils.isBlank(line) || line.startsWith("#")) {
					// Ignore empty lines and comments
					continue;
				}
				StringBuilder b = new StringBuilder(line).append('\r');

				while ((line = br.readLine()) != null) {
					lines++;
					if (StringUtils.isBlank(line)) {
						break;
					}
					b.append(line).append('\r');
				}
				
				String message = b.toString(); 
				String pid = getSegment("PID", message);
				if (pid == null) {
					log.error("Bad RSP response message in {}({}): {}", MOCK_DATA_FILE, lines, message);
					continue;
				}
				String dob = getField(pid, DOB);
				List<String> l = map.computeIfAbsent(dob, k -> new ArrayList<>());
				l.add(message);
				String mrNo = getField(pid, ID_LIST);
				dataById.put(mrNo, message);
			}
		} catch (IOException e) {
			log.error("Error reading mock IIS data from " + MOCK_DATA_FILE, e);
		}
		return map;
	}

	private static class QueryException extends Exception {
		private static String[] fieldNames = {
			"", "Query Name", "Query Tag", "ID List", "Name", "Mother's Maiden Name", "Date of Birth", "Sex"
		};
		private static final long serialVersionUID = 1L;
		private final int field;
		private QueryException(int field) {
			this.field = field;
		}
		private int getField() {
			return field;
		}
		public String getFieldName() {
			if (field < 0 || field >= fieldNames.length) {
				return null;
			}
			return fieldNames[field];
		}
	}

	@Override
	public ResponseEntity<?> simulateError(HttpServletResponse resp) throws Fault {
		// The Mock IIS doesn't have errors.
		return null;
	}
	@Override
	public SubmitSingleMessageResponse getResponse(SubmitSingleMessageRequest requestMessage) throws Fault {
		SubmitSingleMessageResponse r = new SubmitSingleMessageResponse(requestMessage, requestMessage.getSchema(), false);
		String hl7RequestMessage = requestMessage.getHl7Message();
		r.setHl7Message(getResponse(hl7RequestMessage));
		return r;
	}
	
	public String getResponse(String hl7RequestMessage) {
		if (!RequestPayloadType.QBP.equals(getRequestPayloadType(hl7RequestMessage))) {
			// Return a NAK to the message reporting the error.
			return queryError(hl7RequestMessage, "MSH^1^9", "200^Unsupported Message Type^HL70357", "Mock does not handle VXU Messages");
		}
		// Parse the message.
		String query = getSegment("QPD", hl7RequestMessage);
		if (query == null) {
			return queryError(hl7RequestMessage, "QPD^1^1", "100^Segment Sequence Error^HL70357", "Missing QPD Segment");
		}
		
		String[] qParts;
		try {
			qParts = getQueryParts(query);
		} catch (QueryException e) {
			return queryError(hl7RequestMessage, "QPD^1^" + Integer.toString(e.getField()), "101^Required Field Missing^HL70357", "Missing Required QPD Parameter: " + e.getFieldName());
		}
		
		List<String> data = findRecords(qParts);
		
		if (data == null) {
			return notFound(hl7RequestMessage);
		}
		data = match(qParts, data);
		if (data.isEmpty()) {
			return notFound(hl7RequestMessage);
		}
		
		if (data.size() == 1) {
			return found(data.get(0), hl7RequestMessage);
		}
		if (data.size() <= 3) {
			return multipleMatches(data, hl7RequestMessage);
		}
		return tooManyMatches(hl7RequestMessage);
	}

	private List<String> findRecords(String[] qParts) {
		List<String> data = null;
		if (qParts.length > DATE_OF_BIRTH && !StringUtils.isEmpty(qParts[DATE_OF_BIRTH])) {
			data = immunizationData.get(qParts[DATE_OF_BIRTH]);
		} else {
			String m = dataById.get(qParts[PID_LIST]);
			if (m == null) {
				data = null;
			} else {
				data = Collections.singletonList(m);
			}
		}
		return data;
	}
	
	private static String getSegment(String segment, String hl7RequestMessage) {
		segment += "|";
		String[] segments = hl7RequestMessage.split("[\r\n]");
		for (String seg: segments) {
			if (seg.startsWith(segment)) {
				return seg;
			}
		}
		return null;
	}

	private static String replaceSegment(String segment, String hl7Message, String newSegment) {
		segment += "|";
		String[] segments = hl7Message.split("[\r\n]");
		for (int i = 0; i < segments.length; i++) {
			if (segments[i].startsWith(segment)) {
				segments[i] = newSegment + "\r";
			} else {
				segments[i] += "\r";
			}
		}
		return StringUtils.join(segments);
	}

	private static String getField(String segment, int fieldNo) {
		String[] fields = segment.split("\\|");
		return fields.length > fieldNo ? fields[fieldNo] : "";
	}

	private static String[] getQueryParts(String query) throws QueryException {
		String[] qParts = query.split("\\|");
		/*
		 * These are the query parts and their use: (R = required, O = optional, N = optional and NOT used)
		 * 
		 * 1. Message Query Name		R
		 * 2. QueryTag					R
		 * 3. PatientList				O
		 * 4. PatientName 				R
		 * 5. PatientMotherMaidenName	O 
		 * 6. PatientDateofBirth		R
		 * 7. PatientSex				R
		 * 8. PatientAddress			O
		 * 9. PatientHomePhone			O
		 * 10. PatientMultipleBirthIndicator	N
		 * 11. PatientBirthOrder		N
		 * 12. ClientLastUpdatedDate	N
		 * 13. ClientLastUpdateFacility	N
		 */
		int fieldNo = 6;
		// If it Has a PID LIST, it's a good query
		if (qParts.length > PID_LIST && !StringUtils.isEmpty(qParts[3])) {
			return qParts;
		}
		// Otherwise it must have name, gender, and DOB 
		if (qParts.length < PATIENT_SEX || 
			StringUtils.isEmpty(qParts[fieldNo = QUERY_NAME]) ||  	// NOSONAR save last attempted matched field in fieldNo
			StringUtils.isEmpty(qParts[fieldNo = QUERY_TAG]) ||  	// NOSONAR save last attempted matched field in fieldNo
			StringUtils.isEmpty(qParts[fieldNo = PATIENT_NAME]) ||	// NOSONAR save last attempted matched field in fieldNo
			StringUtils.isEmpty(qParts[fieldNo = DATE_OF_BIRTH]) ||	// NOSONAR save last attempted matched field in fieldNo
			StringUtils.isEmpty(qParts[fieldNo = PATIENT_SEX])		// NOSONAR save last attempted matched field in fieldNo
		) {
			// fieldNo is set above by side effects and will be the value of the first field making the expression true
			throw new QueryException(fieldNo);
		}
		return qParts;
	}

	private static String queryError(String message, String errorLocation, String errorCode, String errorMessage) {
		Map<String, String> map = new HashMap<>();
		map.put("{{Error Location}}", errorLocation);
		map.put("{{Error Code}}", errorCode);
		map.put("{{Error Message}}", errorMessage);
		return generateResponse(message, PerformanceSimulatorMockIIS.QUERY_ERROR, map);
	}
	
	private static String notFound(String hl7Message) {
		return generateResponse(hl7Message, PerformanceSimulatorMockIIS.QUERY_NOT_FOUND, null);
	}
	
	private static String generateResponse(String hl7RequestMessage, String hl7ResponseMessage, Map<String, String> map) {
		// Construct a Not Found Response
		String msh = StringUtils.defaultString(getSegment("MSH", hl7RequestMessage));
		String qpd = StringUtils.defaultString(getSegment("QPD", hl7RequestMessage));
		hl7ResponseMessage = replaceSegment("QPD", hl7ResponseMessage, qpd);
		Map<String, String> replacements = getReplacementMap(map != null ? map : Collections.emptyMap(), qpd, msh);
		return updateMessage(hl7ResponseMessage, replacements);
	}
	
	private static Map<String, String> getReplacementMap(Map<String, String> init, String qpd, String msh) {
		Map<String, String> map = new HashMap<>(init);
		Date now = new Date();
		map.put("{{Message Timestamp}}", String.format("%tF%tT", now, now).replace("-","").replace(":","") + String.format("%tz", now));
		map.put("{{Unique Message Identifier}}", UUID.randomUUID().toString());
		if (qpd != null) {
			map.put("{{QPD-2 value from QBP message}}",getField(qpd, 2));
			map.put("{{QPD-1 value from QBP message}}",getField(qpd, 1));
		}
		if (msh != null) {
			map.put("{{Original Message Control ID}}",getField(msh, 10));
		}

		return map;
	}
	
	private static String updateMessage(String message, Map<String, String> replacements) {
		for (Map.Entry<String, String> e: replacements.entrySet()) {
			message = message.replace(e.getKey(), e.getValue());
		}
		return message;
	}

	private static String found(String responseMessage, String requestMessage) {
		String msh = StringUtils.defaultString(getSegment("MSH", requestMessage));
		String qpd = StringUtils.defaultString(getSegment("QPD", requestMessage));
		responseMessage = replaceSegment("QPD", responseMessage, qpd);
		Map<String, String> replacements = getReplacementMap(Collections.emptyMap(), qpd, msh);
		return updateMessage(responseMessage, replacements);
	}
	
	private static String multipleMatches(List<String> data, String message) {
		StringBuilder result = new StringBuilder(generateResponse(message, PerformanceSimulatorMockIIS.QUERY_MULTIPLE, null));
		StringBuilder newData = new StringBuilder();
		for (int i = 0; i < data.size(); i++) {
			String d = data.get(i);
			String pid = getSegment("PID", d);
			String nk1 = getSegment("NK1", d);
			if (pid != null) {
				newData.setLength(0);
				newData.append("PID|").append(i+1).append(pid.substring(5)).append("\r");
				if (nk1 != null) {
					newData.append(nk1).append("\r");
				}
				result.append(newData);
			}
		}
		return result.toString();
	}
	
	private static String  tooManyMatches(String hl7RequestMessage) {
		return generateResponse(hl7RequestMessage, PerformanceSimulatorMockIIS.QUERY_TOO_MANY, null);
	}

	private static RequestPayloadType getRequestPayloadType(String message) {
		if (message.contains("|QBP^")) {
			return RequestPayloadType.QBP;
		}
		if (message.contains("|VXU^")) {
			return RequestPayloadType.VXU;
		}
		return RequestPayloadType.OTHER;
	}

	/**
	 * Match the immunization results in data to the values provided in the query parts
	 * @param qParts	the query parts
	 * @param data		The data to match
	 * @return
	 */
    private static List<String> match(String[] qParts, List<String> data) {
    	List<String> matched = new ArrayList<>(data);
    	Iterator<String> matchIterator = matched.iterator(); 
    	while (matchIterator.hasNext()) {
    		String response = matchIterator.next();
    		if (!responseMatches(response, qParts)) {
    			matchIterator.remove();
    		}
    	}
    	return matched;
	}


	private static boolean responseMatches(String data, String[] qParts) {
		String pid =  getSegment("PID", data);
		if (pid == null) {
			return false;
		}
		// We know date of birth matches, so we don't need to check that.
		
		for (int i = PID_LIST; i <= PATIENT_PHONE && i < qParts.length; i++) {
			boolean match = true;
			switch (i) {
			case PID_LIST:
				match = matchRepeatableField(getField(pid, ID_LIST), qParts[PID_LIST], PerformanceSimulatorMockIIS::compareFirstComponent);
				break;
			case PATIENT_NAME:
				match = 
					matchRepeatableField(getField(pid, NAME), qParts[PATIENT_NAME], PerformanceSimulatorMockIIS::compareNameParts) ||
					matchRepeatableField(getField(pid, ALIAS), qParts[PATIENT_NAME], PerformanceSimulatorMockIIS::compareNameParts);
				break;
			case MOTHERS_MAIDEN_NAME:
				match = matchRepeatableField(getField(pid, MAIDEN_NAME), qParts[MOTHERS_MAIDEN_NAME], PerformanceSimulatorMockIIS::compareFirstComponent);
				break;
			case PATIENT_SEX:
				match = matchRepeatableField(getField(pid, SEX), qParts[PATIENT_SEX], PerformanceSimulatorMockIIS::compareFirstComponent);
				break;
			case ADDRESS:
				match = matchRepeatableField(getField(pid, ADDR), qParts[ADDRESS], PerformanceSimulatorMockIIS::compareAddrParts);
				break;
			case PATIENT_PHONE:
				match = 
					matchRepeatableField(getField(pid, HOME_PHONE), qParts[PATIENT_PHONE], PerformanceSimulatorMockIIS::comparePhoneNumber) ||
					matchRepeatableField(getField(pid, WORK_PHONE), qParts[PATIENT_PHONE], PerformanceSimulatorMockIIS::comparePhoneNumber);
				break;
			default:
				continue;
			}
			if (!match) {
				return false;
			}
		}
		
		return true;
	}


	private static boolean matchRepeatableField(String value, String match, Comparator<String> matchFunction) {
		if (StringUtils.isEmpty(match)) {
			return true;
		}
		for (String m: match.split("~")) {
			if (m.isEmpty()) {
				continue;
			}
			for (String v: value.split("~")) {
				if (matchFunction.compare(m, v) == 0) {
					return true;
				}
			}
		}
		return false;
	}
	
	private static int compareNameParts(String value, String match) {
		String[] values = value.split("\\^");
		String[] matches = match.split("\\^");
		if (values.length > 0 && matches.length > 0) {
			int c = compareNameStrings(values[0], matches[0], 3);
			if (c != 0) return c;
		}
		if (values.length > 1 && matches.length > 1) {
			int c = compareNameStrings(values[1], matches[1], 3);
			if (c != 0) return c;
		} 
		return 0;
	}

	private static int compareFirstComponent(String value, String match) {
		String[] values = value.split("\\^");
		String[] matches = match.split("\\^");
		if (values.length > 0 && matches.length > 0) {
			int c = compareNameStrings(values[0], matches[0], 0);
			if (c != 0) return c;
		}
		return 0;
	}

	private static int comparePhoneNumber(String value, String match) {
		value = normalizePhoneNumber(value);
		match = normalizePhoneNumber(match);
		if (match.isEmpty()) {
			return 0;
		}
		return StringUtils.compare(value, match);
	}
	
	private static String normalizePhoneNumber(String value) {
		String[] values = value.split("\\^");
		if (values.length > 1) {
			if (values.length > 12) {
				value = values[12];
			} else if (values.length > 7) {
				value = values[6] + values[7];
			}
		} else {
			value = values[0];
		}
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < value.length(); i++) {
			int c = value.charAt(i);
			if (Character.isDigit(c) || Character.isAlphabetic(c)) {
				b.append(c);
			}
		}
		value = b.toString();
		if (value.length() == 10 && value.startsWith("1")) {
			// remove leading 1
			value = value.substring(1);
		}
		return value;
	}

	private static int compareNameStrings(String v, String m, int len) {
		if (m.isEmpty()) {
			return 0;
		}
		int l = Math.min(v.length(), m.length());
		v = v.toUpperCase().substring(0, l);
		m = m.toUpperCase().substring(0, l);
		
		l = LevenshteinDistance.getDefaultInstance().apply(v, m);
		return  l < len ? 0 : l;
	}
	
	private static int compareStreetAddress(String v, String m, int len) {
		if (m.isEmpty()) {
			return 0;
		}
		String[] vParts = splitAlphaNumeric(v);
		String[] mParts = splitAlphaNumeric(m);
		int c = mParts[1].isEmpty() ? 0 : StringUtils.compare(vParts[1], mParts[1]);
		if (c != 0) {
			return c;
		}
		return compareNameStrings(vParts[0], mParts[0], len);
	}
	
	private static String[] splitAlphaNumeric(String s) {
		StringBuilder alpha = new StringBuilder();
		StringBuilder numeric = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			int c = s.charAt(i);
			if (Character.isDigit(c)) {
				numeric.append(c);
			} else if (Character.isAlphabetic(c)) {
				alpha.append(c);
			} else {
				numeric.append(c);
				alpha.append(c);
			}
		}
		String[] result = { alpha.toString(), numeric.toString() };
		return result;
	}

	private static int compareAddrParts(String value, String match) {
		String[] values = value.split("\\^");
		String[] matches = match.split("\\^");
		
		// Compare zip codes.
		for (int i = 4; i >= 0; i--) {
			if (matches.length <= i || StringUtils.isEmpty(matches[i])) {
				continue;
			}
			if (values.length <= i) {
				return -1;
			}
			int c = 0;
			switch (i) {
			case 4:
				// Compare zip code
				String v = values[4];
				String m = matches[4];
				int l = Math.min(v.length(), m.length());
				v = v.substring(0, l);
				m = m.substring(0, l);
				c = StringUtils.compare(v, m);
				break;
			case 3:
				// Compare state
				c = StringUtils.compareIgnoreCase(values[i], matches[i]);
				break;
			case 2:
				// Compare City
				c = compareNameStrings(values[i], matches[i], 3);
				break;
			case 0:
				// Compare Street address
				c = compareStreetAddress(values[0], matches[0], 5);
				break;
			default:
				continue;
			}
			if (c != 0) {
				return c;
			}
		}
		
		return 0;
	}

}
