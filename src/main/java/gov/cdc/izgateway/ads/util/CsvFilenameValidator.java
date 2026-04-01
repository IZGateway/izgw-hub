package gov.cdc.izgateway.ads.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Utility class for parsing and validating CSV ADS (Automated Data Submission) filenames.
 * <p>
 * CSV ADS filenames must follow the structure:
 * <pre>{@code [prefix]_[Entity]_[Date].[extension]}</pre>
 * where {@code [prefix]} contains a frequency keyword ("Monthly" or "Quarterly",
 * detected case-insensitively at any position) plus a report-type abbreviation.
 * For example: {@code MonthlyFlu_XXA_2026FEB.csv}, {@code riQuarterlyAggregate_XXA_2025Q4.csv}
 * </p>
 *
 * <p>The frequency keyword in the filename determines whether the submission is monthly
 * or quarterly — no external period-type lookup is performed.  Monthly is the default
 * assumption when no frequency keyword can be detected.</p>
 *
 * <h2>Validation Checks</h2>
 * <ol>
 *   <li><strong>Pattern parse</strong> – filename must match the expected regex structure
 *       and contain a recognisable frequency keyword in the first segment; fail-fast</li>
 *   <li><strong>Entity ID match</strong> – entity code in the filename must match the
 *       expected facility/entity value (when provided)</li>
 *   <li><strong>Date / period match</strong> – date encoded in the filename must match the
 *       {@code period} parameter supplied at submission time (when provided)</li>
 * </ol>
 *
 * <p>ZIP filename validation is handled by {@link gov.cdc.izgateway.ads.ZipFilenameComponents}.</p>
 * <p>All methods are static; this class is not instantiable.</p>
 *
 * @see CsvFilenameComponents
 */
public final class CsvFilenameValidator {

