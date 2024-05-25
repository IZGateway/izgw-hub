package gov.cdc.izgateway.ads;

import gov.cdc.izgateway.logging.event.EventIdMdcConverter;
import gov.cdc.izgateway.logging.event.TransactionData;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.service.IDestinationService;
import lombok.Getter;
import lombok.Setter;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

/**
 * This class implements the builder pattern to create Metadata for processing an ADS Request.
 * It handles all the business rules for validating content before building the Metadata object.
 */
public class MetadataBuilder {
	private static final String MONTH_PATTERN = "(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)";
    private static final String PERIOD_PATTERN = "^\\d{4}(Q[1-4]|-" + MONTH_PATTERN + "|((-\\d{2}){1,2}))$";
    public static final String FACILITY_IZG = "IZG";
    private MetadataImpl meta = new MetadataImpl();
    private List<String> errors = new ArrayList<>();
    private String destUrl;
    @Getter
    @Setter
	private boolean metadataValidationEnabled = true;
    private static final String DEFAULT_SCHEMA_VERSION = "1.0";
	
    public MetadataBuilder() {
        meta.setExtSource("IZGW");
    }

    public MetadataImpl build() throws MetadataFault {
        meta.setSchemaVersion(DEFAULT_SCHEMA_VERSION);
        meta.setEventId(MDC.get(EventIdMdcConverter.EVENT_ID_MDC_KEY));
        if (errors.isEmpty()) {
            return meta;
        }
        throw new MetadataFault(meta, errors.toArray(new String[0]));
    }
    
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    public List<String> getErrors() {
        return errors;
    }

    public MetadataBuilder setReportType(String reportType){
        meta.setExtEvent(reportType);
        if (StringUtils.isBlank(reportType)) {
            errors.add("Report Type must be present and not empty");
            return this;
        }
        return this;
    }

    public MetadataBuilder setPeriod(String period) {
        meta.setPeriod(period);
        if (StringUtils.isBlank(period)) {
            errors.add("Period must be present and not empty");
            return this;
        }
        
        // Normalize it
        period = period.trim().toUpperCase();
        meta.setPeriod(period);
        if (!period.matches(PERIOD_PATTERN)) {
            errors.add(String.format("Period (%s) is not valid.  It must be in the form YYYYQ# or YYYY-MMM or YYYY-MM-DD.", period));
        }
        return this;
    }

    public MetadataBuilder setFilename(String filename) {
    	filename = filename.trim();
        meta.setFilename(filename);
        if (!ADSUtils.validateFilename(filename)) {
            errors.add(String.format("Filename (%s) is invalid. Characters must be in the range of [0x20, 0xD7FF] and must not include  *, \\, ?, >, <, :, |, /, \\ or <DEL>", filename));
        } 
        if (isMetadataValidationEnabled()) {
        	validateMetadata(filename);
        }
        return this;
    }

	/**
     * Set messageId from passed in messageId value.  If it is already a UUID,
     * use it, otherwise hash the string 
     * 
     * @param messageId	The messageId
     * @return	The metadata builder.
     */
    public MetadataBuilder setMessageId(String messageId) {
    	UUID uuid = null;
    	if (messageId == null || messageId.isEmpty()) {
    		uuid = UUID.randomUUID();
    	} else {
	    	try {
	    		uuid = UUID.fromString(messageId);
	    	} catch (IllegalArgumentException ex) {
	    		// Not a valid UUID, set from string hashCode
	    		uuid = UUID.nameUUIDFromBytes(messageId.getBytes(StandardCharsets.UTF_8));
	    	}
    	}
        meta.setExtObjectKey(uuid.toString());
        return this;
    }
    
    private void validateMetadata(String filename) {
    	ParsedFilename pf = ParsedFilename.parse(filename, errors);
		if (!pf.getEntityId().equalsIgnoreCase(meta.getExtEntity())) {
			errors.add(String.format("Entity ID (%s) does not match Entity (%s) in filename (%s)", meta.getExtEntity(), pf.getEntityId(), meta.getFilename()));
		}
		if (!pf.getFiletype().equalsIgnoreCase(meta.getExtEvent())) {
			errors.add(String.format("File type (%s) does not match file type (%s) in filename (%s)", meta.getExtEvent(), pf.getFiletype(), meta.getFilename()));
		}
		Calendar cal = Calendar.getInstance();
		if (pf.getDate() != null) {
			cal.setTime(pf.getDate());
		}
		Calendar metaDate = meta.getPeriodAsCalendar(); 
		int divisor = pf.getFiletype().equals(ParsedFilename.ROUTINE_IMMUNIZATION) ? 3 : 1;
		if (cal.get(Calendar.MONTH) / divisor != metaDate.get(Calendar.MONTH) / divisor ||
			cal.get(Calendar.YEAR) != metaDate.get(Calendar.YEAR)
		) {
			errors.add(String.format("File date from filename (%s) does not match period (%s)", meta.getFilename(), meta.getPeriod()));
		}
	}

    public MetadataBuilder setRouteId(IDestinationService dests, String routeId) {
        meta.setRouteId(routeId);
        if (StringUtils.isBlank(routeId)) {
            errors.add("Route ID must be present and not empty");
        }
        IDestination dest = dests.findByDestId(routeId.trim().toLowerCase());
        meta.setDestination(dest);
        if (dest == null) {
            errors.add(String.format("Destination ID (%s) is not valid",  routeId));
        } else if (!dest.isDex()) {
            errors.add(String.format("Destination ID (%s) is not a valid DEX endpoint", routeId));
        } else {
            // Normalize it
            meta.setRouteId(dest.getDestId());
            destUrl = dest.getDestUri();
            setDestinationId("ndlp");
        }
        return this;
    }

    public MetadataBuilder setProvenance(String facilityId, TransactionData transactionData) {
        meta.setUsername(transactionData.getSource().getCommonName());
        meta.setIpAddress(transactionData.getSource().getIpAddress());
        
        if (StringUtils.isBlank(facilityId)) {
            errors.add("Facility ID must be present and not empty");
        } else {
            facilityId = facilityId.trim().toUpperCase();
            if (facilityId.trim().length() != 3 || 
                !(facilityId.endsWith("A") || facilityId.equals(FACILITY_IZG))) {
                errors.add(
                    String.format("Facility ID (%s) is not valid.  It must in the form of XXA.", facilityId)
                );
            }
        }
        String org = ADSUtils.mapFacilityId(facilityId, transactionData.getSource().getOrganization());
        meta.setExtEntity(org);

        if (org == null) {
            errors.add("FacilityID cannot be mapped from " + facilityId);
        } else if (!ADSUtils.isFacilityIdValidForSender(facilityId, transactionData.getSource().getOrganization())) {
            errors.add("Invalid FacilityID: " + facilityId);
        }

        return this;
    }
    
    public MetadataBuilder setDestinationId(String destinationId) {
        meta.setDestinationId(destinationId);
        return this;
    }

    public MetadataBuilder setFileSize(long size) {
        meta.setFileSize(size);
        return this;
    }

    public String getDestUrl() {
        return destUrl;
    }

}
