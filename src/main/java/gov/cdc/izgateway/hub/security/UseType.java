package gov.cdc.izgateway.hub.security;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The categories of immunization data an API-key credential may submit, and which a jurisdiction
 * may accept (IGDD-3140 / IGDD-3257).
 *
 * <p>A credential declares its {@code useTypes}; a destination's jurisdiction declares its
 * {@code allowedUseTypes}. Both are server-side properties stored in DynamoDB — neither is carried
 * in the JWT — so policy can change without reissuing credentials. At routing time the two sets must
 * intersect for the message to be authorized; see
 * {@code AccessControlService.checkAccessToDestination(String)}.</p>
 *
 * @author Audacious Inquiry
 */
public enum UseType {
    /** Data submitted on behalf of an individual patient. */
    PATIENT,
    /** Data submitted by or on behalf of a healthcare provider. */
    PROVIDER,
    /** Data submitted for public health reporting purposes. */
    PUBLIC_HEALTH;

    /**
     * Normalize a stored use-type value for comparison: trimmed and upper-cased. Values are persisted
     * as strings by Config Console, which validates them against this enumeration on write, so Hub
     * normalizes rather than rejects unrecognized values (an unrecognized value simply fails to
     * intersect).
     *
     * @param value the raw stored value; may be {@code null}
     * @return the normalized value, or {@code null} if {@code value} is {@code null} or blank
     */
    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Normalize a collection of stored use-type values, dropping nulls and blanks.
     *
     * @param values the raw stored values; may be {@code null}
     * @return the normalized set; never {@code null}
     */
    static Set<String> normalizeAll(Collection<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .map(UseType::normalize)
                .filter(v -> v != null)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Determine whether a credential's use-types intersect a jurisdiction's allowed use-types.
     *
     * <p>Per IGDD-3140 the intersection MUST be non-empty for access to be granted. Consequently an
     * empty or absent {@code allowedUseTypes} on the destination jurisdiction is <b>deny-all</b> for
     * API-key senders, and a credential with no {@code useTypes} is authorized for nothing. Comparison
     * is case-insensitive.</p>
     *
     * @param credentialUseTypes the {@code useTypes} on the calling credential; may be {@code null}
     * @param allowedUseTypes    the {@code allowedUseTypes} on the destination jurisdiction; may be
     *                           {@code null}
     * @return {@code true} if at least one use-type is common to both sets
     */
    public static boolean intersects(Collection<String> credentialUseTypes, Collection<String> allowedUseTypes) {
        Set<String> credential = normalizeAll(credentialUseTypes);
        if (credential.isEmpty()) {
            return false;
        }
        Set<String> allowed = normalizeAll(allowedUseTypes);
        if (allowed.isEmpty()) {
            return false;
        }
        return credential.stream().anyMatch(allowed::contains);
    }
}
