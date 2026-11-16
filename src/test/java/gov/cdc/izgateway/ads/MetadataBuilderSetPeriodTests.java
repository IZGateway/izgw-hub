package gov.cdc.izgateway.ads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link MetadataBuilder#setPeriod(String)}'s structural validation and
 * its cross-check of the period's shape (monthly/quarterly/annual) against the
 * established submission frequency for the report type.
 * <p>
 * Covers Task 4 of the {@code annual-ri-aggregate-zip-submission} change request:
 * adding a bare {@code YYYY} period format and cross-checking it (and the existing
 * {@code YYYYQ#}/{@code YYYY-MON} formats) against the report type's frequency.
 * </p>
 */
class MetadataBuilderSetPeriodTests {

    // -------------------------------------------------------------------------
    // PERIOD_PATTERN now accepts a bare 4-digit year
    // -------------------------------------------------------------------------

    @Test
    void setPeriod_bareYear_isStructurallyValid() {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("riAnnualAggregate");
        builder.setPeriod("2025");

        assertFalse(containsSubstring(builder.getErrors(), "is not valid"));
    }

    @Test
    void setPeriod_existingFormats_stillValid() {
        MetadataBuilder builder1 = new MetadataBuilder();
        builder1.setReportType("routineImmunization");
        builder1.setPeriod("2025Q4");
        assertFalse(containsSubstring(builder1.getErrors(), "is not valid"));

        MetadataBuilder builder2 = new MetadataBuilder();
        builder2.setReportType("influenzaVaccination");
        builder2.setPeriod("2025-FEB");
        assertFalse(containsSubstring(builder2.getErrors(), "is not valid"));

        MetadataBuilder builder3 = new MetadataBuilder();
        builder3.setReportType("riAnnualAggregate");
        builder3.setPeriod("2025-12-31");
        assertFalse(containsSubstring(builder3.getErrors(), "is not valid"));
    }

    @ParameterizedTest(name = "\"{0}\" is still rejected as structurally invalid")
    @CsvSource({
        "2025Q5",   // no such quarter
        "2025Q",    // missing quarter digit
        "25",       // year must be 4 digits
        "2025XYZ",  // not a recognized month abbreviation
    })
    void setPeriod_malformedValues_stillRejected(String period) {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("riAnnualAggregate");
        builder.setPeriod(period);

        assertTrue(containsSubstring(builder.getErrors(), "is not valid"));
    }

    // -------------------------------------------------------------------------
    // Structural-error message names the one expected format for the report type,
    // rather than listing every structurally-valid shape.
    // -------------------------------------------------------------------------

    @Test
    void setPeriod_malformed_annualMessageNamesBareYear() {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("riAnnualAggregate");
        builder.setPeriod("not-a-period");

        assertTrue(containsSubstring(builder.getErrors(), "requires a period in the form YYYY (e.g. 2026)"));
    }

    @Test
    void setPeriod_malformed_quarterlyMessageNamesQuarterlyForm() {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("routineImmunization");
        builder.setPeriod("not-a-period");

        assertTrue(containsSubstring(builder.getErrors(), "requires a period in the form YYYYQ# (e.g. 2026Q1)"));
    }

    @Test
    void setPeriod_malformed_monthlyMessageNamesMonthlyForm() {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("influenzaVaccination");
        builder.setPeriod("not-a-period");

        assertTrue(containsSubstring(builder.getErrors(), "requires a period in the form YYYY-MMM (e.g. 2026-FEB)"));
    }

    @Test
    void setPeriod_malformed_genericImmunizationMessageListsAllForms() {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("genericImmunization");
        builder.setPeriod("not-a-period");

        assertTrue(containsSubstring(builder.getErrors(), "requires a period in the form YYYYQ#, YYYY-MMM, or YYYY"));
    }

    // -------------------------------------------------------------------------
    // classifyPeriodFrequency()
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "classifyPeriodFrequency({0}) = {1}")
    @CsvSource({
        "2025Q4,   Quarterly",
        "2025-FEB, Monthly",
        "2025,     Annual",
    })
    void classifyPeriodFrequency_recognizedShapes(String period, String expected) {
        assertEquals(expected, MetadataBuilder.classifyPeriodFrequency(period));
    }

    @Test
    void classifyPeriodFrequency_legacyNumericDate_returnsNull() {
        assertNull(MetadataBuilder.classifyPeriodFrequency("2025-12-31"));
        assertNull(MetadataBuilder.classifyPeriodFrequency("2025-12"));
    }

    // -------------------------------------------------------------------------
    // establishedFrequency()
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "establishedFrequency({0}) = {1}")
    @CsvSource({
        "riAnnualAggregate,          Annual",
        "routineImmunization,        Quarterly",
        "riQuarterlyAggregate,       Quarterly",
        "influenzaVaccination,       Monthly",
        "rsvPrevention,              Monthly",
        "covidAllMonthlyVaccination, Monthly",
        "farmerFluVaccination,       Monthly",
        "measlesVaccination,         Monthly",
    })
    void establishedFrequency_knownTypes(String reportType, String expected) {
        assertEquals(expected, MetadataBuilder.establishedFrequency(reportType));
    }

    @Test
    void establishedFrequency_annualCheckTakesPrecedenceOverRiPrefix() {
        // riAnnualAggregate also starts with "ri", same collision isAnnual() already guards against.
        assertEquals("Annual", MetadataBuilder.establishedFrequency("riAnnualAggregate"));
    }

    // -------------------------------------------------------------------------
    // setPeriod() cross-check — matching frequency passes
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} + {1} passes the frequency cross-check")
    @CsvSource({
        "riAnnualAggregate,    2025",
        "routineImmunization,  2023Q1",
        "riQuarterlyAggregate, 2025Q4",
        "influenzaVaccination, 2025-FEB",
    })
    void setPeriod_matchingFrequency_noCrossCheckError(String reportType, String period) {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType(reportType);
        builder.setPeriod(period);

        assertFalse(containsSubstring(builder.getErrors(), "requires a"),
                reportType + " + " + period + " should not trigger the frequency cross-check");
    }

    // -------------------------------------------------------------------------
    // setPeriod() cross-check — mismatched frequency fails
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} + {1} fails the frequency cross-check")
    @CsvSource({
        // Annual type, wrong-shaped period
        "riAnnualAggregate,    2025Q4",
        "riAnnualAggregate,    2025-FEB",
        // Quarterly type, wrong-shaped period
        "routineImmunization,  2025",
        "routineImmunization,  2025-FEB",
        "riQuarterlyAggregate, 2025",
        "riQuarterlyAggregate, 2025-FEB",
        // Monthly type, wrong-shaped period
        "influenzaVaccination, 2025",
        "influenzaVaccination, 2025Q4",
    })
    void setPeriod_mismatchedFrequency_addsError(String reportType, String period) {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType(reportType);
        builder.setPeriod(period);

        assertTrue(containsSubstring(builder.getErrors(), "requires a"),
                reportType + " + " + period + " should trigger the frequency cross-check");
    }

    // -------------------------------------------------------------------------
    // Bypasses: genericImmunization and the legacy numeric date form
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "genericImmunization + {0} skips the cross-check")
    @CsvSource({
        "2025",
        "2025Q4",
        "2025-FEB",
    })
    void setPeriod_genericImmunization_skipsCrossCheck(String period) {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("genericImmunization");
        builder.setPeriod(period);

        assertFalse(containsSubstring(builder.getErrors(), "requires a"));
    }

    @Test
    void setPeriod_legacyNumericDate_skipsCrossCheckRegardlessOfReportType() {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("influenzaVaccination"); // Monthly-established, but legacy form is exempt
        builder.setPeriod("2025-12-31");

        assertFalse(containsSubstring(builder.getErrors(), "requires a"));
    }

    private static boolean containsSubstring(Iterable<String> errors, String substring) {
        for (String error : errors) {
            if (error.contains(substring)) {
                return true;
            }
        }
        return false;
    }
}
