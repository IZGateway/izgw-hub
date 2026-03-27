package gov.cdc.izgateway.ads;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import gov.cdc.izgateway.ads.MetadataBuilder;

/**
 * Unit tests for {@link MetadataBuilder#computeDataStreamId(String)}.
 * <p>
 * The algorithm converts camelCase (including acronyms) to kebab-case by inserting a
 * hyphen at lowercase→uppercase transitions and at the boundary between an acronym run
 * and the start of a new camelCase word, then lowercases the entire result.
 * The primary acceptance criterion is that it reproduces all existing hardcoded
 * {@code data_stream_id} values from the original {@code Metadata.getDataStreamId()}
 * switch statement, and correctly handles acronyms (e.g. RI, ABC, COVID).
 * </p>
 *
 * <p>Covers Task 8 of the ads-metadata-management change request.</p>
 */
class ComputeDataStreamIdTests {

    // -------------------------------------------------------------------------
    // All known production report types — must reproduce original hardcoded values
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "computeDataStreamId({0}) = {1}")
    @CsvSource({
        "routineImmunization,         routine-immunization",
        "influenzaVaccination,         influenza-vaccination",
        "rsvPrevention,                rsv-prevention",
        "measlesVaccination,           measles-vaccination",
        "farmerFlu,                    farmer-flu",
        "farmerFluVaccination,         farmer-flu-vaccination",
        "covidAllMonthlyVaccination,   covid-all-monthly-vaccination",
        "covidBridgeVaccination,       covid-bridge-vaccination",
        "genericImmunization,          generic-immunization",
    })
    void computeDataStreamId_productionTypes(String input, String expected) {
        assertEquals(expected.trim(), MetadataBuilder.computeDataStreamId(input.trim()));
    }

    // -------------------------------------------------------------------------
    // Acronym handling — consecutive uppercase treated as a single acronym block;
    // hyphen inserted only at the acronym→word boundary
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "computeDataStreamId({0}) = {1}")
    @CsvSource({
        // Acronyms are kept together; hyphen inserted only at acronym→word boundary
        "RIQuarterlyAggregate,   ri-quarterly-aggregate",
        "ABCReport,              abc-report",
        // All-lowercase: no hyphens inserted
        "allowercase,            allowercase",
        // All-uppercase with no following lowercase word: no word boundary, stays together
        "ABC,                    abc",
        // Single character
        "A,                      a",
        "a,                      a",
    })
    void computeDataStreamId_acronymAndEdgeCases(String input, String expected) {
        assertEquals(expected.trim(), MetadataBuilder.computeDataStreamId(input.trim()));
    }

    // -------------------------------------------------------------------------
    // Null / empty — returns "generic-immunization" (documented default)
    // -------------------------------------------------------------------------

    @Test
    void computeDataStreamId_null_returnsDefault() {
        assertEquals("generic-immunization", MetadataBuilder.computeDataStreamId(null));
    }

    @Test
    void computeDataStreamId_empty_returnsDefault() {
        assertEquals("generic-immunization", MetadataBuilder.computeDataStreamId(""));
    }

    // -------------------------------------------------------------------------
    // Numbers are treated as non-uppercase — no hyphen inserted before them
    // -------------------------------------------------------------------------

    @Test
    void computeDataStreamId_numbersNotHyphenated() {
        // COVID is an acronym; digits are not uppercase so no hyphen before them;
        // V in Vaccine starts a new word after the digit, so hyphen is inserted.
        assertEquals("covid19-vaccine", MetadataBuilder.computeDataStreamId("COVID19Vaccine"));
    }
}
