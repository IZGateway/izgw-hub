package gov.cdc.izgateway.ads;

import gov.cdc.izgateway.ads.util.CsvFilenameComponents;
import gov.cdc.izgateway.ads.util.CsvFilenameValidator;
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
import org.apache.commons.lang3.Strings;
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
    // Trailing group is optional so a bare 4-digit year (annual) validates too.
    private static final String PERIOD_PATTERN = "^\\d{4}(Q[1-4]|-" + MONTH_PATTERN + "|((-\\d{2}){1,2}))?$";
    // MONTHLY/QUARTERLY reference CsvFilenameValidator's canonical constants rather than
    // duplicating the literals; ANNUAL has no CSV equivalent, so it's defined here.
    // Package-private (not private) so MetadataImpl.getPeriodAsCalendar() can share them.
    static final String MONTHLY = CsvFilenameValidator.FREQ_MONTHLY;
    static final String QUARTERLY = CsvFilenameValidator.FREQ_QUARTERLY;
    static final String ANNUAL = "Annual";
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
     * is looked up in the file-type registry using a case-insensitive match.  When a match
     * is found the supplied value is replaced with the canonical name from
     * {@link IFileType#getFileTypeName()} before any computation, ensuring correct camelCase
     * casing for downstream hyphenation.  A warning is logged when the type is not found,
     * but processing continues so that unregistered types still work.
     * </p>
     * <p>
     * Derived fields set here:
     * <ul>
     *   <li>{@code meta_ext_event} – from {@code computeMetaExtEvent()}</li>
     *   <li>{@code meta_ext_event_type} – the canonical report type name</li>
     *   <li>{@code data_stream_id} – computed lazily via {@link Metadata#getDataStreamId()}</li>
     * </ul>
     *
     * @param reportType the report type name (case-insensitive; normalized to registry casing)
     * @return this builder
     */
    public MetadataBuilder setReportType(String reportType) {
        if (StringUtils.isBlank(reportType)) {
            errors.add("Report Type must be present and not empty");
            return this;
        }

        // Validate against the registered file-type registry when available.
        // Normalize to canonical casing from the registry so that computeDataStreamId()
        // always receives the correctly-cased camelCase string it needs for correct hyphenation.
        if (accessControlService != null) {
            IFileType fileType = accessControlService.getFileType(reportType);
            if (fileType == null) {
                log.warn("Report type '{}' is not registered in the file-type registry", reportType);
            } else {
                reportType = fileType.getFileTypeName();
            }
        }

        // Compute all metadata fields from the reportType name.
        String metaExtEvent = computeMetaExtEvent(reportType);
        meta.setExtEvent(metaExtEvent);
        meta.setExtEventType(reportType);

        // Force V2 if Generic is used.
        if (GENERIC.equals(metaExtEvent)) {
            meta.setExtSourceVersion(Metadata.DEX_VERSION2);
        }

        return this;
    }

    /**
     * Set the period associated with the report.
     * <p>
     * In addition to validating the period's structural format, this cross-checks the
     * period's shape (monthly, quarterly, or annual) against the established submission
     * frequency for the already-set report type (see {@link #setReportType(String)},
     * which must be called first). The check is skipped for {@code genericImmunization}
     * and for the legacy numeric {@code YYYY-MM}/{@code YYYY-MM-DD} period form, which
     * predates this cross-check and is not one of the three recognized frequency shapes.
     * </p>
     *
     * @param period the period associated with the report, in the form YYYY-MMM, YYYYQ[1-4], or YYYY
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
            String reportType = meta.getExtEvent();
            String expectedForm = GENERIC.equalsIgnoreCase(reportType)
                    ? "YYYYQ#, YYYY-MMM, or YYYY"
                    : periodFormatExample(establishedFrequency(reportType));
            errors.add(String.format(
                    "Period (%s) is not valid.  Report type (%s) requires a period in the form %s.",
                    period, reportType, expectedForm));
            return this;
        }

        String actualFrequency = classifyPeriodFrequency(period);
        String reportType = meta.getExtEvent();
        if (actualFrequency != null && !GENERIC.equalsIgnoreCase(reportType)) {
            String expectedFrequency = establishedFrequency(reportType);
            if (!actualFrequency.equals(expectedFrequency)) {
                errors.add(String.format(
                        "Period (%s) is %s-formatted, but report type (%s) requires a %s period.",
                        period, actualFrequency, reportType, expectedFrequency));
            }
        }
        return this;
    }

    /**
     * Classify a structurally-valid period string's shape.
     *
     * @param period the normalized (uppercase, trimmed) period string; must already be
     *               known to match {@link #PERIOD_PATTERN}
     * @return {@link #QUARTERLY}, {@link #MONTHLY}, or {@link #ANNUAL}; or {@code null}
     *         for the legacy numeric {@code YYYY-MM}/{@code YYYY-MM-DD} form, which is
     *         not one of the three recognized frequency shapes
     */
    static String classifyPeriodFrequency(String period) {
        if (period.matches("\\d{4}Q[1-4]")) {
            return QUARTERLY;
        }
        if (period.matches("\\d{4}-" + MONTH_PATTERN)) {
            return MONTHLY;
        }
        if (period.matches("\\d{4}")) {
            return ANNUAL;
        }
        return null;
    }

    /**
     * Determine the established submission frequency for a canonical report type.
     * Mirrors the eligibility logic in {@link #isAnnual(String)}: annual aggregate
     * types take priority over the quarterly check since their names also start with
     * {@code "ri"}.
     *
     * @param reportType the canonical report type name (e.g. {@code meta_ext_event})
     * @return {@link #ANNUAL}, {@link #QUARTERLY}, or {@link #MONTHLY} (the default)
     */
    static String establishedFrequency(String reportType) {
        if (isAnnual(reportType)) {
            return ANNUAL;
        }
        if ("routineImmunization".equalsIgnoreCase(reportType) || Strings.CI.contains(reportType, "quarter")) {
            return QUARTERLY;
        }
        return MONTHLY;
    }

    /**
     * Describe, with an example, the single period format expected for a given
     * established frequency. Used to give a specific, actionable error message
     * instead of listing every structurally-valid form.
     *
     * @param frequency {@link #ANNUAL}, {@link #QUARTERLY}, or {@link #MONTHLY}
     * @return a human-readable format description with an example value
     */
    private static String periodFormatExample(String frequency) {
        if (ANNUAL.equals(frequency)) {
            return "YYYY (e.g. 2026)";
        }
        if (QUARTERLY.equals(frequency)) {
            return "YYYYQ# (e.g. 2026Q1)";
        }
        return "YYYY-MMM (e.g. 2026-FEB)";
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

        String ext = StringUtils.substringAfterLast(filename, ".").toLowerCase();
        boolean isCsv = "csv".equals(ext);

        if (isCsv) {
            // CSV validation is handled entirely by CsvFilenameValidator.
            // Use CsvFilenameValidator.parseFilename() to obtain the isTestFile flag only.
            CsvFilenameComponents fc = CsvFilenameValidator.parseFilename(filename);
            meta.setTestFile(fc != null && fc.isTestFile());
            if (isMetadataValidationEnabled()) {
                validateCsvMetadata();
            }
        } else {
            // ZIP (routine immunization) files: use ZipFilenameComponents for parsing and validation.
            ZipFilenameComponents zfc = ZipFilenameComponents.parse(filename, errors);
            meta.setTestFile(zfc.isTestfile());
            if (isMetadataValidationEnabled()) {
                validateZipMetadata(zfc);
            }
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
    
    /**
     * Validate metadata for CSV (RIVER) files using {@link CsvFilenameValidator}.
     * All inputs come from previously set {@code meta} fields.
     */
    private void validateCsvMetadata() {
        CsvFilenameComponents result = CsvFilenameValidator.validate(
                meta.getFilename(),
                meta.getExtEntity(),
                meta.getPeriod());
        errors.addAll(result.getErrors());
    }

    /**
     * Validate metadata for ZIP files (routine immunization or annual aggregate reports)
     * using {@link ZipFilenameComponents}. ZIP filenames follow a different structure
     * than CSV files: {@code EEE_YYYYMMDD_yyyymmdd_Z.zip}
     * (entity_startdate_submissiondate_zone).
     * <p>
     * Eligibility for ZIP submission is name-based: {@code routineImmunization} (the
     * original quarterly-cadence type) or any report type whose canonical name contains
     * {@code "annual"} (case-insensitive, see {@link #isAnnual(String)}) may submit a
     * {@code .zip}. The date-bucket tolerance used to match the filename's date against
     * the submission period is driven by the same check: a same-quarter match by
     * default, or a same-year match (ignoring month) for an annual type.
     * </p>
     *
     * @param pf the parsed filename
     */
    private void validateZipMetadata(ZipFilenameComponents pf) {
        if (!pf.getEntityId().equalsIgnoreCase(meta.getExtEntity())) {
            errors.add(String.format("Entity ID (%s) does not match Entity (%s) in filename (%s)",
                    meta.getExtEntity(), pf.getEntityId(), meta.getFilename()));
        }
        String reportType = meta.getExtEvent();
        boolean annual = isAnnual(reportType);
        boolean zipEligible = "routineImmunization".equalsIgnoreCase(reportType) || annual;
        // Eligibility validation isn't relevant when type is genericImmunization.
        if (!GENERIC.equalsIgnoreCase(reportType) && !zipEligible) {
            errors.add(String.format("Report type (%s) is not eligible for ZIP submission (%s)",
                    reportType, meta.getFilename()));
        }
        Calendar cal = Calendar.getInstance();
        if (pf.getDate() != null) {
            cal.setTime(pf.getDate());
        }
        Calendar metaDate = meta.getPeriodAsCalendar();
        // 12 makes month/12 always equal (0 == 0 for any 0-11 value), so checkDate()
        // effectively compares year only for annual types; 3 buckets by calendar quarter.
        int divisor = annual ? 12 : 3;
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
     * Returns true if the given canonical report type name indicates an annual
     * submission cadence.
     *
     * @param reportType the canonical report type name (e.g. {@code meta_ext_event}),
     *                    may be null
     * @return true if {@code reportType} contains {@code "annual"} (case-insensitive)
     */
    static boolean isAnnual(String reportType) {
        return reportType != null && Strings.CI.contains(reportType, "annual");
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
     * Compute the ADS data stream ID from a file type name by converting camelCase
     * (including acronyms) to kebab-case and lowercasing the result.
     * <p>
     * A hyphen is inserted before an uppercase letter when:
     * <ul>
     *   <li>the preceding character is a lowercase letter or digit
     *       (e.g. {@code farmerFlu} → {@code farmer-flu}), or</li>
     *   <li>the preceding character is uppercase <em>and</em> the following character
     *       is lowercase, marking the boundary between an acronym and the next word
     *       (e.g. {@code RIQuarterlyAggregate} → {@code ri-quarterly-aggregate}).</li>
     * </ul>
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code "routineImmunization"} → {@code "routine-immunization"}</li>
     *   <li>{@code "RIQuarterlyAggregate"} → {@code "ri-quarterly-aggregate"}</li>
     *   <li>{@code "COVID19Vaccine"} → {@code "covid19-vaccine"}</li>
     * </ul>
     *
     * @param fileTypeName the file type name to convert
     * @return the computed data stream ID, or {@code "generic-immunization"} if the input is null/empty
     */
    static String computeDataStreamId(String fileTypeName) {
        if (fileTypeName == null || fileTypeName.isEmpty()) {
            return "generic-immunization";
        }
        StringBuilder result = new StringBuilder();
        int len = fileTypeName.length();
        for (int i = 0; i < len; i++) {
            char c = fileTypeName.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                char prev = fileTypeName.charAt(i - 1);
                char next = (i + 1 < len) ? fileTypeName.charAt(i + 1) : '\0';
                boolean prevLowerOrDigit = Character.isLowerCase(prev) || Character.isDigit(prev);
                boolean nextLower = Character.isLowerCase(next);
                if (prevLowerOrDigit || (Character.isUpperCase(prev) && nextLower)) {
                    result.append('-');
                }
            }
            result.append(Character.toLowerCase(c));
        }
        return result.toString();
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