package gov.cdc.izgateway.ads.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CsvFilenameValidator}.
 * <p>
 * Verifies that each filename is parsed into the correct {@link CsvFilenameComponents}
 * and that structural validation behaves as expected.
 * </p>
 */
class CsvFilenameValidatorTests {

    // -------------------------------------------------------------------------
    // 1. testMonthlyRSV_XXA_2022SEP.csv  – contains "test", otherwise valid
    // -------------------------------------------------------------------------

    @Test
    void parseFilename_testPrefix_strippedAndFlagSet() {
        CsvFilenameComponents c = CsvFilenameValidator.parseFilename("testMonthlyRSV_XXA_2022SEP.csv");

        assertNotNull(c, "Should parse successfully after stripping 'test'");
        assertTrue(c.isTestFile(),           "isTestFile should be true");
        assertEquals("Monthly",   c.getFrequency());
        assertEquals("RSV",       c.getReportTypeAbbrev());
        assertEquals("XXA",       c.getEntityId());
        assertEquals("2022SEP",   c.getDateString());
        assertEquals("2022",      c.getYear());
        assertEquals("SEP",       c.getMonth());
        assertNull(c.getQuarter(),           "Quarter should be null for a monthly filename");
        assertEquals("csv",       c.getExtension());
        assertTrue(c.isMonthly());
        assertFalse(c.isQuarterly());
    }

    @Test
    void validate_testPrefix_passesWithPeriodAndEntity() {
        CsvFilenameComponents result =
            CsvFilenameValidator.validate("testMonthlyRSV_XXA_2022SEP.csv", "XXA", "2022-SEP");

        assertTrue(result.isValid(), () -> "Expected valid but got errors: " + result.getErrors());
        assertTrue(result.isTestFile());
    }

    // -------------------------------------------------------------------------
    // 2. MonthlyRSV_XXA_2022SEP.csv  – plain valid monthly file
    // -------------------------------------------------------------------------

    @Test
    void parseFilename_monthlyRSV() {
        CsvFilenameComponents c = CsvFilenameValidator.parseFilename("MonthlyRSV_XXA_2022SEP.csv");

        assertNotNull(c);
        assertFalse(c.isTestFile());
        assertEquals("Monthly",  c.getFrequency());
        assertEquals("RSV",      c.getReportTypeAbbrev());
        assertEquals("XXA",      c.getEntityId());
        assertEquals("2022SEP",  c.getDateString());
        assertEquals("2022",     c.getYear());
        assertEquals("SEP",      c.getMonth());
        assertNull(c.getQuarter());
        assertEquals("csv",      c.getExtension());
    }

    @Test
    void validate_monthlyRSV_passesAllChecks() {
        CsvFilenameComponents result =
            CsvFilenameValidator.validate("MonthlyRSV_XXA_2022SEP.csv", "XXA", "2022-SEP");

        assertTrue(result.isValid(), () -> result.getErrors().toString());
    }

    // -------------------------------------------------------------------------
    // 3. MonthlyFlu_XXA_2022SEP.csv
    // -------------------------------------------------------------------------

    @Test
    void parseFilename_monthlyFlu() {
        CsvFilenameComponents c = CsvFilenameValidator.parseFilename("MonthlyFlu_XXA_2022SEP.csv");

        assertNotNull(c);
        assertFalse(c.isTestFile());
        assertEquals("Monthly",  c.getFrequency());
        assertEquals("Flu",      c.getReportTypeAbbrev());
        assertEquals("XXA",      c.getEntityId());
        assertEquals("2022SEP",  c.getDateString());
        assertEquals("2022",     c.getYear());
        assertEquals("SEP",      c.getMonth());
        assertNull(c.getQuarter());
        assertEquals("csv",      c.getExtension());
    }

    @Test
    void validate_monthlyFlu_passesAllChecks() {
        CsvFilenameComponents result =
            CsvFilenameValidator.validate("MonthlyFlu_XXA_2022SEP.csv", "XXA", "2022-SEP");

        assertTrue(result.isValid(), () -> result.getErrors().toString());
    }

    // -------------------------------------------------------------------------
    // 4. MonthlyFarmerFlu_XXA_2022SEP.csv
    // -------------------------------------------------------------------------

