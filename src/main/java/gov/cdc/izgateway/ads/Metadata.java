package gov.cdc.izgateway.ads;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.FastDateFormat;

import java.io.Serializable;
import java.util.Date;

/**
 * Object used to store metadata.  JSON Names are the same values as would be used
 * in the Azure metadata.
 * 
 * Data Element Name		Data Element Description	Purpose	Optionality	Level 
 * meta_destination_id	Set to - "NDLP"	Establishes that this file should be directed to CDC NDLP.	Required	File Level
 * 
 * meta_ext_source	Set to - "IZGW"	Establishes where data came from.	Required	File Level
 * 
 * meta_ext_sourceversion	V2022-12-31	Aligns with version of the specification.	Required	File Level
 * 
 * meta_ext_event	Set to:
 * o    "routineImmunization" - for Routine Immunization files
 * o    "influenzaVaccination" - for Influenza files	Determines storage container for IZGW to submit files on NDLP Storage Account	Required	File Level
 * 
 * meta_ext_entity	This is the three digit entity code.  See tab - Submitting Entity Codes, e.g., 
 * o 	NYA (New York State)
 * o 	CV1 (CVS Health Corporation)
 * o 	DD2 (Department of Defense)	Drives metrics for vaccine admin progress at  entity grouping level.	Required	File Level
 * 
 * meta_username	Username of user who submitted the file to the IZGW.  If this is a system to system submission then this should be the service account.	This field is used for troubleshooting.	Required	File Level
 * 
 * meta_ext_objectkey	81bc2718-aef5-479b-8d67-955a2e75ac53 	This field is used to track back to the source filename / objectid.	Required	File Level
 * 
 * meta_ext_filename	The name of the file submitted to the IZGW.  File and extension should be named in accordance with guidelines outlined in the DUA.	Tracking the name of the file that was submitted.	Required	File Level
 * 
 * meta_ext_submissionperiod	This is the actual time period associated with submission for this file:
 * o    YYYYQ#  - For Routine Immunization quarterly 
 *       submissions (e.g., 2022Q1)
 * o    YYYY-MON - for Influenza Vaccination monthly 
 *       submissions (e.g., 2023-FEB)	Tracks frequency associated with file submission, as well as the actual file submission timeframe based on the frequency requirements associated with the program (i.e., Quarterly for Routine Immunizations, and Monthly for Influenza Vaccinations)	Required	File Level
 *       
 * meta_schema_version	This is an optional metadata field used for DEX Uploads.  It represents the version of the metadata  associated with the file being uploaded to DEX. If the optional meta_schema_version metadata field is not provided, then the earliest version found in the metadata config file for the given meta_destination_id will be used.	Indicates the version of metadata for the file being uploaded to DEX. 	Optional	File Level
 * 
 *  
 */

@JsonPropertyOrder({ 
    "data_producer_id",
    "data_stream_id",
    "data_stream_route",
	"meta_destination_id", 
	"meta_ext_entity", 
	"meta_ext_event", 
	"meta_ext_event_type", 
	"meta_ext_file_timestamp",
	"meta_ext_filename",
	"meta_ext_objectkey",
	"meta_ext_source", 
	"meta_ext_sourceversion", 
	"meta_ext_submissionperiod", 
	"meta_schema_version", 
	"meta_username", 
	"izgw_event_id",
	"izgw_filesize", 
	"izgw_ipaddress", 
	"izgw_path", 
	"izgw_route_id",
	"izgw_submission_location",
	"izgw_submission_status",
	"izgw_uploaded_timestamp",
    "sender_id"
})
public interface Metadata extends Serializable {
	
	/** The metadata name of the flag that marks a file as a test file. */
	public static final String TESTFILE = "meta_ext_testfile";
	/** The date pattern for RFC 2616 (HTTP) dates in metadata. */
	public static final String RFC2616_DATE_FORMAT_PATTERN = "EEE, dd MMM yyyy HH:mm:ss zzz";
	/** The date format for RFC 2616 (HTTP) dates in metadata. */
	public static final FastDateFormat RFC2616_DATE_FORMAT = FastDateFormat.getInstance(RFC2616_DATE_FORMAT_PATTERN);
	/** The first DEX metadata source version. */
	public static final String DEX_VERSION1 = "V2023-09-01";
	/** The second DEX metadata source version. */
	public static final String DEX_VERSION2 = "V2024-09-04";

	/**
	 * Gets the DEX metadata version (version). DEX requires this field.
	 * @return "1.0" for {@link #DEX_VERSION1}, else "2.0"
	 */
	@JsonProperty("version")
	default String getVersion() {
		switch (StringUtils.defaultString(getExtSourceVersion())) {
		case DEX_VERSION1:
			return "1.0";
		case DEX_VERSION2:
		default:
			return "2.0";
		}
	}
	
    /**
     * Gets the destination ID (meta_destination_id).
     * @return the destination ID
     */
    @JsonProperty("meta_destination_id")
    String getDestinationId();
    /**
     * Sets the destination ID (meta_destination_id).
     * @param destId the destination ID
     */
    void setDestinationId(String destId);
    
