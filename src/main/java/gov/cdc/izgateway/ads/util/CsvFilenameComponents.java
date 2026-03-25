package gov.cdc.izgateway.ads.util;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable value object holding the parsed components of a CSV ADS submission filename.
 * <p>
 * CSV ADS filenames follow the structure:
 * <pre>{@code [Frequency][ReportType]_[Entity]_[Date].[extension]}</pre>
 * For example: {@code MonthlyFlu_XXA_2026FEB.csv}, {@code QuarterlyRI_XXA_2026Q2.csv}
 * </p>
 *
 * @see CsvFilenameValidator
 */
@Value
@Builder
public class CsvFilenameComponents {

    /**
     * The frequency keyword extracted from the filename prefix.
     * Either {@code "Monthly"} or {@code "Quarterly"}.
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
     * The date string as it appears in the filename.
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
     * Returns {@code true} if the frequency keyword is {@code "Quarterly"}.
     *
     * @return true for quarterly submissions
     */
    public boolean isQuarterly() {
        return "Quarterly".equals(frequency);
    }

    /**
     * Returns {@code true} if the frequency keyword is {@code "Monthly"}.
     *
     * @return true for monthly submissions
     */
    public boolean isMonthly() {
        return "Monthly".equals(frequency);
    }
}
