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
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * This class implements the builder pattern to create Metadata for processing an ADS Request.
 * It handles all the business rules for validating content before building the Metadata object.
 */
public class MetadataBuilder {
	private static final String MONTH_PATTERN = "(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)";
    private static final String PERIOD_PATTERN = "^\\d{4}(Q[1-4]|-" + MONTH_PATTERN + "|((-\\d{2}){1,2}))$";
    /**
     * This is the facility id assigned to IZ Gateway by NDLP.
     */
    public static final String FACILITY_IZG = "IZG";
    private MetadataImpl meta = new MetadataImpl();
    private List<String> errors = new ArrayList<>();
    private String destUrl;
    @Getter
    @Setter
	private boolean metadataValidationEnabled = true;
    private static final String DEFAULT_SCHEMA_VERSION = "1.0";
	
    /**
     * Create a new MetadataBuider.
     */
    public MetadataBuilder() {
        meta.setExtSource("IZGW");
    }

    /**
     * Return the built metadata object.
     * @return the built metadata object.
     * @throws MetadataFault if any errors were founding during the building process.
     */
    public MetadataImpl build() throws MetadataFault {
        meta.setSchemaVersion(DEFAULT_SCHEMA_VERSION);
        meta.setEventId(MDC.get(EventIdMdcConverter.EVENT_ID_MDC_KEY));
        if (errors.isEmpty()) {
            return meta;
        }
        throw new MetadataFault(meta, errors.toArray(new String[0]));
    }
    
    /**
     * Returns true if there were any errors while building.
     * @return true if there were any errors while building
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    /**
     * Get the list of errors found in the metadata.
     * @return the list of errors found in the metadata
     */
    public List<String> getErrors() {
        return errors;
    }

    private static final Set<String> DEX_REPORT_TYPES = new LinkedHashSet<>(Arrays.asList(
    		"covidallmonthlyvaccination",
    		"influenzavaccination",
    		"routineimmunization",
    		"rsvprevention"
    		));
    static final String GENERIC = "genericImmunization";
    /**
     * Set the report type
     * @param reportType the report type
     * @return The metadata builder.
     */
    public MetadataBuilder setReportType(String reportType) {
    	// If it's one of the original report types, set it
    	// on meta_ext_event as well as meta_ext_event_type
    	if (DEX_REPORT_TYPES.contains(reportType.toLowerCase())) {
    		meta.setExtEvent(reportType);
    	} else {
    		meta.setExtEvent(GENERIC);
    		// Force V2 if Generic is used.
    		meta.setExtSourceVersion(Metadata.DEX_VERSION2);
    	}
        meta.setExtEventType(reportType);
        if (StringUtils.isBlank(reportType)) {
            errors.add("Report Type must be present and not empty");
            return this;
        }
        return this;
    }

    /**
     * Set the period associated with the report
     * @param period the period associated with the report, in the form YYYY-MMM or YYYYQ[1-4]
     * @return	The metadata builder
     */
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

    /**
     * Set the filename of the report
     * The filename structure is well defined for each report type and is used
     * to valid metadata parameters.
     * @param filename	The filename.
     * @return	The MetadataBuilder
     */
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
		// Filetype validation isn't relevant when type is genericImmunization. These could be anything and we won't know.
		if (!GENERIC.equalsIgnoreCase(meta.getExtEvent()) && !pf.getFiletype().equalsIgnoreCase(meta.getExtEvent())) {
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

    /**
     * Set the route identifier
     * @param dests	A reference to the destination service to validate routes
     * @param routeId	The route
     * @return	The MetadataBuilder
     */
    public MetadataBuilder setRouteId(IDestinationService dests, String routeId) {
        meta.setRouteId(routeId);
        if (StringUtils.isBlank(routeId)) {
            errors.add("Route ID must be present and not empty");
        }
        // Ensure that some value is set in ExtSourceVersion 
        meta.setExtSourceVersion(Metadata.DEX_VERSION2);

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
            // Set the value for ExtSourceVersion based on version of DEX Endpoint
	        switch (dest.getDestVersion()) {
	        case ADSController.IZGW_ADS_VERSION1:
	        	meta.setExtSourceVersion(Metadata.DEX_VERSION1);
	        	break;
	        default:
	        case ADSController.IZGW_ADS_VERSION2:
	        	meta.setExtSourceVersion(Metadata.DEX_VERSION2);
	        	break;
	        }
        }
        return this;
    }

    /**
     * Set the provenance of the report
     * @param facilityId	The facility identifier
     * @param transactionData	The source of user metadata (it has the certificate name), and IP address
     * @return	The MetadataBuilder
     */
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
    
    /**
     * Set the intended destination for the report.
     * @param destinationId the intended destination for the report
     * @return The MetadataBuilder
     */
    public MetadataBuilder setDestinationId(String destinationId) {
        meta.setDestinationId(destinationId);
        return this;
    }

    /**
     * Set the file size
     * @param size the file size
     * @return The MetadataBuilder
     */
    public MetadataBuilder setFileSize(long size) {
        meta.setFileSize(size);
        return this;
    }

    /**
     * Get the destination for the upload
     *  
     * @return The MetadataBuilder
     */
    public String getDestUrl() {
        return destUrl;
    }

}
