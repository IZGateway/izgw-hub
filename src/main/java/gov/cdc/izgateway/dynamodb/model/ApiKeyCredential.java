package gov.cdc.izgateway.dynamodb.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

import java.time.Instant;

import gov.cdc.izgateway.model.DynamoDbAudit;
import gov.cdc.izgateway.model.DynamoDbEntity;

@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@DynamoDbBean
public class ApiKeyCredential extends DynamoDbAudit implements DynamoDbEntity {

    private String jti;
    private String env;
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
        return env + "#" + jti;
    }
}