    @Test
    void parseFilename_monthlyFarmerFlu() {
        CsvFilenameComponents c = CsvFilenameValidator.parseFilename("MonthlyFarmerFlu_XXA_2022SEP.csv");

        assertNotNull(c);
        assertFalse(c.isTestFile());
        assertEquals("Monthly",   c.getFrequency());
        assertEquals("FarmerFlu", c.getReportTypeAbbrev());
        assertEquals("XXA",       c.getEntityId());
        assertEquals("2022SEP",   c.getDateString());
        assertEquals("2022",      c.getYear());
        assertEquals("SEP",       c.getMonth());
        assertNull(c.getQuarter());
        assertEquals("csv",       c.getExtension());
    }

    @Test
    void validate_monthlyFarmerFlu_passesAllChecks() {
        CsvFilenameComponents result =
            CsvFilenameValidator.validate("MonthlyFarmerFlu_XXA_2022SEP.csv", "XXA", "2022-SEP");

        assertTrue(result.isValid(), () -> result.getErrors().toString());
    }

    // -------------------------------------------------------------------------
    // 5. MonthlyMeasles_XXA_2022SEP.csv
    // -------------------------------------------------------------------------

    @Test
    void parseFilename_monthlyMeasles() {
        CsvFilenameComponents c = CsvFilenameValidator.parseFilename("MonthlyMeasles_XXA_2022SEP.csv");

        assertNotNull(c);
        assertFalse(c.isTestFile());
        assertEquals("Monthly",  c.getFrequency());
        assertEquals("Measles",  c.getReportTypeAbbrev());
        assertEquals("XXA",      c.getEntityId());
        assertEquals("2022SEP",  c.getDateString());
        assertEquals("2022",     c.getYear());
        assertEquals("SEP",      c.getMonth());
        assertNull(c.getQuarter());
        assertEquals("csv",      c.getExtension());
    }

    @Test
    void validate_monthlyMeasles_passesAllChecks() {
        CsvFilenameComponents result =
            CsvFilenameValidator.validate("MonthlyMeasles_XXA_2022SEP.csv", "XXA", "2022-SEP");

        assertTrue(result.isValid(), () -> result.getErrors().toString());
    }

    // -------------------------------------------------------------------------
    // 6. riQuarterlyAggregate_XXA_2025Q4.csv
    //    "Quarterly" appears mid-prefix – should parse successfully, with the
    //    frequency keyword detected and removed to leave "riAggregate" as the
    //    report type abbreviation.
    // -------------------------------------------------------------------------

    @Test
    void parseFilename_riQuarterlyAggregate_parsesSuccessfully() {
        CsvFilenameComponents c = CsvFilenameValidator.parseFilename("riQuarterlyAggregate_XXA_2025Q4.csv");

        assertNotNull(c, "Should parse: 'Quarterly' is present (case-insensitive) in the prefix");
        assertFalse(c.isTestFile());
        assertEquals("Quarterly",   c.getFrequency());
        assertEquals("riAggregate", c.getReportTypeAbbrev());
        assertEquals("XXA",         c.getEntityId());
        assertEquals("2025Q4",      c.getDateString());
        assertEquals("2025",        c.getYear());
        assertEquals("4",           c.getQuarter());
        assertNull(c.getMonth(),    "Month should be null for a quarterly filename");
        assertEquals("csv",         c.getExtension());
        assertTrue(c.isQuarterly());
        assertFalse(c.isMonthly());
    }

    @Test
    void validate_riQuarterlyAggregate_passesAllChecks() {
        CsvFilenameComponents result =
            CsvFilenameValidator.validate("riQuarterlyAggregate_XXA_2025Q4.csv", "XXA", "2025Q4");

        assertTrue(result.isValid(), () -> "Expected valid but got errors: " + result.getErrors());
    }

    // -------------------------------------------------------------------------
    // 7. RIA_16M.csv  – no frequency prefix, wrong structure – must fail
    // -------------------------------------------------------------------------

    @Test
    void parseFilename_RIA_16M_failsPattern() {
        CsvFilenameComponents c = CsvFilenameValidator.parseFilename("RIA_16M.csv");

        assertNull(c, "Should not parse: missing frequency prefix and wrong structure");
    }

    @Test
    void validate_RIA_16M_failsWithError() {
        CsvFilenameComponents result =
            CsvFilenameValidator.validate("RIA_16M.csv", "RIA", null);

        assertFalse(result.isValid());
        assertFalse(result.getErrors().isEmpty());
    }
}
