package gov.cdc.izgateway.ads.util;

import java.util.Collections;
import java.util.List;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable value object holding the parsed components of a CSV ADS submission filename,
 * plus any validation errors collected by {@link CsvFilenameValidator#validate}.
 * <p>
 * CSV ADS filenames follow the structure:
 * <pre>{@code [Prefix]_[Entity]_[Date].[extension]}</pre>
 * where {@code [Prefix]} contains a frequency keyword ("Monthly" or "Quarterly",
 * detected case-insensitively at any position) plus a report-type abbreviation.
 * For example: {@code MonthlyFlu_XXA_2026FEB.csv}, {@code riQuarterlyAggregate_XXA_2025Q4.csv}
 * </p>
 *
 * <p>Call {@link #isValid()} to check whether validation passed. When invalid,
 * {@link #getErrors()} contains one or more human-readable messages.  Parsed
 * component fields ({@link #getFrequency()}, etc.) will be {@code null} when
 * the filename could not be parsed at all (i.e. pattern match failed).</p>
 *
 * @see CsvFilenameValidator
 */
@Value
@Builder
public class CsvFilenameComponents {

    /**
     * The frequency keyword extracted from the filename prefix.
     * Either {@code "Monthly"} or {@code "Quarterly"}.
     * {@code null} when the filename failed structural parsing.
     */
    String frequency;

    /**
     * The report type abbreviation extracted from the filename prefix
     * (the portion after the frequency keyword up to the first underscore).
     * Examples: {@code "Flu"}, {@code "RI"}, {@code "AllCOVID"}, {@code "RSV"}.
     */
    String reportTypeAbbrev;

    /**
     * The 3-character entity/jurisdiction code (e.g., {@code "XXA"}, {@code "MAA"}).
     */
    String entityId;

    /**
     * The date string as it appears in the filename (uppercased).
     * Monthly format: {@code "2026FEB"}; quarterly format: {@code "2026Q2"}.
     */
    String dateString;

    /**
     * The 4-digit year extracted from the date component.
     */
    String year;

    /**
     * The month abbreviation (e.g., {@code "FEB"}) for monthly filenames, or {@code null}
     * for quarterly filenames.
     */
    String month;

    /**
     * The quarter digit (e.g., {@code "2"}) for quarterly filenames, or {@code null}
     * for monthly filenames.
     */
    String quarter;

    /**
     * The file extension without the leading dot, lowercased (e.g., {@code "csv"}, {@code "zip"}).
     */
    String extension;

    /**
     * True if the original filename contained the word "test" (case-insensitive),
     * indicating this is a test submission. The word is stripped before structural parsing.
     */
    boolean testFile;

    /**
     * Validation errors collected during {@link CsvFilenameValidator#validate}.
     * Empty when the filename is valid.  May contain a parse-failure message even
     * when all other fields are {@code null}.
     */
    @Builder.Default
    List<String> errors = Collections.emptyList();

    /**
     * Returns {@code true} if no validation errors were found.
     *
     * @return true when errors is empty
     */
    public boolean isValid() {
        return errors.isEmpty();
    }

    /**
     * Returns {@code true} if the frequency keyword is {@code "Quarterly"}.
     *
     * @return true for quarterly submissions
     */
    public boolean isQuarterly() {
        return "Quarterly".equals(frequency);
    }

    /**
     * Returns {@code true} if the frequency keyword is {@code "Monthly"} or if no
     * frequency keyword was detected (monthly is the default assumption).
     *
     * @return true for monthly submissions or when frequency is undetermined
     */
    public boolean isMonthly() {
        return !"Quarterly".equals(frequency);
    }
}
