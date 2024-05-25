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

@Slf4j
@Data
public class MetadataImpl implements Metadata {
	private static final long serialVersionUID = 1L;
	private String routeId;
    private String period;
    private String extSource;
    private String extSourceVersion;
    private String extEvent;
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
	@JsonIgnore
    private transient IDestination destination;
    
    public MetadataImpl() {
    }
    
    public MetadataImpl(Metadata resp) {
        if (resp == null) {
            return;
        }
        routeId = resp.getRouteId();
        period = resp.getPeriod();
        extSource = resp.getExtSource();
        extSourceVersion = resp.getExtSourceVersion();
        extEvent = resp.getExtEvent();
        extEntity = resp.getExtEntity();
        username = resp.getUsername(); 
        ipAddress = resp.getIpAddress();
        filename = resp.getFilename();
        extObjectKey = resp.getExtObjectKey();
        fileSize = resp.getFileSize();
        uploadedDate = resp.getUploadedDate();
        path = resp.getPath();
        schemaVersion = resp.getSchemaVersion();
        destinationId = resp.getDestinationId();
        eventId = resp.getEventId();
        if (resp instanceof MetadataImpl r2) {
        	destination = r2.getDestination();
        }
    }

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
		default:
			break;
		}
	}

	private static final List<String> MONTHS = Arrays.asList("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
	private static final List<String> QUARTERS = Arrays.asList("Q1", "Q2", "Q3", "Q4");
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
		
		if (year >= 0) {
			metaDate.set(Calendar.YEAR, year);
		}
		if (month >= 0) {
			metaDate.set(Calendar.MONTH, month);
		}
		return metaDate;
	}
}
