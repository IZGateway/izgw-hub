package gov.cdc.izgateway.ads.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Utility class for validating ADS (Automated Data Submission) filenames.
 * <p>
 * ADS filenames must follow the structure:
 * <pre>{@code [prefix]_[Entity]_[Date].[extension]}</pre>
 * where {@code [prefix]} contains a frequency keyword ("Monthly" or "Quarterly",
 * detected case-insensitively at any position) plus a report-type abbreviation.
 * For example: {@code MonthlyFlu_XXA_2026FEB.csv}, {@code riQuarterlyAggregate_XXA_2025Q4.csv}
 * </p>
 *
 * <h2>Validation Checks</h2>
 * <ol>
 *   <li><strong>Pattern parse</strong> – filename must match the expected regex structure
 *       and contain a recognisable frequency keyword in the first segment</li>
 *   <li><strong>Frequency / period-type match</strong> – detected frequency must be
 *       consistent with the report type's {@code periodType} ("MONTHLY", "QUARTERLY", or "BOTH")</li>
 *   <li><strong>Entity ID match</strong> – entity code in the filename must match the
 *       expected facility/entity value</li>
 *   <li><strong>Date / period match</strong> – date encoded in the filename must match the
 *       {@code period} parameter supplied at submission time</li>
 * </ol>
 *
 * <p>All methods are static; this class is not instantiable.</p>
 *
 * @see FilenameComponents
 * @see FilenameValidationResult
 */
public final class FilenameValidator {

    /**
     * Regex pattern for the structural parts of an ADS filename after the prefix has been
     * separated from the rest.
     * <pre>
     * Group 1 – Prefix segment:    everything before the first underscore (frequency + report type)
     * Group 2 – Entity ID:         exactly 2 uppercase letters followed by 'A'
     * Group 3 – Full date string:  YYYYQ# or YYYYMMM
     * Group 4 – Quarterly year:    4 digits  (present only for quarterly dates)
     * Group 5 – Quarter number:    1-4       (present only for quarterly dates)
     * Group 6 – Monthly year:      4 digits  (present only for monthly dates)
     * Group 7 – Month abbreviation: JAN-DEC  (present only for monthly dates)
     * Group 8 – Extension:         csv | zip (case-insensitive)
     * </pre>
     */
    private static final Pattern FILENAME_PATTERN = Pattern.compile(
        "^([A-Za-z]+)_([A-Z]{2}A)_" +
        "((?:(\\d{4})Q([1-4]))|(?:(\\d{4})(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)))" +
        "\\.(csv|zip)$",
        Pattern.CASE_INSENSITIVE
    );

    private static final String FREQ_QUARTERLY = "Quarterly";
    private static final String FREQ_MONTHLY   = "Monthly";

    private FilenameValidator() {
        // utility class – not instantiable
    }

    /**
     * Parse the filename into its structural components without performing any business-rule
     * validation. Returns {@code null} if the filename does not match the expected pattern
     * or contains no recognisable frequency keyword.
     * <p>
     * The frequency keyword ("Monthly" or "Quarterly") is detected case-insensitively
     * anywhere within the first underscore-delimited segment and then removed to leave
     * the report-type abbreviation — the same technique used for the "test" word.
     * The extension is always returned in lowercase.
     * </p>
     *
     * @param filename the raw filename (with extension) to parse; may be {@code null}
     * @return the parsed {@link FilenameComponents}, or {@code null} if the filename is
     *         blank, does not match the expected structure, or contains no frequency keyword
     */
    public static FilenameComponents parseFilename(String filename) {
        if (StringUtils.isBlank(filename)) {
            return null;
        }
        String normalized = filename.trim();

        // Detect and strip "test" (case-insensitive), matching ParsedFilename convention.
        boolean isTestFile = Strings.CI.contains(normalized, "test");
        if (isTestFile) {
            normalized = Strings.CI.remove(normalized, "test");
        }

        Matcher m = FILENAME_PATTERN.matcher(normalized);
        if (!m.matches()) {
            return null;
        }

        String prefix     = m.group(1);   // e.g. "MonthlyFlu", "riQuarterlyAggregate", "Quarterly"+"RI"
        String entityId   = m.group(2);   // e.g. "XXA"
        String dateString = m.group(3);   // e.g. "2026FEB" or "2026Q2"
        String qYear      = m.group(4);
        String quarter    = m.group(5);
        String mYear      = m.group(6);
        String month      = m.group(7);
        String extension  = m.group(8).toLowerCase();

        // Detect frequency keyword anywhere in the prefix, case-insensitively.
        String frequency;
        String reportAbbrev;
        if (Strings.CI.contains(prefix, FREQ_QUARTERLY)) {
            frequency    = FREQ_QUARTERLY;
            reportAbbrev = Strings.CI.remove(prefix, FREQ_QUARTERLY);
        } else if (Strings.CI.contains(prefix, FREQ_MONTHLY)) {
            frequency    = FREQ_MONTHLY;
            reportAbbrev = Strings.CI.remove(prefix, FREQ_MONTHLY);
        } else {
            // No frequency keyword found – cannot determine submission cadence.
            return null;
        }

        String year = qYear != null ? qYear : mYear;

        return FilenameComponents.builder()
                .frequency(frequency)
                .reportTypeAbbrev(reportAbbrev)
                .entityId(entityId)
                .dateString(dateString.toUpperCase())
                .year(year)
                .month(month != null ? month.toUpperCase() : null)
                .quarter(quarter)
                .extension(extension)
                .testFile(isTestFile)
                .build();
    }

