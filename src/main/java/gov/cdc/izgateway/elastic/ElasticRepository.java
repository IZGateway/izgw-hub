package gov.cdc.izgateway.elastic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.ServiceConfigurationError;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

public class ElasticRepository {
	protected static JsonNode getNode(String key, JsonNode node) {
		String[] parts = key.split("\\.");
		for (String k: parts) {
			if (!StringUtils.isNumeric(k)) {
				node = node.get(k);
			} else if (node.isArray()) {
				node = node.get(Integer.parseInt(k) - 1);
			} else {
				node = node.get(k);
			}
			if (node == null || node instanceof NullNode) {
				throw new IllegalArgumentException(k + " missing from ElasticSearch response in " + key);
			}
		}
		return node;
	}

	private String[] requestTemplate = null;
	protected final ElasticConfiguration config;
	
	protected ElasticRepository(ElasticConfiguration config, String query) {
		this.config = config;
		String template;
		try {
			template = IOUtils.toString(HostRepository.class.getClassLoader().getResourceAsStream(query), StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new ServiceConfigurationError("Cannot access " + query, e);
		}
		setRequestTemplate(template);
	}
	
	private void setRequestTemplate(String template) {  // NOSONAR Cognitive complexity is OK
		ArrayList<String> parts = new ArrayList<>();
		String last = null;
		int state = 0;
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < template.length(); i++) {
			char c = template.charAt(i);
			switch (state) {	// NOSONAR No default case needed
			case 0:
				if (c == '$') {
					last = b.toString();
					parts.add(last);
					b.setLength(0);
					state = 1;
				} else {
					b.append(c);
				}
				break;
			case 1:
				if (c == '{') {
					state = 2;
				} else {
					parts.remove(parts.size() - 1);
					b.append(last);
					b.append('$');
					b.append(c);
					state = 0;
				}
				break;
			case 2:
				if (c == '}') {
					last = b.toString();
					parts.add(last);
					b.setLength(0);
					state = 0;
				} else {
					b.append(c);
				}
				break;
			}
		}
		if (b.length() > 0) {
			parts.add(b.toString());
		}
		
		requestTemplate = parts.toArray(new String[0]);
	}

	public String[] getRequestTemplate() {
		return requestTemplate;
	}
	
	/**
	 * Populate the template from replacement values in map.
	 * @param map	The map of replacement values.
	 * @return	The populated template.
	 */
	protected String populateTemplate(Map<String, String> map) {
		boolean lookup = false;
		boolean hasError = false;
		StringBuilder b = new StringBuilder();
		for (String part: getRequestTemplate()) {
			if (lookup) {
				String value = map.get(part);
				if (value == null) {
					part = "${" + part + "}";
					hasError = true;
				} else {
					part = value;
				}
			}
			b.append(part);
			lookup = !lookup;
		}
		if (hasError) {
			throw new IllegalStateException("Missing replacement values in template: " + b.toString());
		}
		return b.toString();
	}

}
