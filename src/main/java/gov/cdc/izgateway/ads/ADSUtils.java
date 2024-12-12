package gov.cdc.izgateway.ads;

import org.apache.commons.lang3.StringUtils;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.TreeMap;

/**
 * Utility functions supporting Automated Data Submission (ADS) services in IZ Gateway Hub
 * 
 * @author Audacious Inquiry
 *
 */
@Slf4j
public class ADSUtils {
    private static final Map<String, String> ENTITY_ID_MAP = new TreeMap<>();

    private ADSUtils() {
    }

    /**
     * Given a Facility Identifier, get it's associated principal identifier.
     * @param facilityId	The facility ID
     * @param principal	The name of the principal
     * @return	A string identifying the facility.
     */
    public static String mapFacilityId(String facilityId, String principal) {
        // If there is an entity id, then no mapping is needed, just validate it
        if (facilityId != null) {
            // Normalize the value
            return facilityId.trim().toUpperCase();
        } 
        // Otherwise map from source data from certificate.
        return mapFromOrganization(principal);
    }

    /**
     * Given an Organization Name, get it's associated principal identifier.
     * @param organization	The organization name	
     * @return	A string identifying the facility.
     */
    public static String mapFromOrganization(String organization) {
    	
        String org = StringUtils.substringBefore(organization, " ");
        return ENTITY_ID_MAP.get(org);
    }

    /**
     * Compare the facility id to the sender principal.  In the future, this may integrate
     * with access control to determine if a given principal can send on behalf of a given 
     * facility (e.g., uploads for MAA are allowed to be sent by "maiis.mass.gov").
     * 
     * NOTE: For now, this is generally always true.
     * 
     * @param facilityId	The CDC assigned identifier
     * @param principal	The principal associated with the request
     * @return	true if the facility can be sent to by the given principal
     */
    public static boolean isFacilityIdValidForSender(String facilityId, String principal) {
        return facilityId != null;
    }

    private static final char[] DISALLOWED = {
        '*', '\'', '?', '>', '<', ':', '|', '/', '\\', 0x7f, 0x8d, 0x8f, 0x90, 0x9d
    };
	private static final String MY_IP_ADDRESS = getMyIpAddress();
    
    /**
     * Check filenames for invalid characters
     * @param filename	The filename
     * @return	True if no invalid characters are found.
     */
    public static boolean validateFilename(String filename) {
        if (filename.length() == 0 && filename.length() >= 1024) {
            return false;
        }
        return filename.codePoints().allMatch(c -> c > 0x1f && c < 0xd7ff && Arrays.binarySearch(DISALLOWED, (char)c) < 0);
    }

	/**
	 * Create a URI for an ADS Endpoint from metadata
	 * @param base	The base URI
	 * @param meta	The metadata
	 * @return	The constructed URI
	 * @throws URISyntaxException	If the URL has a syntax error
	 */
	public static URI createUrl(URI base, Metadata meta) throws URISyntaxException {
        return new URI(getPath(base, meta));
    }
    
    /**
     * Construct the file path to a blob from it's metadata.
     * NOTE: This is the original NDLP v1 Construction, this might change 
     * for most recent code base
     *  
     * @param base	The base URI
     * @param meta	The metadata for the submission
     * @return	The path to use for the blob
     */
    public static String getPath(URI base, Metadata meta) {
        long now = System.currentTimeMillis();
        if (meta.getExtSourceVersion().equals(Metadata.DEX_VERSION2)) {
        	return String.format("%s/%s/%s/%tY/%tm/%td/%s", 
    			base.getPath(), 
    			meta.getDataStreamId(), 
    			meta.getExtEntity(), 
    			now, now, now,
    			meta.getFilename()
        	);
        }
        return String.format(
            "%s/%s/%s/%tY%tm%td/%s", 
            base.getPath(),
            meta.getPeriod(),
            meta.getExtEntity(),
            now, now, now,
            meta.getFilename()
        );
    }

    /**
     * Azure tokens may be locked to an IP Address. For dev and test environments, we have TWO
     * different IP addresses requests can come from. This method will extract the password
     * based on the IP egress address of the system that is currently running.
     * 
     * This is a bit of a hack, but is ONLY needed for dev and test environments that emerge
     * from multiple regions or zones.  If more than two egress addresses are used, the DB
     * might need to be updated to support longer password strings.
     * 
     * @param password
     * @return	The appropriate token to use for an Azure blob store.
     */
	public static String getAzureToken(String password) {
		String[] tokens = password.split(",");
		if (tokens.length == 1) {
			return password;
		}
		for (String token: tokens) {
			String ipAddress = StringUtils.substringBetween(token, "sip=", "&");
			if (StringUtils.isEmpty(ipAddress)) {
				// This token is NOT locked to an IP Address 
				log.debug("Did not find a token matching {}", MY_IP_ADDRESS);
				return token;
			}
			if (ipAddress.equals(MY_IP_ADDRESS) || ipAddress.contains("-") && myIpInRange(ipAddress)) {
				// This token is locked to my IP Address or to a range of IP addresses that my IP Address is in. 
				log.debug("Found token for {} with parameters {}", MY_IP_ADDRESS, StringUtils.substringBefore(token, "&sig="));
				return token;
			}
		}
		return null;
	}
	
	/**
	 * Verify that MY_IP_ADDRESS is within the range specified by ipAddress.
	 * 
	 * @param ipRange	The range
	 * @return true if within the range
	 */
	private static boolean myIpInRange(String ipRange) {
		String[] range = ipRange.split("-");
		if (range.length != 2) {
			throw new IllegalArgumentException("Not an IP Address Range: " + ipRange);
		}
		try {
			InetAddress low = InetAddress.getByName(range[0]);
			InetAddress high = InetAddress.getByName(range[1]);
			InetAddress myAddr = InetAddress.getByName(MY_IP_ADDRESS);
			byte[] mine = myAddr.getAddress();
			if (low.getClass() == high.getClass() && low.getClass() == myAddr.getClass()) {
				return 
					Arrays.compareUnsigned(low.getAddress(), mine) <= 0 &&
					Arrays.compareUnsigned(high.getAddress(), mine) >= 0;
			}
			return false; // Not same class of IP address, my address is v4 and range is v6 or vice versa
		} catch (UnknownHostException e) {
			throw new IllegalArgumentException("Unknown host exception checking " + ipRange, e);
		}
	}
	
	/**
	 * Call a web service to get this server's egress IP Address.
	 * Tries checkip.amazonaws.com and ipv4.icanhazip.com, both of which should be highly reliable.
	 * 
	 * @return The egress IP Address of this system.
	 */
    public static String getMyIpAddress() {
    	String[] urlStrings = { "http://checkip.amazonaws.com/", "http://ipv4.icanhazip.com/" };
    	Throwable ex = null;
    	for (String urlString: urlStrings) {
	    	try (BufferedReader br = new BufferedReader(new InputStreamReader(new URL(urlString).openStream()))) {
	    	    return br.readLine();
	    	} catch (Exception e) {
	    		if (ex == null) {
	    			ex = e;
	    		}
	    	}
    	}
    	throw new ServiceConfigurationError("Cannot get IP Address", ex);
	}
    
    /**
     * @return The egress IP address for this instance.
     */
    public static String getEgressPoint() {
    	return MY_IP_ADDRESS;
    }
}
