package gov.cdc.izgateway.dynamodb.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the DynamoDB attribute types of the API-key access-control attributes to the storage contract
 * agreed with Config Console (IGDD-3140 / IGDD-3257):
 *
 * <ul>
 *   <li>{@code ApiKeyCredential.environments} — Number Set ({@code NS})</li>
 *   <li>{@code ApiKeyCredential.useTypes} — String Set ({@code SS})</li>
 *   <li>{@code Jurisdiction.allowedUseTypes} — String Set ({@code SS})</li>
 * </ul>
 *
 * <p>These attributes are written by Config Console and only read by Hub, so a type disagreement is
 * invisible to every test that mocks the repository: the bean compiles, the unit tests pass, and the
 * failure surfaces only against real data. Asserting the generated attribute type here is the cheap
 * guard. If one of these assertions fails, the Hub bean and Config Console have diverged — fix the
 * contract, do not relax the test.</p>
 *
 * <p>Also pins the empty-set behaviour: DynamoDB cannot store an empty set, so "none" must be
 * represented by the attribute being absent. For {@code Jurisdiction.allowedUseTypes} that absence
 * <em>is</em> the deny-all policy state.</p>
 *
 * @author Audacious Inquiry
 */
class PolicyAttributeMappingTests {

    private static final TableSchema<ApiKeyCredential> CREDENTIAL_SCHEMA =
            TableSchema.fromBean(ApiKeyCredential.class);
    private static final TableSchema<Jurisdiction> JURISDICTION_SCHEMA =
            TableSchema.fromBean(Jurisdiction.class);

    /** Marshals with ignoreNulls=true, the way DynamoDbRepository writes items. */
    private static Map<String, AttributeValue> marshal(ApiKeyCredential credential) {
        return CREDENTIAL_SCHEMA.itemToMap(credential, true);
    }

    private static ApiKeyCredential credential() {
        ApiKeyCredential credential = new ApiKeyCredential();
        credential.setJti("018f4e2a-5678-7abc-8def-000000000002");
        credential.setStatus("active");
        return credential;
    }

    @Test
    @DisplayName("environments marshals to a DynamoDB Number Set (NS), not a List")
    void environments_isNumberSet() {
        ApiKeyCredential credential = credential();
        credential.setEnvironments(Set.of(2, 5));

        AttributeValue environments = marshal(credential).get("environments");

        assertThat(environments.ns()).containsExactlyInAnyOrder("2", "5");
        assertThat(environments.hasL()).isFalse();
        assertThat(environments.hasSs()).isFalse();
    }

    @Test
    @DisplayName("A round trip through the table schema preserves environments")
    void environments_roundTrip() {
        ApiKeyCredential credential = credential();
        credential.setEnvironments(Set.of(2, 5));

        ApiKeyCredential restored = CREDENTIAL_SCHEMA.mapToItem(marshal(credential));

        assertThat(restored.getEnvironments()).containsExactlyInAnyOrder(2, 5);
    }

    @Test
    @DisplayName("useTypes marshals to a DynamoDB String Set (SS)")
    void useTypes_isStringSet() {
        ApiKeyCredential credential = credential();
        credential.setUseTypes(Set.of("PROVIDER", "PUBLIC_HEALTH"));

        AttributeValue useTypes = marshal(credential).get("useTypes");

        assertThat(useTypes.ss()).containsExactlyInAnyOrder("PROVIDER", "PUBLIC_HEALTH");
        assertThat(useTypes.hasL()).isFalse();
    }

    @Test
    @DisplayName("Absent set attributes are omitted on write and read back as null")
    void nullSets_areOmittedAndReadBackAsNull() {
        Map<String, AttributeValue> item = marshal(credential());

        // DynamoDB rejects an empty set, so "none" is expressed by omitting the attribute entirely.
        assertThat(item).doesNotContainKeys("environments", "useTypes");

        ApiKeyCredential restored = CREDENTIAL_SCHEMA.mapToItem(item);
        assertThat(restored.getEnvironments()).isNull();
        assertThat(restored.getUseTypes()).isNull();
    }

    @Test
    @DisplayName("Jurisdiction.allowedUseTypes marshals to a DynamoDB String Set (SS)")
    void allowedUseTypes_isStringSet() {
        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setJurisdictionId(42);
        jurisdiction.setAllowedUseTypes(Set.of("PROVIDER"));

        AttributeValue allowedUseTypes =
                JURISDICTION_SCHEMA.itemToMap(jurisdiction, true).get("allowedUseTypes");

        assertThat(allowedUseTypes.ss()).containsExactly("PROVIDER");
        assertThat(allowedUseTypes.hasL()).isFalse();
    }

    @Test
    @DisplayName("A jurisdiction with no policy omits allowedUseTypes — absence is the deny-all state")
    void allowedUseTypes_absentMeansDenyAll() {
        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setJurisdictionId(42);

        Map<String, AttributeValue> item = JURISDICTION_SCHEMA.itemToMap(jurisdiction, true);

        assertThat(item).doesNotContainKey("allowedUseTypes");
        assertThat(JURISDICTION_SCHEMA.mapToItem(item).getAllowedUseTypes()).isNull();
    }
}
