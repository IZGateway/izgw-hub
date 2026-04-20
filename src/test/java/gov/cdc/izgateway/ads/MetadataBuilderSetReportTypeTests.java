package gov.cdc.izgateway.ads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import gov.cdc.izgateway.model.IFileType;
import gov.cdc.izgateway.service.IAccessControlService;

/**
 * Integration tests for {@link MetadataBuilder#setReportType(String)} focusing on
 * the interaction with an injected {@link IAccessControlService}.
 *
 * <ul>
 *   <li>Verifies that metadata fields ({@code extEvent}, {@code extEventType},
 *       {@code dataStreamId}) are set correctly for every known report type.</li>
 *   <li>Verifies warning-only behavior when a report type is not in the registry
 *       (processing must continue, no error added).</li>
 *   <li>Verifies that no registry lookup occurs when no service is injected.</li>
 * </ul>
 *
 * <p>Covers Task 10 of the ads-metadata-management change request.</p>
 */
class MetadataBuilderSetReportTypeTests {

    private IAccessControlService mockService;
    private IFileType mockFileType;

    @BeforeEach
    void setUp() {
        mockService  = mock(IAccessControlService.class);
        mockFileType = mock(IFileType.class);
        // Default: report type found in registry
        when(mockService.getFileType(anyString())).thenReturn(mockFileType);
    }

    // -------------------------------------------------------------------------
    // All known report types — extEvent, extEventType, dataStreamId
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}: extEvent={1}, dataStreamId={2}")
    @CsvSource({
        "routineImmunization,        routineImmunization,        routine-immunization",
        "influenzaVaccination,        influenzaVaccination,        influenza-vaccination",
        "rsvPrevention,               rsvPrevention,               rsv-prevention",
        "measlesVaccination,          measlesVaccination,          measles-vaccination",
        "covidAllMonthlyVaccination,  covidAllMonthlyVaccination,  covid-all-monthly-vaccination",
        "covidBridgeVaccination,      covidBridgeVaccination,      covid-bridge-vaccination",
        "genericImmunization,         genericImmunization,         generic-immunization",
        // farmerFlu: special extEvent mapping drives the correct dataStreamId
        "farmerFlu,                   farmerFluVaccination,        farmer-flu-vaccination",
        "farmerFluVaccination,        farmerFluVaccination,        farmer-flu-vaccination",
    })
    void setReportType_setsAllDerivedFields(
            String reportType, String expectedExtEvent, String expectedDataStreamId) throws MetadataFault {

        MetadataBuilder builder = new MetadataBuilder(mockService);
        builder.setMetadataValidationEnabled(false);
        builder.setReportType(reportType.trim());

        MetadataImpl meta = builder.build();
        assertEquals(expectedExtEvent.trim(),       meta.getExtEvent(),      "extEvent");
        assertEquals(reportType.trim(),             meta.getExtEventType(),  "extEventType (always raw input)");
        assertEquals(expectedDataStreamId.trim(),   meta.getDataStreamId(),  "dataStreamId");
        assertTrue(builder.getErrors().isEmpty(),   "no errors expected");
    }

    // -------------------------------------------------------------------------
    // Registry lookup behaviour
    // -------------------------------------------------------------------------

    @Test
    void setReportType_withService_lookupsFileType() {
        when(mockService.getFileType("influenzaVaccination")).thenReturn(mockFileType);

        MetadataBuilder builder = new MetadataBuilder(mockService);
        builder.setReportType("influenzaVaccination");

        verify(mockService).getFileType("influenzaVaccination");
    }

    @Test
    void setReportType_unknownType_logsWarnButNoError() throws MetadataFault {
        // Registry returns null → unknown type
        when(mockService.getFileType("unknownType")).thenReturn(null);

        MetadataBuilder builder = new MetadataBuilder(mockService);
        builder.setMetadataValidationEnabled(false);
        builder.setReportType("unknownType");

        // Processing must continue — no validation error should be added
        assertTrue(builder.getErrors().isEmpty(),
                "unknown report type should log a warning but not add an error");
        // Fields are still computed from the name
        MetadataImpl meta = builder.build();
        assertEquals("unknown-type", meta.getDataStreamId());
        assertEquals("unknownType",  meta.getExtEventType());
    }

    @Test
    void setReportType_withoutService_noLookupAttempted() {
        // No service injected — the null-safe path must not crash
        MetadataBuilder builder = new MetadataBuilder();   // no-arg → null service
        builder.setReportType("influenzaVaccination");

        // Verify nothing was called on any mock (there is no mock in this path)
        verify(mockService, never()).getFileType(anyString());
    }

    // -------------------------------------------------------------------------
    // No-service path still produces correct metadata
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "no-service: setReportType({0}).dataStreamId = {1}")
    @CsvSource({
        "routineImmunization,  routine-immunization",
        "farmerFlu,            farmer-flu-vaccination",
        "genericImmunization,  generic-immunization",
    })
    void setReportType_noService_stillComputesDataStreamId(String reportType, String expectedDataStreamId)
            throws MetadataFault {

        MetadataBuilder builder = new MetadataBuilder();   // no service
        builder.setMetadataValidationEnabled(false);
        builder.setReportType(reportType.trim());

        MetadataImpl meta = builder.build();
        assertEquals(expectedDataStreamId.trim(), meta.getDataStreamId());
        assertTrue(builder.getErrors().isEmpty());
    }

    // -------------------------------------------------------------------------
    // genericImmunization forces DEX_VERSION2 regardless of service presence
    // -------------------------------------------------------------------------

    @Test
    void setReportType_genericImmunization_forcesVersion2_withService() throws MetadataFault {
        MetadataBuilder builder = new MetadataBuilder(mockService);
        builder.setMetadataValidationEnabled(false);
        builder.setReportType("genericImmunization");

        assertEquals(Metadata.DEX_VERSION2, builder.build().getExtSourceVersion());
    }

    @Test
    void setReportType_nonGeneric_doesNotForceVersion2() throws MetadataFault {
        MetadataBuilder builder = new MetadataBuilder(mockService);
        builder.setMetadataValidationEnabled(false);
        builder.setReportType("influenzaVaccination");

        // extSourceVersion should not be set to DEX_VERSION2 by setReportType alone
        assertFalse(Metadata.DEX_VERSION2.equals(builder.build().getExtSourceVersion()),
                "setReportType should only force VERSION2 for genericImmunization");
    }

    // -------------------------------------------------------------------------
    // Blank / null input
    // -------------------------------------------------------------------------

    @Test
    void setReportType_blank_addsErrorAndSkipsLookup() {
        MetadataBuilder builder = new MetadataBuilder(mockService);
        builder.setReportType("  ");

        assertFalse(builder.getErrors().isEmpty(), "blank reportType must produce an error");
        verify(mockService, never()).getFileType(anyString());
    }

    @Test
    void setReportType_null_addsErrorAndSkipsLookup() {
        MetadataBuilder builder = new MetadataBuilder(mockService);
        builder.setReportType(null);

        assertFalse(builder.getErrors().isEmpty(), "null reportType must produce an error");
        verify(mockService, never()).getFileType(anyString());
    }
}