    /**
     * Gets the source of the data (meta_ext_source).
     * @return the source
     */
    @JsonProperty("meta_ext_source")
    String getExtSource();
    /**
     * Sets the source of the data (meta_ext_source).
     * @param extSource the source
     */
    void setExtSource(String extSource);
    /**
     * Gets the sender ID (sender_id). DEX requires this field. The value is IZGW.
     * @return the sender ID, which is the same as the source
     */
    @JsonProperty("sender_id")
    default String getSenderId() {
    	return getExtSource();
    }

    /**
     * Gets the version of the specification (meta_ext_sourceversion).
     * @return the source version
     */
    @JsonProperty("meta_ext_sourceversion")
    String getExtSourceVersion();
    /**
     * Sets the version of the specification (meta_ext_sourceversion).
     * @param extSourceVersion the source version
     */
    void setExtSourceVersion(String extSourceVersion);
    
    /**
     * Gets the event, which selects the storage container (meta_ext_event).
     * @return the event
     */
    @JsonProperty("meta_ext_event")
    String getExtEvent();
    /**
     * Sets the event, which selects the storage container (meta_ext_event).
     * @param extEvent the event
     */
    void setExtEvent(String extEvent);

    /**
     * Gets the event type (meta_ext_event_type). This field is new in DEX 2.0.
     * The values are usually the same as meta_ext_event, but other submission
     * types can also go through to NDLP.
     * @return the event type
     */
    @JsonProperty("meta_ext_event_type")
    String getExtEventType();
    /**
     * Sets the event type (meta_ext_event_type).
     * @param extEventTyype the event type
     */
    void setExtEventType(String extEventTyype);
    /**
     * Gets the data stream ID (data_stream_id). The value comes from meta_ext_event
     * through the camelCase to kebab-case conversion in
     * {@code MetadataBuilder.computeDataStreamId()}. {@link MetadataImpl} overrides
     * this method to return a stored value first, so a value set during deserialization wins.
     * @return the data stream ID
     */
    @JsonProperty("data_stream_id")
    default String getDataStreamId() {
        return MetadataBuilder.computeDataStreamId(getExtEvent());
    }
    
    /**
     * Gets the submitting entity code (meta_ext_entity).
     * @return the entity code
     */
    @JsonProperty("meta_ext_entity")
    String getExtEntity();
    /**
     * Sets the submitting entity code (meta_ext_entity).
     * @param extEntity the entity code
     */
    void setExtEntity(String extEntity);
    /**
     * Gets the data producer ID (data_producer_id). DEX requires this field.
     * @return the data producer ID, which is the same as meta_ext_entity
     */
    @JsonProperty("data_producer_id")
    default String getDataProducerId() {
    	return getExtEntity();
    }
    /**
     * Gets the jurisdiction (jurisdiction). DEX requires this field.
     * @return the jurisdiction, which is the same as meta_ext_entity
     */
    @JsonProperty("jurisdiction")
    default String getJurisdiction() {
    	return getExtEntity();
    }
    
    /**
     * Gets the name of the user or service account that submitted the file (meta_username).
     * @return the user name
     */
    @JsonProperty("meta_username")
    String getUsername();
    /**
     * Sets the name of the user or service account that submitted the file (meta_username).
     * @param username the user name
     */
    void setUsername(String username);
    
    /**
     * Gets the object key that identifies the source file (meta_ext_objectkey).
     * @return the object key
     */
    @JsonProperty("meta_ext_objectkey")
    String getExtObjectKey();
    /**
     * Sets the object key that identifies the source file (meta_ext_objectkey).
     * @param extObjectKey the object key
     */
    void setExtObjectKey(String extObjectKey);
    
    /**
     * Gets the name of the submitted file (meta_ext_filename).
     * @return the file name
     */
    @JsonProperty("meta_ext_filename")
    String getFilename();
    /**
     * Sets the name of the submitted file (meta_ext_filename).
     * @param filename the file name
     */
    void setFilename(String filename);
    /**
     * Gets the received file name (received_filename). DEX requires this field.
     * @return the original name of the uploaded file
     */
    @JsonProperty("received_filename")
    default String getReceivedFilename() {
    	return getFilename();
    }
    /**
     * Gets the data stream route (data_stream_route). DEX requires this field.
     * @return "csv" for CSV files, else "other"
     */
    @JsonProperty("data_stream_route") 
    default String getDataStreamRoute() {
    	String v = StringUtils.defaultString(getFilename());
    	if (v.toLowerCase().endsWith(".csv")) {
    		return "csv";
    	}
    	return "other";
    }
    
    /**
     * Gets the submission period of the file (meta_ext_submissionperiod).
     * @return the submission period
     */
    @JsonProperty("meta_ext_submissionperiod")
    String getPeriod();
    /**
     * Sets the submission period of the file (meta_ext_submissionperiod).
     * @param period the submission period
     */
    void setPeriod(String period);
    
    /**
     * Gets the metadata schema version (meta_schema_version).
     * @return the schema version
     */
    @JsonProperty("meta_schema_version")
    String getSchemaVersion();
    /**
     * Sets the metadata schema version (meta_schema_version).
     * @param version the schema version
     */
    void setSchemaVersion(String version);
    
