package gov.cdc.izgateway.ads;


import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.util.encoders.Base64;
import org.springframework.web.multipart.MultipartFile;

import gov.cdc.izgateway.logging.markers.Markers2;
import jakarta.activation.DataHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceConfigurationError;

/**
 * Utility class to compute integrity check data
 * 
 */
@Slf4j
public class IntegrityCheck {
    
    private static final byte[] MSOXML_FILE_MAGIC = { 0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x06, 0x00 };
    private static final byte[] MSOOLE_FILE_MAGIC = { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1 };
    private static final byte[] ZIP_FILE_MAGIC = { 0x50, 0x4B, 0x03, 0x04 };
    private static final Map<String, byte[]> MAGIC_NUMBERS = new HashMap<>();
    static {
        MAGIC_NUMBERS.put("", MSOXML_FILE_MAGIC);
        MAGIC_NUMBERS.put("", MSOOLE_FILE_MAGIC);
        MAGIC_NUMBERS.put("", ZIP_FILE_MAGIC);
    }

    /** The MD5 Hash of the data.  While MD5 is considered insecure for many security functions,
     * it is still perfectly accepable for use as a data integrity check.
     */
    private final byte[] hash;
    /** Length of data is basic integrity check */
    private final long length;

    /** Mime Type */
    private final String mimeType;
    
    /** Construct a new integity check object
     * @param mimeType 
     */
    public IntegrityCheck(byte[] hash, long length, String mimeType) {
        this.hash = hash;
        this.length = length;
        this.mimeType = mimeType;
    }

    /**
     * Compute the length of the data. Attempts to get it from the MultipartFile if it
     * exists, otherwise, computes an IntegrityCheck from the file data.
     * 
     * @param data The DataHandler
     * @return	The length of content in the data handler
     * @throws IOException	If an IO Exception occurs
     */
    public static IntegrityCheck getLength(DataHandler data) throws IOException {
    	if (data.getContent() instanceof MultipartFile mp) {
    		long fileSize = mp.getSize();
    		return new IntegrityCheck(new byte[0], fileSize, null);
    	}
    	return getIntegrityCheck(data);
    }
    /** 
     * Compute MD5 Hash and length of DataHandler content 
     * @param data  The data handler
     * @return An IntegrityCheck object containing the MD5 Hash and length
     * @throws IOException 
     */
    public static IntegrityCheck getIntegrityCheck(DataHandler data) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            log.error(Markers2.append(e), "No MD5 Algorithm available: {}", e);
            throw new ServiceConfigurationError("Misconfigured System");
        }
        byte[] buffer = new byte[1048576];
        byte[] magic = null;
        InputStream is;
        long fileSize = 0;
        is = data.getInputStream();
        int read = 0;
        while ((read = is.read(buffer)) > 0) {
            if (magic == null) {
                magic = Arrays.copyOf(buffer, 8);
            }
            fileSize += read;
            md.update(buffer, 0, read);
        }
        if (magic == null) {
        	return new IntegrityCheck(new byte[0], fileSize, null);
        }
        byte[] hash = md.digest();
        String mimeType = null;
        for (Map.Entry<String, byte[]> e: MAGIC_NUMBERS.entrySet()) {
            byte[] key = e.getValue();
            if (Arrays.compare(magic, 0, key.length, key, 0, key.length) == 0) {
                mimeType = e.getKey();
                break;
            }
        }
        return new IntegrityCheck(hash, fileSize, mimeType); 
    }

    
    /**
     * @return the hash code.
     */
    public byte[] getHash() {
        return hash;
    }

    /**
     * @return the length.
     */
    public long getLength() {
        return length;
    }

    /**
     * @return the MD5 hash.
     */
    public String toString() {
        return Base64.toBase64String(hash);
    }
    
    /** 
     * @return the mimeType.
     */
    public String getMimeType() {
        return mimeType;
    }

    
    /** 
     * @return true if there is a mimeType value
     */
    public boolean hasMimeType() {
        return !StringUtils.isBlank(mimeType);
    }

}
