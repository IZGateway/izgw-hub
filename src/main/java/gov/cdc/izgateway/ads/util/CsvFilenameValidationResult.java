package gov.cdc.izgateway.ads.util;

import java.util.Collections;
import java.util.List;

import lombok.Value;

/**
 * Immutable value object holding the result of validating a CSV ADS submission filename.
 * <p>
 * Callers should check {@link #isValid()} first. If validation failed, {@link #getErrors()}
 * contains one or more human-readable messages describing each problem found.
 * If the filename was successfully parsed, {@link #getComponents()} holds the structured
 * breakdown of the filename; it is {@code null} when the filename could not be parsed at all.
 * </p>
 *
 * <pre>{@code
 * CsvFilenameValidationResult result = CsvFilenameValidator.validate(filename, periodType, expectedEntity, period);
 * if (!result.isValid()) {
 *     result.getErrors().forEach(log::warn);
 * }
 * }</pre>
 *
 * @see CsvFilenameValidator
 * @see CsvFilenameComponents
 */
@Value
public class CsvFilenameValidationResult {

    /** True when the filename passed all validation checks. */
    boolean valid;

    /**
     * Human-readable error messages describing each validation failure.
     * Empty when {@link #isValid()} is {@code true}.
     */
    List<String> errors;

    /**
     * Structured components parsed from the filename, or {@code null} if the filename
     * did not match the expected pattern and could not be parsed.
     */
    CsvFilenameComponents components;

    /**
     * Creates a successful validation result with the parsed components.
     *
     * @param components the successfully parsed filename components
     * @return a valid result
     */
    public static CsvFilenameValidationResult success(CsvFilenameComponents components) {
        return new CsvFilenameValidationResult(true, Collections.emptyList(), components);
    }

    /**
     * Creates a failed validation result with one or more error messages.
     *
     * @param errors     the list of validation error messages (must not be null or empty)
     * @param components the partially parsed components, or {@code null} if parsing failed entirely
     * @return an invalid result
     */
    public static CsvFilenameValidationResult failure(List<String> errors, CsvFilenameComponents components) {
        return new CsvFilenameValidationResult(false, Collections.unmodifiableList(errors), components);
    }
}
