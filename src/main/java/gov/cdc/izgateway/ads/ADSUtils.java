package gov.cdc.izgateway.ads;

import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

public class ADSUtils {
    private static final Map<String, String> ENTITY_ID_MAP = new TreeMap<>();

    private ADSUtils() {
    }

    public static String mapFacilityId(String facilityId, String principal) {
        // If there is an entity id, then no mapping is needed, just validate it
        if (facilityId != null) {
            // Normalize the value
            return facilityId.trim().toUpperCase();
        } 
        // Otherwise map from source data from certificate.
        return mapFromOrganization(principal);
    }

    public static String mapFromOrganization(String organization) {
        String org = StringUtils.substringBefore(organization, " ");
        return ENTITY_ID_MAP.get(org);
    }

    public static boolean isFacilityIdValidForSender(String facilityId, String principal) {
        return facilityId != null;
    }

    private static final char[] DISALLOWED = {
        '*', '\'', '?', '>', '<', ':', '|', '/', '\\', 0x7f, 0x8d, 0x8f, 0x90, 0x9d
    };
    
    public static boolean validateFilename(String filename) {
        if (filename.length() == 0 && filename.length() >= 1024) {
            return false;
        }
        return filename.codePoints().allMatch(c -> c > 0x1f && c < 0xd7ff && Arrays.binarySearch(DISALLOWED, (char)c) < 0);
    }

    public static URI createUrl(URI base, Metadata meta) throws URISyntaxException {
        return new URI(getPath(base, meta));
    }
    
    public static String getPath(URI base, Metadata meta) {
        long now = System.currentTimeMillis();
        return String.format(
            "%s/%s/%s/%tY%tm%td/%s", 
            base.getPath(),
            meta.getPeriod(),
            meta.getExtEntity(),
            now, now, now,
            meta.getFilename()
        );
    }
}