    /**
     * Regex pattern for the structural parts of a CSV ADS filename.
     * <pre>
     * Group 1 – Prefix segment:     everything before the first underscore (frequency + report type)
     * Group 2 – Entity ID:          exactly 2 letters followed by 'A' (case-insensitive)
     * Group 3 – Full date string:   YYYYQ# or YYYYMMM
     * Group 4 – Quarterly year:     4 digits  (present only for quarterly dates)
     * Group 5 – Quarter number:     1-4       (present only for quarterly dates)
     * Group 6 – Monthly year:       4 digits  (present only for monthly dates)
     * Group 7 – Month abbreviation: JAN-DEC   (present only for monthly dates)
     * Group 8 – Extension:          csv | zip (case-insensitive)
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

    private CsvFilenameValidator() {
        // utility class – not instantiable
    }

    /**
     * Parse the filename into its structural components without performing any business-rule
     * validation. Returns {@code null} if the filename does not match the expected pattern
     * or contains no recognisable frequency keyword.
     * <p>
     * The frequency keyword ("Monthly" or "Quarterly") is detected case-insensitively
     * anywhere within the first underscore-delimited segment and then removed to leave
     * the report-type abbreviation.  The extension is always returned in lowercase.
     * </p>
     *
     * @param filename the raw filename (with extension) to parse; may be {@code null}
     * @return the parsed {@link CsvFilenameComponents}, or {@code null} if the filename is
     *         blank, does not match the expected structure, or contains no frequency keyword
     */
    public static CsvFilenameComponents parseFilename(String filename) {
        if (StringUtils.isBlank(filename)) {
            return null;
        }
        String normalized = filename.trim();

        // Detect and strip "test" (case-insensitive), matching ParsedZipFilename convention.
        boolean isTestFile = Strings.CI.contains(normalized, "test");
        if (isTestFile) {
            normalized = Strings.CI.remove(normalized, "test");
        }

        Matcher m = FILENAME_PATTERN.matcher(normalized);
        if (!m.matches()) {
            return null;
        }

        String prefix     = m.group(1);   // e.g. "MonthlyFlu", "riQuarterlyAggregate"
        String entityId   = m.group(2);   // e.g. "XXA"
        String dateString = m.group(3);   // e.g. "2026FEB" or "2025Q4"
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

        return CsvFilenameComponents.builder()
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
     * Validate a CSV ADS submission filename against three business rules.
     *
     * <ol>
     *   <li>The filename must match the expected structural pattern (fail-fast).</li>
     *   <li>The entity ID encoded in the filename must match {@code expectedEntity}
     *       (case-insensitive). Pass {@code null} to skip this check.</li>
     *   <li>The date encoded in the filename must match the {@code period} parameter
     *       supplied at submission time. Pass {@code null} to skip this check.</li>
     * </ol>
     *
     * <p>The frequency keyword in the filename ("Monthly"/"Quarterly") determines
     * the submission cadence; no external period-type is required.  Monthly is
     * assumed as the default when no frequency keyword is detectable.</p>
     *
     * @param filename       the raw filename to validate (with extension)
     * @param expectedEntity the expected entity/facility code (e.g. {@code "XXA"});
     *                       pass {@code null} to skip the entity check
     * @param period         the submission period parameter (e.g. {@code "2026-FEB"} or
     *                       {@code "2026Q2"}); pass {@code null} to skip the period check
     * @return a {@link CsvFilenameComponents} describing the parse result and any errors;
     *         call {@link CsvFilenameComponents#isValid()} to check success
     */
    public static CsvFilenameComponents validate(
            String filename,
            String expectedEntity,
            String period) {

        List<String> errors = new ArrayList<>();

        // Check 1: structural pattern parse (fail-fast)
        CsvFilenameComponents components = parseFilename(filename);
        if (components == null) {
            errors.add(String.format(
                "Filename '%s' does not match the expected pattern " +
                "[Monthly|Quarterly][ReportType]_[XXA]_[YYYY(MMM|Q#)].(csv|zip). " +
                "Examples: MonthlyFlu_XXA_2026FEB.csv, QuarterlyRI_XXA_2026Q2.csv",
                filename));
            return CsvFilenameComponents.builder()
                    .errors(Collections.unmodifiableList(errors))
                    .build();
        }

        // Check 2: entity ID match
        validateEntity(filename, components, expectedEntity, errors);

        // Check 3: date / period match
        validatePeriod(filename, components, period, errors);

        if (errors.isEmpty()) {
            return components;
        }

        // Rebuild with errors attached
        return CsvFilenameComponents.builder()
                .frequency(components.getFrequency())
                .reportTypeAbbrev(components.getReportTypeAbbrev())
                .entityId(components.getEntityId())
                .dateString(components.getDateString())
                .year(components.getYear())
                .month(components.getMonth())
                .quarter(components.getQuarter())
                .extension(components.getExtension())
                .testFile(components.isTestFile())
                .errors(Collections.unmodifiableList(errors))
                .build();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Check 2: The entity ID in the filename must match the expected entity value
     * (case-insensitive comparison).
     */
    private static void validateEntity(
            String filename,
            CsvFilenameComponents components,
            String expectedEntity,
            List<String> errors) {

        if (StringUtils.isBlank(expectedEntity)) {
            return;
        }
        if (!components.getEntityId().equalsIgnoreCase(expectedEntity)) {
            errors.add(String.format(
                "Entity ID in filename '%s' is '%s' but expected '%s'",
                filename, components.getEntityId(), expectedEntity));
        }
    }

    /**
     * Check 3: The date encoded in the filename must match the submission period parameter.
     * <p>
     * The period parameter may arrive in several formats (e.g. {@code "2026-FEB"},
     * {@code "2026FEB"}, {@code "2026Q4"}). Both sides are normalised by uppercasing and
     * removing hyphens before comparison.
     * </p>
     */
    private static void validatePeriod(
            String filename,
            CsvFilenameComponents components,
            String period,
            List<String> errors) {

        if (StringUtils.isBlank(period)) {
            return;
        }
        String normalizedPeriod = period.trim().toUpperCase().replace("-", "");
        String filenameDateNorm = components.getDateString().toUpperCase();

        if (!normalizedPeriod.equals(filenameDateNorm)) {
            errors.add(String.format(
                "Date in filename '%s' is '%s' but does not match period parameter '%s'",
                filename, components.getDateString(), period));
        }
    }
}