    /**
     * Tells if the file is a test file (meta_ext_testfile).
     * @return true if the file is a test file
     */
    @JsonProperty(TESTFILE)
    boolean isTestFile();
    /**
     * Sets the flag that marks the file as a test file (meta_ext_testfile).
     * @param testFile true if the file is a test file
     */
    void setTestFile(boolean testFile);
    
    // Our additions to NDLP Specification
    /**
     * Gets the ID of the destination route (izgw_route_id).
     * @return the route ID
     */
    @JsonProperty("izgw_route_id")
    String getRouteId();
    /**
     * Sets the ID of the destination route (izgw_route_id).
     * @param routeId the route ID
     */
    void setRouteId(String routeId);
    
    /**
     * Gets the path of the file at the destination (izgw_path).
     * @return the path
     */
    @JsonProperty("izgw_path")
    String getPath();
    /**
     * Sets the path of the file at the destination (izgw_path).
     * @param path the path
     */
    void setPath(String path);
    
    /**
     * Gets the IP address of the submitter (izgw_ipaddress).
     * @return the IP address
     */
    @JsonProperty("izgw_ipaddress")
    String getIpAddress();
    /**
     * Sets the IP address of the submitter (izgw_ipaddress).
     * @param ipAddress the IP address
     */
    void setIpAddress(String ipAddress);
    
    /**
     * Gets the size of the file in bytes (izgw_filesize).
     * @return the file size
     */
    @JsonProperty("izgw_filesize")
    long getFileSize();
    /**
     * Sets the size of the file in bytes (izgw_filesize).
     * @param fileSize the file size
     */
    void setFileSize(long fileSize);
    
    /**
     * Gets the date and time of the upload (izgw_uploaded_timestamp).
     * @return the upload date
     */
    @JsonProperty("izgw_uploaded_timestamp")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = RFC2616_DATE_FORMAT_PATTERN)
    Date getUploadedDate();
    /**
     * Sets the date and time of the upload (izgw_uploaded_timestamp).
     * @param uploadedDate the upload date
     */
    void setUploadedDate(Date uploadedDate);
    
    /**
     * Gets the file timestamp (meta_ext_file_timestamp). This is the same as the upload date.
     * @return the file timestamp
     */
    @JsonProperty("meta_ext_file_timestamp")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = RFC2616_DATE_FORMAT_PATTERN)
    default Date getFileTimestamp() {
    	return getUploadedDate();
    }
    
    /**
     * Gets the IZ Gateway event ID of the request (izgw_event_id).
     * @return the event ID
     */
    @JsonProperty("izgw_event_id")
    String getEventId();
    /**
     * Sets the IZ Gateway event ID of the request (izgw_event_id).
     * @param eventId the event ID
     */
    void setEventId(String eventId);
    
    /**
     * Gets the submission status (izgw_submission_status).
     * @return the submission status
     */
    @JsonProperty("izgw_submission_status")
    String getSubmissionStatus();
    /**
     * Sets the submission status (izgw_submission_status).
     * @param status the submission status
     */
    void setSubmissionStatus(String status);
    
    /**
     * Gets the submission location (izgw_submission_location).
     * @return the submission location
     */
    @JsonProperty("izgw_submission_location")
    String getSubmissionLocation();
    /**
     * Sets the submission location (izgw_submission_location).
     * @param location the submission location
     */
    void setSubmissionLocation(String location);


    /**
     * Compares the metadata fields of this object with the fields of another object.
     * @param o the object to compare
     * @return true if o is a Metadata object with the same field values
     */
    default boolean isEqual(Object o) {
        if (!(o instanceof Metadata)) {
            return false;
        }
        Metadata that = (Metadata)o;
        return 
            equals(this.getRouteId(), that.getRouteId()) &&
            equals(this.getDestinationId(), that.getDestinationId()) &&
            equals(this.getPeriod(), that.getPeriod()) &&
            equals(this.getExtEntity(), that.getExtEntity()) &&
            equals(this.getExtEvent(), that.getExtEvent()) &&
            equals(this.getExtEventType(), that.getExtEventType()) &&
            equals(this.getExtObjectKey(), that.getExtObjectKey()) &&
            equals(this.getExtSource(), that.getExtSource()) &&
            equals(this.getExtSourceVersion(), that.getExtSourceVersion()) &&
            equals(this.getFilename(), that.getFilename()) &&
            this.getFileSize() == that.getFileSize() &&
            equals(this.getUploadedDate(), that.getUploadedDate()) &&
            equals(this.getUsername(), that.getUsername()) &&
            equals(this.getIpAddress(), that.getIpAddress()) &&
            equals(this.getFilename(), that.getExtEntity()) &&
            equals(this.getPath(), that.getPath()) &&
            equals(this.getEventId(), that.getEventId());
    }
    
    /**
     * Compares two objects for equality. Two null values are equal.
     * @param a the first object
     * @param b the second object
     * @return true if the objects are equal
     */
    default boolean equals(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }
}