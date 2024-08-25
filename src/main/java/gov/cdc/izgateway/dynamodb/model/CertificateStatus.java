package gov.cdc.izgateway.dynamodb.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;

import gov.cdc.izgateway.dynamodb.DynamoDbEntity;
import gov.cdc.izgateway.model.ICertificateStatus;
import gov.cdc.izgateway.utils.X500Utils;


/**
 * This class records status of a given certificate wrt to OCSP checks
 * 
 * @author Audacious Inquiry
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper=false)
public class CertificateStatus extends DynamoDbEntity implements Serializable, ICertificateStatus {
	/** certificateId is the SHA-1 Message Digest of the Certificate
	 *  It is guaranteed to be unique across all certificates within the
	 *  limits of the hash function (2^160 possible unique values, resulting 
	 *  in a very low likelihood of collisions). Serial Number is only guaranteed
	 *  to be unique within the context of a Certificate Authority.
	 *  
	 *  Given we are testing certificates from multiple CAs, we use the certificateId
	 *  instead of the serial number.
	 */
    private String certificateId;

    /** We collect the common name so that if a human needs to track down a problem
     *  they will have an easier time of it.
     */
    private String commonName;
    
    private String certSerialNumber;

    private Timestamp lastCheckedTimeStamp;

    private Timestamp nextCheckTimeStamp;

    private String lastCheckStatus;

    public String toString() {
    	return String.format("%s(%s) last checked at %tD %tT.%TL: %s", 
    			commonName, certificateId, lastCheckedTimeStamp, lastCheckedTimeStamp, lastCheckedTimeStamp, lastCheckStatus);
    }
    
    /**
     * Create a PhizCertificateStatus object representing the current certificate.
     * @param cert The certificate to initialize from.
     * @return A PhizCertificateStatus object representing the certificate
     */
    public static CertificateStatus create(X509Certificate cert) {
    	if (cert == null) {
    		throw new NullPointerException("cert parameter cannot be null");
    	}
    	return new CertificateStatus(cert);
    }
    
    /**
     * Create a new CertificateStatus entity
     */
    public CertificateStatus() {
    	setLastCheckStatus("UNKNOWN");
	}
    /**
     * Create a new CertificateStatus entity from an existing certificate
     * @param cert The certificate to copy from
     */
    public CertificateStatus(X509Certificate cert) {
    	if (cert == null) {
    		throw new NullPointerException("cert parameter cannot be null");
    	}
    	setLastCheckStatus("UNKNOWN");
		setLastCheckedTimeStamp(new Timestamp(0)); // Start of epoch, effectively never previously checked.
		setNextCheckTimeStamp(new Timestamp(System.currentTimeMillis() - 100));  // Overdue for a check
		setCertificateId(ICertificateStatus.computeThumbprint(cert));
		setCertSerialNumber(cert.getSerialNumber().toString(16));
		setCommonName(X500Utils.getCommonName(cert));
    }
    /**
     * Create a new CertificateStatus entity from an existing CertificateStatus entity
     * @param s The certificate status to copy from
     */
    public CertificateStatus(ICertificateStatus s) {
    	setLastCheckStatus(s.getLastCheckStatus());
    	setLastCheckedTimeStamp(s.getLastCheckedTimeStamp());
    	setNextCheckTimeStamp(s.getNextCheckTimeStamp());
    	setCertificateId(s.getCertificateId());
    	setCertSerialNumber(s.getCertSerialNumber());
    	setCommonName(s.getCommonName());
    }

	@Override
	public String primaryId() {
		return certificateId;
	}
}