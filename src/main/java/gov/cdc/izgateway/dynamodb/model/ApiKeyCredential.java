package gov.cdc.izgateway.dynamodb.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

import java.time.Instant;
import java.util.Set;

import gov.cdc.izgateway.model.DynamoDbAudit;
import gov.cdc.izgateway.model.DynamoDbEntity;

@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@DynamoDbBean
public class ApiKeyCredential extends DynamoDbAudit implements DynamoDbEntity {

    private String jti;
    // Server-side set of numeric environment IDs (values 1-6 per the IZG Environment enumeration) in
    // which this credential is valid. Written by Config Console (IGDD-3140); read by Hub at routing time.
    // Environment authorization is NOT carried in the JWT — Hub looks the credential up by jti and checks
    // that its own env (SystemUtils.getDestType()) is contained in this set. The set can change without
    // rewriting the sort key. Standard credentials contain exactly one ID; admin/operational credentials
    // MAY contain several.
    //
    // Persisted as a DynamoDB Number Set (NS). DynamoDB cannot store an empty set, so "no environments"
    // is represented by the attribute being absent, which deserializes to null here — null and empty are
    // therefore equivalent and both deny. See ApiKeyPrincipalProvider#lookupAndCacheCredential.
    private Set<Integer> environments;
    // Server-side set of use-types (PATIENT / PROVIDER / PUBLIC_HEALTH per gov.cdc.izgateway.hub.security.UseType)
    // this credential may submit. Written by Config Console (IGDD-3140), which validates the values against the
    // enumeration; read by Hub at routing time and intersected with the destination jurisdiction's
    // allowedUseTypes (IGDD-3257). Like `environments`, it is NOT carried in the JWT, so policy can change
    // without reissuing the credential.
    //
    // Persisted as a DynamoDB String Set (SS). As with `environments`, an absent attribute deserializes to
    // null, and null and empty are equivalent: a credential with no useTypes is authorized for nothing.
    private Set<String> useTypes;
    private String status;
    private String jurisdictionId;
    // Serialized as ISO-8601 UTC strings (e.g. 2025-06-04T00:00:00Z) by the AWS SDK Enhanced Client's
    // default InstantAsStringAttributeConverter. Distinct from the Date fields in DynamoDbAudit.
    private Instant issuedAt;
    private Instant expiresAt;
    private Instant revokedAt;
    private String revokedBy;
    // Written by Hub's grace-period sweep (IGDD-3167) when a grace-expired credential reached its own
    // expiresAt on/before graceExpiresAt. Mutually exclusive with revokedAt/revokedBy: a credential
    // transitions to exactly one terminal status, so only the matching pair is ever populated.
    private Instant expiredAt;
    private String expiredBy;
    // Written by Config Console on renewal (IGDD-2707) onto the superseded (old) credential, which is
    // moved to status "grace_period"; read by Hub's grace-period revocation job (IGDD-2711).
    // graceExpiresAt is the instant after which the grace-period credential becomes eligible for
    // automated revocation; supersededBy is the jti of the renewed credential that replaced it. Both
    // are null on credentials that have never been renewed. graceExpiresAt serializes as an ISO-8601
    // UTC string like the other Instants.
    private Instant graceExpiresAt;
    private String supersededBy;

    @Override
    public String getPrimaryId() {
        // Sort key is the jti alone (no environment prefix); permitted environments are the server-side
        // `environments` set rather than being encoded in the key (IGDD-3140).
        return jti;
    }
}