    /**
     * Validate an ADS submission filename against the four required business rules.
     *
     * <ol>
     *   <li>The filename must match the expected structural pattern.</li>
     *   <li>The frequency prefix ("Monthly"/"Quarterly") must be consistent with
     *       the report type's {@code periodType}.</li>
     *   <li>The entity ID encoded in the filename must match {@code expectedEntity}
     *       (case-insensitive). Pass {@code null} to skip this check.</li>
     *   <li>The date encoded in the filename must match the {@code period} parameter
     *       supplied at submission time. Pass {@code null} to skip this check.</li>
     * </ol>
     *
     * @param filename       the raw filename to validate (with extension)
     * @param periodType     the report type's period classification:
     *                       {@code "MONTHLY"}, {@code "QUARTERLY"}, or {@code "BOTH"};
     *                       pass {@code null} to skip the frequency check
     * @param expectedEntity the expected entity/facility code (e.g. {@code "XXA"});
     *                       pass {@code null} to skip the entity check
     * @param period         the submission period parameter (e.g. {@code "2026-FEB"} or
     *                       {@code "2026Q2"}); pass {@code null} to skip the period check
     * @return a {@link FilenameValidationResult} describing whether validation passed
     *         and any errors that were found
     */
    public static FilenameValidationResult validate(
            String filename,
            String periodType,
            String expectedEntity,
            String period) {

        List<String> errors = new ArrayList<>();

        // Check 1: structural pattern parse
        FilenameComponents components = parseFilename(filename);
        if (components == null) {
            errors.add(String.format(
                "Filename '%s' does not match the expected pattern " +
                "[Monthly|Quarterly][ReportType]_[XXA]_[YYYY(MMM|Q#)].(csv|zip). " +
                "Examples: MonthlyFlu_XXA_2026FEB.csv, QuarterlyRI_XXA_2026Q2.csv",
                filename));
            return FilenameValidationResult.failure(errors, null);
        }

        // Check 2: frequency / period-type consistency
        validateFrequency(filename, components, periodType, errors);

        // Check 3: entity ID match
        validateEntity(filename, components, expectedEntity, errors);

        // Check 4: date / period match
        validatePeriod(filename, components, period, errors);

        return errors.isEmpty()
                ? FilenameValidationResult.success(components)
                : FilenameValidationResult.failure(errors, components);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Check 2: The filename's frequency keyword must be compatible with the report type's
     * {@code periodType}.
     * <ul>
     *   <li>{@code "MONTHLY"} requires the {@code "Monthly"} prefix.</li>
     *   <li>{@code "QUARTERLY"} requires the {@code "Quarterly"} prefix.</li>
     *   <li>{@code "BOTH"} accepts either prefix.</li>
     * </ul>
     */
    private static void validateFrequency(
            String filename,
            FilenameComponents components,
            String periodType,
            List<String> errors) {

        if (StringUtils.isBlank(periodType) || "BOTH".equalsIgnoreCase(periodType)) {
            return; // no constraint to enforce
        }

        if ("MONTHLY".equalsIgnoreCase(periodType) && !components.isMonthly()) {
            errors.add(String.format(
                "Filename '%s' must start with 'Monthly' for report types with periodType MONTHLY " +
                "(found '%s')",
                filename, components.getFrequency()));
        } else if ("QUARTERLY".equalsIgnoreCase(periodType) && !components.isQuarterly()) {
            errors.add(String.format(
                "Filename '%s' must start with 'Quarterly' for report types with periodType QUARTERLY " +
                "(found '%s')",
                filename, components.getFrequency()));
        }
    }

    /**
     * Check 3: The entity ID in the filename must match the expected entity value
     * (case-insensitive comparison).
     */
    private static void validateEntity(
            String filename,
            FilenameComponents components,
            String expectedEntity,
            List<String> errors) {

        if (StringUtils.isBlank(expectedEntity)) {
            return; // caller opted out of entity check
        }
        if (!components.getEntityId().equalsIgnoreCase(expectedEntity)) {
            errors.add(String.format(
                "Entity ID in filename '%s' is '%s' but expected '%s'",
                filename, components.getEntityId(), expectedEntity));
        }
    }

    /**
     * Check 4: The date encoded in the filename must match the submission period parameter.
     * <p>
     * The period parameter may arrive in several formats (e.g. {@code "2026-FEB"},
     * {@code "2026FEB"}, {@code "2026Q2"}). Both sides are normalised by uppercasing and
     * removing hyphens before comparison.
     * </p>
     */
    private static void validatePeriod(
            String filename,
            FilenameComponents components,
            String period,
            List<String> errors) {

        if (StringUtils.isBlank(period)) {
            return; // caller opted out of period check
        }
        // Normalise: uppercase, strip hyphens
        String normalizedPeriod = period.trim().toUpperCase().replace("-", "");
        String filenameDateNorm = components.getDateString().toUpperCase();

        if (!normalizedPeriod.equals(filenameDateNorm)) {
            errors.add(String.format(
                "Date in filename '%s' is '%s' but does not match period parameter '%s'",
                filename, components.getDateString(), period));
        }
    }
}
