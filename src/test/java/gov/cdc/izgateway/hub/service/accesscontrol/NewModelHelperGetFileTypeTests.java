package gov.cdc.izgateway.hub.service.accesscontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import gov.cdc.izgateway.dynamodb.model.FileType;
import gov.cdc.izgateway.model.IFileType;

/**
 * Unit tests for the three-tier matching logic in {@link NewModelHelper#getFileType(String)}.
 *
 * <p>Uses a stub subclass to pre-populate the file-type cache without requiring DynamoDB.</p>
 */
class NewModelHelperGetFileTypeTests {

    /**
     * Stub subclass that pre-populates {@code fileTypeCache} directly, bypassing DynamoDB.
     */
    private static class StubNewModelHelper extends NewModelHelper {
        /**
         * Constructs a helper with the given file type names pre-loaded.
         *
         * @param fileTypeNames the canonical file type names to register
         */
        StubNewModelHelper(List<String> fileTypeNames) {
            super(null);
            for (String name : fileTypeNames) {
                FileType ft = new FileType();
                ft.setFileTypeName(name);
                fileTypeCache.put(name, ft);
            }
        }

        @Override
        public void refresh() {
            // no-op — cache is pre-populated in constructor
        }
    }

    private NewModelHelper helper;

    /** Set up a helper with all canonical IZ Gateway file type names. */
    @BeforeEach
    void setUp() {
        helper = new StubNewModelHelper(List.of(
                "routineImmunization",
                "influenzaVaccination",
                "rsvPrevention",
                "covidAllMonthlyVaccination",
                "covidBridgeVaccination",
                "farmerFluVaccination",
                "measlesVaccination",
                "genericImmunization",
                "riQuarterlyAggregate"
        ));
    }

    // -----------------------------------------------------------------------
    // Tier 1 — exact match
    // -----------------------------------------------------------------------

    @Test
    void getFileType_exactMatch_returnsEntry() {
        IFileType ft = helper.getFileType("farmerFluVaccination");
        assertNotNull(ft);
        assertEquals("farmerFluVaccination", ft.getFileTypeName());
    }

    // -----------------------------------------------------------------------
    // Tier 2 — case-insensitive match
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "case-insensitive: \"{0}\" → \"{1}\"")
    @CsvSource({
        "ROUTINEIMMUNIZATION,        routineImmunization",
        "influenzavaccination,        influenzaVaccination",
        "COVIDALLMONTHLYVACCINATION, covidAllMonthlyVaccination",
        "FARMERFLUVACCINATION,        farmerFluVaccination",
    })
    void getFileType_caseInsensitive_returnsCanonical(String input, String expectedName) {
        IFileType ft = helper.getFileType(input.trim());
        assertNotNull(ft, "expected match for: " + input.trim());
        assertEquals(expectedName.trim(), ft.getFileTypeName());
    }

    // -----------------------------------------------------------------------
    // Tier 3 — noise-word stripped match (backward compatibility)
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "noise-word stripped: \"{0}\" → \"{1}\"")
    @CsvSource({
        // Legacy reportType values that omit noise-word suffixes
        "farmerFlu,       farmerFluVaccination",
        "FARMERFLU,       farmerFluVaccination",
        "rsv,             rsvPrevention",
        "RSV,             rsvPrevention",
        "influenza,       influenzaVaccination",
        "measles,         measlesVaccination",
        "covidAllMonthly, covidAllMonthlyVaccination",
        // "monthly" is also a noise word — "covidall" strips to same root as "covidAllMonthlyVaccination"
        "covidall,        covidAllMonthlyVaccination",
        "COVIDALL,        covidAllMonthlyVaccination",
    })
    void getFileType_noiseWordStripped_returnsCanonical(String input, String expectedName) {
        IFileType ft = helper.getFileType(input.trim());
        assertNotNull(ft, "expected noise-word match for: " + input.trim());
        assertEquals(expectedName.trim(), ft.getFileTypeName());
    }

    // -----------------------------------------------------------------------
    // No-match cases
    // -----------------------------------------------------------------------

    @Test
    void getFileType_unknownType_returnsNull() {
        assertNull(helper.getFileType("totallyUnknownReportType"));
    }

    @Test
    void getFileType_blank_returnsNull() {
        assertNull(helper.getFileType("   "));
    }

    @Test
    void getFileType_null_returnsNull() {
        assertNull(helper.getFileType(null));
    }
}
