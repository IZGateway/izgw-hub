package gov.cdc.izgateway.ads;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;

import gov.cdc.izgateway.model.IDestination;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Primary implementation for the Metadata interface used by the ADS Service
 * 
 * @author Audacious Inquiry
 */
@Slf4j
@Data
public class MetadataImpl implements Metadata {
	private static final long serialVersionUID = 1L;
	private String routeId;
    private String period;
    private String extSource;
    private String extSourceVersion;
    private String extEvent;
    private String extEventType;
    private String extEntity;
    private String username;
    private String filename;
    private String extObjectKey;
    private String ipAddress;
    private String path;
    private String schemaVersion;
    private long fileSize;
    private Date uploadedDate;
	private String destinationId;
	private String eventId;
	private String submissionStatus;
	private String submissionLocation;
	private boolean isTestFile;
	@JsonIgnore
    private transient IDestination destination;

	/**
	 * Create a new metadata imlementation.
	 */
    public MetadataImpl() {
    }
    
	/**
	 * Copy an existing metadata into a new one.
	 * @param resp	The existing metadata
	 */
    public MetadataImpl(Metadata resp) {
        if (resp == null) {
            return;
        }
        routeId = resp.getRouteId();
        period = resp.getPeriod();
        extSource = resp.getExtSource();
        extSourceVersion = resp.getExtSourceVersion();
        extEvent = resp.getExtEvent();
        extEventType = resp.getExtEventType();
        extEntity = resp.getExtEntity();
        username = resp.getUsername(); 
        filename = resp.getFilename();
        extObjectKey = resp.getExtObjectKey();
        ipAddress = resp.getIpAddress();
        path = resp.getPath();
        schemaVersion = resp.getSchemaVersion();
        fileSize = resp.getFileSize();
        uploadedDate = resp.getUploadedDate();
        destinationId = resp.getDestinationId();
        eventId = resp.getEventId();
    	submissionStatus = resp.getSubmissionStatus();
    	submissionLocation = resp.getSubmissionLocation();
    	isTestFile = resp.isTestFile();
    	
        if (resp instanceof MetadataImpl r2) {
        	destination = r2.getDestination();
        }
    }

    /**
     * Set metadata fields by name
     * @param name	The field name
     * @param value The field value
     */
	public void set(String name, String value) {
		// TODO: Make this work from annotations
		switch (name) {
		case "meta_destination_id":
			setDestinationId(value);
			break;
		case "meta_ext_source":
			setExtSource(value);
			break;
		case "meta_ext_sourceversion":
			setExtSourceVersion(value);
			break;
		case "meta_ext_event":
			setExtEvent(value);
			break;
		case "meta_ext_event_type":
			setExtEventType(value);
			break;
		case "meta_ext_entity":
			setExtEntity(value);
			break;
		case "meta_username":
			setUsername(value);
			break;
		case "meta_ext_objectkey":
			setExtObjectKey(value);
			break;
		case "meta_ext_filename":
			setFilename(value);
			break;
		case "meta_ext_submissionperiod":
			setPeriod(value);
			break;
		case "meta_schema_version":
			setSchemaVersion(value);
			break;
		case Metadata.TESTFILE:
			setTestFile("yes".equalsIgnoreCase(value));
			break;
		case "izgw_route_id":
			setRouteId(value);
			break;
		case "izgw_path":
			setPath(value);
			break;
		case "izgw_ipaddress":
			setIpAddress(value);
			break;
		case "izgw_filesize":
			setFileSize(Long.parseLong(value));
			break;
		case "izgw_uploaded_timestamp":
			Date date;
			try {
				date = Metadata.RFC2616_DATE_FORMAT.parse(value);
				setUploadedDate(date);
			} catch (Exception e1) {
				log.warn("Could not parse date {} from blob metadata", value);
			}
			break;
		case "izgw_submission_status":
			setSubmissionStatus(value);
			break;
		case "izgw_submission_location":
			setSubmissionLocation(value);
			break;
		default:
			break;
		}
	}
	
	public void setExtSourceVersion(String version) {
		extSourceVersion = version;
    	setSchemaVersion(getVersion());
	}
	
	private static final List<String> MONTHS = Arrays.asList("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
	private static final List<String> QUARTERS = Arrays.asList("Q1", "Q2", "Q3", "Q4");
	/**
	 * Get the specified period in a Calendar class 
	 * @return	A calendar representing the reporting period date. 
	 */
	@JsonIgnore
	public Calendar getPeriodAsCalendar() {
		Calendar metaDate = Calendar.getInstance();
		metaDate.setTimeInMillis(System.currentTimeMillis());
		int year = -1;
		int month = -1;
		if (StringUtils.isNotEmpty(period)) {
			try {
				year = Integer.parseInt(StringUtils.substring(period, 0, 4));
			} catch (NumberFormatException ex) {
				// Ignore it.
			}
			if (StringUtils.containsIgnoreCase(getPeriod(), "Q")) {
				month = QUARTERS.indexOf(StringUtils.right(period.toUpperCase(), 2)) * 3;
			} else {
				month = MONTHS.indexOf(StringUtils.right(period.toUpperCase(), 3));
			}
		}
		// Set to first of month because if you run this based on "today"
		// and today happens to be 7/31, but your actual month is September, when
		// you change month to 9, then 9/31 will roll over to 10/1.
		metaDate.set(Calendar.DATE, 1);
		if (year >= 0) {
			metaDate.set(Calendar.YEAR, year);
		}
		if (month >= 0) {
			metaDate.set(Calendar.MONTH, month);
		}
		return metaDate;
	}
}
