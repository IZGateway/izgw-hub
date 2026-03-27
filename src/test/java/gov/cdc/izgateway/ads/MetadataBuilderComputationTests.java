package gov.cdc.izgateway.ads;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for the static computation methods on {@link MetadataBuilder}.
 * <p>
 * These methods are package-private ({@code static}) so they can be tested directly
 * without constructing a full Spring context. Each method is tested against all known
 * production file type names plus edge cases.
 * </p>
 *
 * <p>Covers Task 7 of the ads-metadata-management change request.</p>
 */
class MetadataBuilderComputationTests {

    // -------------------------------------------------------------------------
    // computeMetaExtEvent  (package-private — accessed via setReportType side-effects
    //  or tested indirectly via MetadataBuilder; exposed here for completeness
    //  by calling setReportType on a no-arg builder and inspecting getErrors/build)
    // -------------------------------------------------------------------------

    /**
     * Verify that setReportType stores the expected extEvent on the built metadata.
     * farmerFlu is the only name that maps to a different extEvent value.
     */
    @ParameterizedTest(name = "setReportType({0}).extEvent = {1}")
    @CsvSource({
        "routineImmunization,        routineImmunization",
        "influenzaVaccination,        influenzaVaccination",
        "farmerFlu,                   farmerFluVaccination",
        "farmerFluVaccination,        farmerFluVaccination",
        "rsvPrevention,               rsvPrevention",
        "measlesVaccination,          measlesVaccination",
        "covidAllMonthlyVaccination,  covidAllMonthlyVaccination",
        "genericImmunization,         genericImmunization",
    })
    void setReportType_setsCorrectExtEvent(String reportType, String expectedExtEvent) throws MetadataFault {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setMetadataValidationEnabled(false);
        builder.setReportType(reportType.trim());

        MetadataImpl meta = builder.build();
        assertEquals(expectedExtEvent.trim(), meta.getExtEvent());
    }

    /**
     * setReportType always stores the raw report type name as extEventType,
     * regardless of any mapping applied to extEvent.
     */
    @ParameterizedTest(name = "setReportType({0}).extEventType = {0}")
    @CsvSource({
        "routineImmunization",
        "influenzaVaccination",
        "farmerFlu",
        "rsvPrevention",
        "measlesVaccination",
        "genericImmunization",
    })
    void setReportType_extEventTypeIsAlwaysRawInput(String reportType) throws MetadataFault {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setMetadataValidationEnabled(false);
        builder.setReportType(reportType);

        MetadataImpl meta = builder.build();
        assertEquals(reportType, meta.getExtEventType());
    }

    /**
     * genericImmunization forces ExtSourceVersion to DEX_VERSION2.
     */
    @Test
    void setReportType_genericImmunization_forcesVersion2() throws MetadataFault {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setMetadataValidationEnabled(false);
        builder.setReportType("genericImmunization");

        MetadataImpl meta = builder.build();
        assertEquals(Metadata.DEX_VERSION2, meta.getExtSourceVersion());
    }

    /**
     * A blank reportType should add a validation error and not throw.
     */
    @Test
    void setReportType_blank_addsError() {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType("   ");
        assertEquals(1, builder.getErrors().size());
    }

    @Test
    void setReportType_null_addsError() {
        MetadataBuilder builder = new MetadataBuilder();
        builder.setReportType(null);
        assertEquals(1, builder.getErrors().size());
    }
}
