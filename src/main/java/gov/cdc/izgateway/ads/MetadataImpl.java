package gov.cdc.izgateway.ads;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

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
	/** The ID of the destination route (izgw_route_id). */
	private String routeId;
    /** The submission period of the file (meta_ext_submissionperiod). */
    private String period;
    /** The source of the data (meta_ext_source). */
    private String extSource;
    /** The version of the specification (meta_ext_sourceversion). */
    private String extSourceVersion;
    /** The event, which selects the storage container (meta_ext_event). */
    private String extEvent;
    /** The event type (meta_ext_event_type). */
    private String extEventType;
    /** The submitting entity code (meta_ext_entity). */
    private String extEntity;
    /** The name of the user or service account that submitted the file (meta_username). */
    private String username;
    /** The name of the submitted file (meta_ext_filename). */
    private String filename;
    /** The object key that identifies the source file (meta_ext_objectkey). */
    private String extObjectKey;
    /** The IP address of the submitter (izgw_ipaddress). */
    private String ipAddress;
    /** The path of the file at the destination (izgw_path). */
    private String path;
    /** The metadata schema version (meta_schema_version). */
    private String schemaVersion;
    /** The size of the file in bytes (izgw_filesize). */
    private long fileSize;
    /** The date and time of the upload (izgw_uploaded_timestamp). */
    private Date uploadedDate;
	/** The destination ID (meta_destination_id). */
	private String destinationId;
	/** The IZ Gateway event ID of the request (izgw_event_id). */
	private String eventId;
	/** The submission status (izgw_submission_status). */
	private String submissionStatus;
	/** The submission location (izgw_submission_location). */
	private String submissionLocation;
	/** True if the file is a test file (meta_ext_testfile). */
	private boolean isTestFile;
	/** Computed data stream ID stored at report-type resolution time. Null until set. */
	private String dataStreamId;
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
        	dataStreamId = r2.getDataStreamId();
        }
    }

    /**
     * Set metadata fields by name
     * @param name	The field name
     * @param value The field value
     */
	public void set(String name, String value) {
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
		case "data_stream_id":
			setDataStreamId(value);
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

	/**
	 * Returns the stored data stream ID if one has been computed and stored, otherwise
	 * falls back to the default computation in the {@link Metadata} interface.
	 * @return the data stream ID for this submission
	 */
	@Override
	public String getDataStreamId() {
		if (dataStreamId != null) {
			return dataStreamId;
		}
		return Metadata.super.getDataStreamId();
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
			if (Strings.CI.contains(getPeriod(), "Q")) {
				month = QUARTERS.indexOf(StringUtils.right(period.toUpperCase(), 2)) * 3;
			} else if (MetadataBuilder.ANNUAL.equals(MetadataBuilder.classifyPeriodFrequency(period.toUpperCase()))) {
				// Annual periods (bare YYYY) have no month component. Default to January
				// instead of leaving this Calendar's month at whatever "now" happened to
				// be -- callers that compare by month (rather than ignoring it, as
				// MetadataBuilder.checkDate() does for annual types) get a deterministic
				// value instead of one that varies by time of day.
				month = 0;
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
