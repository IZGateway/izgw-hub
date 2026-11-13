package gov.cdc.izgateway.ads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MetadataBuilder#validateZipMetadata} (exercised indirectly via
 * {@link MetadataBuilder#setFilename(String)}) and the {@link MetadataBuilder#isAnnual}
 * helper that drives ZIP eligibility and the date-bucket divisor.
 * <p>
 * Covers Tasks 1.2, 1.3, 2.1, and 2.2 of the {@code annual-ri-aggregate-zip-submission}
 * change request. These are package-private/lightweight unit tests in the style of
 * {@link MetadataBuilderComputationTests} — no Spring context or
 * {@code IAccessControlService} is needed since the report type is passed directly to
 * {@link MetadataBuilder#setReportType(String)} without registry lookup.
 * </p>
 * <p>
 * Entity-ID matching is a separate, unrelated check in {@code validateZipMetadata} and
 * is deliberately not exercised here (no {@code setProvenance}/{@code TransactionData}
 * is set up) — these tests assert on specific error substrings rather than
 * {@code getErrors().isEmpty()}, so an unrelated entity-mismatch error does not affect
 * the eligibility/divisor assertions under test.
 * </p>
 */
class MetadataBuilderValidateZipMetadataTests {

    private static final String ANNUAL_FILENAME = "XXA_20251105_20260101_Agg_Z.zip";

    // -------------------------------------------------------------------------
    // isAnnual()
    // -------------------------------------------------------------------------

    @Test
    void isAnnual_annualAggregateName_true() {
        assertTrue(MetadataBuilder.isAnnual("riAnnualAggregate"));
    }

    @Test
    void isAnnual_caseInsensitive_true() {
        assertTrue(MetadataBuilder.isAnnual("RIANNUALAGGREGATE"));
    }

    @Test
    void isAnnual_quarterlyAggregateName_false() {
        assertFalse(MetadataBuilder.isAnnual("riQuarterlyAggregate"));
    }

    @Test
    void isAnnual_routineImmunization_false() {
        assertFalse(MetadataBuilder.isAnnual("routineImmunization"));
    }

    @Test
    void isAnnual_null_false() {
        assertFalse(MetadataBuilder.isAnnual(null));
    }

    // -------------------------------------------------------------------------
    // Task 1.2 — ZIP eligibility by report type
    // -------------------------------------------------------------------------

    @Test
    void validateZipMetadata_routineImmunization_isEligible() {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("routineImmunization");
        builder.setPeriod("2023Q1");
        builder.setFilename("XXA_20230215_20230301_Z.zip");

        assertFalse(containsSubstring(builder.getErrors(), "is not eligible for ZIP submission"),
                "routineImmunization should remain ZIP-eligible");
    }

    @Test
    void validateZipMetadata_annualAggregate_isEligible() {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("riAnnualAggregate");
        builder.setPeriod("2025-12-31");
        builder.setFilename(ANNUAL_FILENAME);

        assertFalse(containsSubstring(builder.getErrors(), "is not eligible for ZIP submission"),
                "riAnnualAggregate should be ZIP-eligible");
    }

    @Test
    void validateZipMetadata_quarterlyAggregate_isRejectedForZip() {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("riQuarterlyAggregate");
        builder.setPeriod("2025Q4");
        builder.setFilename(ANNUAL_FILENAME);

        assertTrue(containsSubstring(builder.getErrors(), "is not eligible for ZIP submission"),
                "riQuarterlyAggregate must remain CSV-only and be rejected for ZIP");
    }

    @Test
    void validateZipMetadata_genericImmunization_skipsEligibilityCheck() {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("genericImmunization");
        builder.setPeriod("2025-12-31");
        builder.setFilename(ANNUAL_FILENAME);

        assertFalse(containsSubstring(builder.getErrors(), "is not eligible for ZIP submission"),
                "genericImmunization skips the eligibility check entirely");
    }

    // -------------------------------------------------------------------------
    // Task 1.3 — cadence-driven date-bucket divisor
    // -------------------------------------------------------------------------

    @Test
    void validateZipMetadata_routineImmunization_sameQuarterDifferentMonth_matches() {
        // period = 2023Q1 (Jan-Mar); filename date = Feb 2023 -> same quarter
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("routineImmunization");
        builder.setPeriod("2023Q1");
        builder.setFilename("XXA_20230215_20230301_Z.zip");

        assertFalse(containsSubstring(builder.getErrors(), "does not match period"),
                "Feb filename date should match Q1 period under the quarterly (divisor 3) tolerance");
    }

    @Test
    void validateZipMetadata_routineImmunization_differentQuarter_doesNotMatch() {
        // period = 2023Q1 (Jan-Mar); filename date = June 2023 -> different quarter
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("routineImmunization");
        builder.setPeriod("2023Q1");
        builder.setFilename("XXA_20230615_20230701_Z.zip");

        assertTrue(containsSubstring(builder.getErrors(), "does not match period"),
                "June filename date must not match a Q1 period");
    }

    @Test
    void validateZipMetadata_annualAggregate_differentMonthSameYear_matches() {
        // period = 2025-12-31; filename date = Nov 2025 -> same year, different month
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("riAnnualAggregate");
        builder.setPeriod("2025-12-31");
        builder.setFilename(ANNUAL_FILENAME);

        assertFalse(containsSubstring(builder.getErrors(), "does not match period"),
                "Nov filename date should match a 2025 annual period regardless of month");
    }

    @Test
    void validateZipMetadata_annualAggregate_differentYear_doesNotMatch() {
        // period = 2025-12-31 (year 2025); filename date = Nov 2024 -> different year
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("riAnnualAggregate");
        builder.setPeriod("2025-12-31");
        builder.setFilename("XXA_20241105_20241201_Agg_Z.zip");

        assertTrue(containsSubstring(builder.getErrors(), "does not match period"),
                "a 2024 filename date must not match a 2025 annual period");
    }

    // -------------------------------------------------------------------------
    // Task 2.1/2.2 — end-to-end: report type resolution + ZIP validation together
    // -------------------------------------------------------------------------

    @Test
    void endToEnd_annualAggregateZip_acceptedWithCorrectDataStreamId() throws MetadataFault {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("riAnnualAggregate");
        builder.setPeriod("2025-12-31");
        builder.setFilename(ANNUAL_FILENAME);
        builder.setMetadataValidationEnabled(false); // isolate build() from the unrelated entity check

        MetadataImpl meta = builder.build();
        assertEquals("ri-annual-aggregate", meta.getDataStreamId());
        assertFalse(containsSubstring(builder.getErrors(), "is not eligible for ZIP submission"));
        assertFalse(containsSubstring(builder.getErrors(), "does not match period"));
    }

    @Test
    void endToEnd_quarterlyAggregateZip_rejected() {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("riQuarterlyAggregate");
        builder.setPeriod("2025Q4");
        builder.setFilename(ANNUAL_FILENAME);

        assertTrue(containsSubstring(builder.getErrors(), "is not eligible for ZIP submission"),
                "a regression guard: riQuarterlyAggregate must stay CSV-only");
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
