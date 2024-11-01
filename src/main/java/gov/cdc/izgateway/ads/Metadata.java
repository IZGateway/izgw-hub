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
	"meta_destination_id", 
	"meta_ext_source", 
	"meta_ext_sourceversion", 
	"meta_ext_event", 
	"meta_ext_entity meta_ext_objectkey",
	"meta_ext_filename", 
	"meta_ext_submissionperiod", 
	"meta_schema_version", 
	"izgw_route_id", 
	"izgw_ipaddress", 
	"izgw_filesize", 
	"izgw_path", 
	"izgw_uploaded_timestamp",
	"izgw_event_id"
})
public interface Metadata extends Serializable {
	
	public static final String RFC2616_DATE_FORMAT_PATTERN = "EEE, dd MMM yyyy HH:mm:ss zzz";
	public static final FastDateFormat RFC2616_DATE_FORMAT = FastDateFormat.getInstance(RFC2616_DATE_FORMAT_PATTERN);
	public static final String DEX_VERSION1 = "V2023-09-01";
	public static final String DEX_VERSION2 = "V2024-09-04";

	/*
	 * version	New field
	 *	DEX required	Add field
	 *  Allowed value: 2.0
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
	
    @JsonProperty("meta_destination_id")
    String getDestinationId();
    void setDestinationId(String destId);
    
    @JsonProperty("meta_ext_source")
    String getExtSource();
    void setExtSource(String extSource);
    /*
     * sender_id	New field
     * DEX required	Add field
     * Allowed value: IZGW
     */
    @JsonProperty("sender_id")
    default String getSenderId() {
    	return getExtSource();
    }

    @JsonProperty("meta_ext_sourceversion")
    String getExtSourceVersion();
    void setExtSourceVersion(String extSourceVersion);
    
    @JsonProperty("meta_ext_event")
    String getExtEvent();
    void setExtEvent(String extEvent);

    /*
     * New with DEX2.0
     * Values generally same as meta_ext_event, but allows other
     * forms of submissions to be passed through to NDLP
     */
    @JsonProperty("meta_ext_event_type")
    String getExtEventType();
    void setExtEventType(String extEventTyype);
    /*
     * data_stream_id	New field
	 * DEX required	Add field
	 * Allowed values:
	 * -	routine-immunization
	 * -	influenza-vaccination
	 * -	rsv-prevention
	 * -	covid-all-monthly-vaccination
	 * -	covid-bridge-vaccination
	 */
    @JsonProperty("data_stream_id")
    default String getDataStreamId() {
    	String value = getExtEvent();
    	switch (value.toLowerCase()) {
		case "routineimmunization":
    		return "routine-immunization";
		case "influenzavaccination":
    		return "influenza-vaccination";
		case "rsvprevention":
    		return "rsv-prevention";
		case "covidallmonthlyvaccination":
			return "covid-all-monthly-vaccination";
		case "covidbridgevaccination":
			return "covid-bridge-vaccination";
		case "genericimmunization":
			return "generic-immunization";
		default:
			return value;
    	}
    }
    
    @JsonProperty("meta_ext_entity")
    String getExtEntity();
    void setExtEntity(String extEntity);
    /* 
     * data_producer_id	New field
     * DEX required	Add field
     * Allowed values: use existing values for meta_ext_entity
     */
    @JsonProperty("data_producer_id")
    default String getDataProducerId() {
    	return getExtEntity();
    }
    /* jurisdiction	New field
     * DEX required	Add field
     * Allowed values: use existing values for meta_ext_entity
     */
    @JsonProperty("jurisdiction")
    default String getJurisdiction() {
    	return getExtEntity();
    }
    
    @JsonProperty("meta_username")
    String getUsername();
    void setUsername(String username);
    
    @JsonProperty("meta_ext_objectkey")
    String getExtObjectKey();
    void setExtObjectKey(String extObjectKey);
    
    @JsonProperty("meta_ext_filename")
    String getFilename();
    void setFilename(String filename);
    /*
     * received_filename	New field
     * DEX required	Add field
     * Allowed value: original filename uploaded
     */
    @JsonProperty("received_filename")
    default String getReceivedFilename() {
    	return getFilename();
    }
    /*
     * data_stream_route	New field
     * DEX required	Add field
     * Allowed values:
     * -	csv for CSV files
     * -	other for all other file types
     */
    @JsonProperty("data_stream_route") 
    default String getDataStreamRoute() {
    	String v = StringUtils.defaultString(getFilename());
    	if (v.toLowerCase().endsWith(".csv")) {
    		return "csv";
    	}
    	return "other";
    }
    
    @JsonProperty("meta_ext_submissionperiod")
    String getPeriod();
    void setPeriod(String period);
    
    @JsonProperty("meta_schema_version")
    String getSchemaVersion();
    void setSchemaVersion(String version);
    
    // Our additions to NDLP Specification
    @JsonProperty("izgw_route_id")
    String getRouteId();
    void setRouteId(String routeId);
    
    @JsonProperty("izgw_path")
    String getPath();
    void setPath(String path);
    
    @JsonProperty("izgw_ipaddress")
    String getIpAddress();
    void setIpAddress(String ipAddress);
    
    @JsonProperty("izgw_filesize")
    long getFileSize();
    void setFileSize(long fileSize);
    
    @JsonProperty("izgw_uploaded_timestamp")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = RFC2616_DATE_FORMAT_PATTERN)
    Date getUploadedDate();
    void setUploadedDate(Date uploadedDate);
    
    @JsonProperty("meta_ext_file_timestamp")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = RFC2616_DATE_FORMAT_PATTERN)
    default Date getFileTimestamp() {
    	return getUploadedDate();
    }
    
    @JsonProperty("izgw_event_id")
    String getEventId();
    void setEventId(String eventId);
    
    @JsonProperty("izgw_submission_status")
    String getSubmissionStatus();
    void setSubmissionStatus(String status);
    
    @JsonProperty("izgw_submission_location")
    String getSubmissionLocation();
    void setSubmissionLocation(String location);


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