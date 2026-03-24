package gov.cdc.izgateway.ads;

import gov.cdc.izgateway.ads.util.FilenameValidationResult;
import gov.cdc.izgateway.ads.util.FilenameValidator;
import gov.cdc.izgateway.logging.event.EventIdMdcConverter;
import gov.cdc.izgateway.logging.event.TransactionData;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IFileType;
import gov.cdc.izgateway.service.IAccessControlService;
import gov.cdc.izgateway.service.IDestinationService;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
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
    private static final String DEFAULT_SCHEMA_VERSION = "2.0";

    /** Optional access control service used to look up registered file types. */
    private final IAccessControlService accessControlService;

    static final String GENERIC = "genericImmunization";

    /**
     * Create a new MetadataBuilder without file-type lookup support.
     * Report-type metadata will be computed purely from the report type name.
     */
    public MetadataBuilder() {
        this(null);
    }

    /**
     * Create a new MetadataBuilder with access to the file-type registry.
     * When {@code accessControlService} is non-null, {@link #setReportType(String)} will
     * validate the report type against the registry and warn if it is unrecognised.
     *
     * @param accessControlService the service used to look up registered file types, or
     *                             {@code null} to skip registry validation
     */
    public MetadataBuilder(IAccessControlService accessControlService) {
        this.accessControlService = accessControlService;
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
        if (errors.isEmpty() || !isMetadataValidationEnabled()) {
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

    /**
     * Set the report type and compute all derived metadata fields from it.
     * <p>
     * If an {@link IAccessControlService} was supplied at construction time the report type
     * is looked up in the file-type registry.  A warning is logged when the type is not
     * found, but processing continues so that unregistered types still work.
     * </p>
     * <p>
     * Derived fields set here:
     * <ul>
     *   <li>{@code meta_ext_event} – from {@code computeMetaExtEvent()}</li>
     *   <li>{@code meta_ext_event_type} – always the raw report type name</li>
     *   <li>{@code data_stream_id} – from {@link IAccessControlService#computeDataStreamId(String)}</li>
     * </ul>
     * </p>
     *
     * @param reportType the report type name
     * @return this builder
     */
    public MetadataBuilder setReportType(String reportType) {
        if (StringUtils.isBlank(reportType)) {
            errors.add("Report Type must be present and not empty");
            return this;
        }

        // Validate against the registered file-type registry when available.
        if (accessControlService != null) {
            IFileType fileType = accessControlService.getFileType(reportType);
            if (fileType == null) {
                log.warn("Report type '{}' is not registered in the file-type registry", reportType);
            }
        }

        // Compute all metadata fields from the reportType name.
        String metaExtEvent = computeMetaExtEvent(reportType);
        meta.setExtEvent(metaExtEvent);
        meta.setExtEventType(reportType);
        meta.setDataStreamId(IAccessControlService.computeDataStreamId(reportType));

        // Force V2 if Generic is used.
        if (GENERIC.equals(metaExtEvent)) {
            meta.setExtSourceVersion(Metadata.DEX_VERSION2);
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
        ParsedFilename pf = ParsedFilename.parse(filename, errors);
        meta.setTestFile(pf.isTestfile());

        if (isMetadataValidationEnabled()) {
            validateMetadata(pf);
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
    
    private void validateMetadata(ParsedFilename pf) {
        String filename = meta.getFilename();
        String ext = org.apache.commons.lang3.StringUtils.substringAfterLast(filename, ".").toLowerCase();

        if ("csv".equals(ext)) {
            // CSV files use the new structured FilenameValidator.
            String periodType = computePeriodType(meta.getExtEvent());
            FilenameValidationResult result = FilenameValidator.validate(
                    filename,
                    periodType,
                    meta.getExtEntity(),
                    meta.getPeriod());
            errors.addAll(result.getErrors());
        } else {
            // ZIP files (routine immunization) use a different filename format
            // (EEE_YYYYMMDD_yyyymmdd_Z.zip) that FilenameValidator does not handle.
            // Retain the original ParsedFilename-based entity and date checks.
            validateZipMetadata(pf);
        }
    }

    /**
     * Validate metadata for ZIP (routine immunization) files using the original
     * ParsedFilename logic. ZIP filenames follow a different structure than CSV files:
     * {@code EEE_YYYYMMDD_yyyymmdd_Z.zip} (entity_startdate_submissiondate_zone).
     *
     * @param pf the parsed filename
     */
    private void validateZipMetadata(ParsedFilename pf) {
        if (!pf.getEntityId().equalsIgnoreCase(meta.getExtEntity())) {
            errors.add(String.format("Entity ID (%s) does not match Entity (%s) in filename (%s)",
                    meta.getExtEntity(), pf.getEntityId(), meta.getFilename()));
        }
        // Filetype validation isn't relevant when type is genericImmunization.
        if (!GENERIC.equalsIgnoreCase(meta.getExtEvent()) && !pf.getFiletype().equalsIgnoreCase(meta.getExtEvent())) {
            errors.add(String.format("File type (%s) does not match file type (%s) in filename (%s)",
                    meta.getExtEvent(), pf.getFiletype(), meta.getFilename()));
        }
        Calendar cal = Calendar.getInstance();
        if (pf.getDate() != null) {
            cal.setTime(pf.getDate());
        }
        Calendar metaDate = meta.getPeriodAsCalendar();
        int divisor = pf.getFiletype().equals(ParsedFilename.ROUTINE_IMMUNIZATION) ? 3 : 1;
        if (!checkDate(cal, metaDate, divisor)) {
            // Failed first time through, try just one day later on date from file name.
            cal.add(Calendar.DAY_OF_MONTH, 1);
            if (!checkDate(cal, metaDate, divisor)) {
                errors.add(String.format("File date from filename (%s) does not match period (%s)",
                        meta.getFilename(), meta.getPeriod()));
            }
        }
    }

    private boolean checkDate(Calendar cal, Calendar metaDate, int divisor) {
        return cal.get(Calendar.MONTH) / divisor == metaDate.get(Calendar.MONTH) / divisor
                && cal.get(Calendar.YEAR) == metaDate.get(Calendar.YEAR);
    }

    /**
     * Compute the meta_ext_event value from the fileTypeName.
     * Special case: "farmerFlu" becomes "farmerFluVaccination" for backward compatibility.
     *
     * @param fileTypeName the file type name
     * @return the computed meta_ext_event value
     */
    private static String computeMetaExtEvent(String fileTypeName) {
        if (fileTypeName == null || fileTypeName.isEmpty()) {
            return GENERIC;
        }
        // Special case for backward compatibility
        if ("farmerFlu".equalsIgnoreCase(fileTypeName)) {
            return "farmerFluVaccination";
        }
        return fileTypeName;
    }

    /**
     * Compute the period type (MONTHLY, QUARTERLY, or BOTH) from the fileTypeName.
     * Rules:
     * <ul>
     *   <li>Contains "quarter" → QUARTERLY</li>
     *   <li>Starts with "ri" or equals "routineImmunization" → QUARTERLY</li>
     *   <li>Equals "genericImmunization" → BOTH</li>
     *   <li>Default → MONTHLY</li>
     * </ul>
     *
     * @param fileTypeName the file type name
     * @return the computed period type
     */
    static String computePeriodType(String fileTypeName) {
        if (fileTypeName == null || fileTypeName.isEmpty()) {
            return "MONTHLY";
        }
        String lower = fileTypeName.toLowerCase();
        if (lower.contains("quarter")) {
            return "QUARTERLY";
        }
        if (lower.startsWith("ri") || lower.equals("routineimmunization")) {
            return "QUARTERLY";
        }
        if (GENERIC.equals(fileTypeName)) {
            return "BOTH";
        }
        return "MONTHLY";
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
        } else if (!dest.isDex() && !dest.isAzure()) {
            errors.add(String.format("Destination ID (%s) is not a valid ADS endpoint", routeId));
        } else {
            // Normalize it
            meta.setRouteId(dest.getDestId());
            destUrl = dest.getDestUri();
            setDestinationId("ndlp");
            // Set the value for ExtSourceVersion based on version of DEX Endpoint
	        switch (dest.getDestVersion()) {
	        case IDestination.IZGW_ADS_VERSION1:
	        	meta.setExtSourceVersion(Metadata.DEX_VERSION1);
	        	break;
	        case IDestination.IZGW_ADS_VERSION2, IDestination.IZGW_AZURE_VERSION1:
	        default:
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
                !(facilityId.endsWith("A") || facilityId.equals(FACILITY_IZG) || facilityId.equals("NIH"))) {
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