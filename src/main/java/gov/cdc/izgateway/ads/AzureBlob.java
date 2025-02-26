package gov.cdc.izgateway.ads;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import ca.uhn.hl7v2.util.XMLUtils;
import gov.cdc.izgateway.logging.markers.Markers2;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Represents an Blob in Azure Blob Stoorage 
 * 
 * @author Audacious Inquiry
 *
 */
@Data
@Slf4j
public class AzureBlob {
    private final String name;
	private final long length;
	private final Date created;
	private final Metadata metadata;

	/**
	 * Create a new blob object
	 * 
	 * @param name	The name of the blob
	 * @param length	Its length 
	 * @param created	The data it was created
	 * @param metadata	Its metadata
	 */
	public AzureBlob(String name, long length, Date created, Metadata metadata) {
		this.name = name;
		this.length = length;
		this.created = created;
		this.metadata = metadata;
	}
	
	public String toString() {
		return String.format("%s of length %d created on %s", 
				name, length, created != null ? Metadata.RFC2616_DATE_FORMAT.format(created) : "(unknown)");
	}
	
	/**
	 * Delete a blob
	 * @param originalCon	The connection to use to delete it
	 * @param sslSocketFactory	The sslSocketFactory
	 */
	public void delete(HttpURLConnection originalCon, SSLSocketFactory sslSocketFactory) {
		try {
			String originalUrl = originalCon.getURL().toString(); 
			String base = StringUtils.substringBefore(originalUrl, "/izgw/");
			String auth = StringUtils.substringAfter(originalUrl, "?");
			URL url = new URL(base + "/izgw/" + getName() + "?" + auth);
			
			HttpsURLConnection conx = (HttpsURLConnection) url.openConnection();
			conx.setSSLSocketFactory(sslSocketFactory);
			conx.setDoInput(true);
			conx.setRequestMethod("DELETE");
			int status = conx.getResponseCode();
			// It could be 404 if deleted from another thread 
			if (status != 202 && status != 404) {
				String error = org.apache.commons.io.IOUtils.toString(conx.getErrorStream(), StandardCharsets.UTF_8);
				log.error("Unexpected response for delete of blob {}: {} {}", getName(), status, error);
				return;
			}
			log.info("Deleted blob {} of length {} created on {}", getName(), getLength(), getCreated());
		} catch (Exception e) {
			log.error(Markers2.append(e), "Cannot delete blob {}: {}", getName(), e.getMessage());
		}
	}
	
	static AzureBlob[] listBlobs(HttpURLConnection originalCon, SSLSocketFactory sslSocketFactory) {
		String originalUrl = originalCon.getURL().toString();
		String base = StringUtils.substringBefore(originalUrl, "/izgw");
		String auth = StringUtils.substringAfter(originalUrl, "?");
		int status = -1;
		try {
			URL url = new URL(base + "/izgw?restype=container&comp=list&" + auth);
			HttpsURLConnection conx = (HttpsURLConnection) url.openConnection();
			conx.setSSLSocketFactory(sslSocketFactory);
			conx.setDoInput(true);
			conx.setRequestMethod("GET");
			status = conx.getResponseCode();
			if (status != 200) {
				String error = IOUtils.toString(conx.getErrorStream(), StandardCharsets.UTF_8);
				log.error("Cannot list blobs: {}", error);
				return new AzureBlob[0];
			}
			return parseBlobs(conx);
		} catch (IOException e) {
			log.error(Markers2.append(e), "Cannot list blobs: {}", e.getMessage());
			return new AzureBlob[0];
		} 
	}

	private static AzureBlob[] parseBlobs(HttpsURLConnection conx) throws IOException {
		List<AzureBlob> blobs = new ArrayList<>();
		try (InputStream is = conx.getInputStream()) {
			Document d = XMLUtils.parse(is, false);
			NodeList n = d.getElementsByTagName("Blob");
			for (int i = n.getLength(); i > 0 ; i--) {
				AzureBlob aBlob = extractBlob((Element)n.item(i-1));
				if (aBlob != null) {
					blobs.add(aBlob);
				}
			}
		}
		return blobs.toArray(new AzureBlob[0]);
	}

	private static AzureBlob extractBlob(Element blob) {
		String name = blob.getElementsByTagName("Name").item(0).getTextContent();
		Element props = (Element) blob.getElementsByTagName("Properties").item(0);
		String length = props.getElementsByTagName("Content-Length").item(0).getTextContent();
		String created = props.getElementsByTagName("Creation-Time").item(0).getTextContent();
		Date date = null;
		long len;
		try {
			len = Long.parseLong(length);
		} catch (NumberFormatException nfe) {
			len = -1;
		}
		
		try {
			date = Metadata.RFC2616_DATE_FORMAT.parse(created);
		} catch (Exception e) {
			// We just don't have a date value, so set to 0 (ancient)
			date = new Date(0);
		}
		
		Metadata meta = parseMetadata(blob);
		if (meta == null) {
			return null;
		}
		return new AzureBlob(name, len, date, meta);
	}

	private static Metadata parseMetadata(Element blob) {
		blob = (Element) blob.getElementsByTagName("Metadata").item(0);
		if (blob == null) {
			return null;
		}
		MetadataImpl m = new MetadataImpl();
		
		for (Node n = blob.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e) {
				m.set(e.getTagName(), e.getTextContent());
			}
		}
		return m;
	}
}