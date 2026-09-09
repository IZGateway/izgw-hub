package gov.cdc.izgateway.hub.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the use-type intersection rule of IGDD-3140 / IGDD-3257: a credential's {@code useTypes} must
 * intersect the destination jurisdiction's {@code allowedUseTypes} for access to be granted.
 */
class UseTypeTests {

    @Test
    @DisplayName("Overlapping use types intersect")
    void overlappingUseTypes_intersect() {
        assertTrue(UseType.intersects(Set.of("PROVIDER"), Set.of("PROVIDER")));
        assertTrue(UseType.intersects(Set.of("PATIENT", "PROVIDER"), Set.of("PROVIDER", "PUBLIC_HEALTH")));
    }

    @Test
    @DisplayName("A multi-use-type jurisdiction accepts any listed category")
    void multiUseTypeJurisdiction_acceptsAnySubset() {
        Set<String> allowed = Set.of("PATIENT", "PROVIDER", "PUBLIC_HEALTH");
        assertTrue(UseType.intersects(Set.of("PATIENT"), allowed));
        assertTrue(UseType.intersects(Set.of("PROVIDER"), allowed));
        assertTrue(UseType.intersects(Set.of("PUBLIC_HEALTH"), allowed));
    }

    @Test
    @DisplayName("A single-use-type jurisdiction rejects out-of-scope credentials")
    void singleUseTypeJurisdiction_rejectsOutOfScope() {
        assertFalse(UseType.intersects(Set.of("PATIENT"), Set.of("PROVIDER")));
        assertTrue(UseType.intersects(Set.of("PROVIDER"), Set.of("PROVIDER")));
    }

    @Test
    @DisplayName("Disjoint sets do not intersect")
    void disjointUseTypes_doNotIntersect() {
        assertFalse(UseType.intersects(Set.of("PATIENT"), Set.of("PROVIDER", "PUBLIC_HEALTH")));
    }

    @Test
    @DisplayName("Empty or absent allowedUseTypes is deny-all")
    void emptyAllowedUseTypes_deniesAll() {
        assertFalse(UseType.intersects(Set.of("PROVIDER"), Set.of()));
        assertFalse(UseType.intersects(Set.of("PROVIDER"), null));
    }

    @Test
    @DisplayName("A credential with no useTypes is authorized for nothing")
    void emptyCredentialUseTypes_authorizesNothing() {
        assertFalse(UseType.intersects(Set.of(), Set.of("PROVIDER")));
        assertFalse(UseType.intersects(null, Set.of("PROVIDER")));
        assertFalse(UseType.intersects(null, null));
    }

    @Test
    @DisplayName("Comparison is case-insensitive and tolerates surrounding whitespace")
    void comparisonIsNormalized() {
        assertTrue(UseType.intersects(List.of("provider"), Set.of("PROVIDER")));
        assertTrue(UseType.intersects(List.of("  Public_Health  "), Set.of("PUBLIC_HEALTH")));
    }

    @Test
    @DisplayName("Null and blank entries are ignored rather than matching")
    void nullAndBlankEntriesAreIgnored() {
        assertFalse(UseType.intersects(Arrays.asList(null, "  "), Set.of("PROVIDER")));
        assertFalse(UseType.intersects(Set.of("PROVIDER"), Arrays.asList(null, "")));
        assertTrue(UseType.intersects(Arrays.asList(null, "PROVIDER"), Set.of("PROVIDER")));
    }

    @Test
    @DisplayName("Unrecognized stored values simply fail to intersect")
    void unrecognizedValues_doNotIntersect() {
        assertFalse(UseType.intersects(Set.of("NOT_A_USE_TYPE"), Set.of("PROVIDER")));
    }

    @Test
    @DisplayName("The enumeration is exactly PATIENT, PROVIDER, PUBLIC_HEALTH")
    void enumerationValues() {
        assertTrue(Set.of(UseType.values()).containsAll(
                Set.of(UseType.PATIENT, UseType.PROVIDER, UseType.PUBLIC_HEALTH)));
        assertTrue(UseType.values().length == 3);
    }
}
