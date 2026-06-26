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
    // Serialized as ISO-8601 UTC strings by the AWS SDK Enhanced Client's default
    // InstantAsStringAttributeConverter. Distinct from the Date fields in DynamoDbAudit.
    private Instant issuedAt;
    private Instant expiresAt;
    private Instant revokedAt;
    private String revokedBy;
    // Written by Config Console on renewal (IGDD-2707) onto the superseded (old) credential; read by
    // Hub's grace-period revocation job (IGDD-2711). graceExpiresAt is the instant after which a
    // superseded-but-still-active key becomes eligible for automated revocation. supersededBy is the
    // jti of the renewed credential that replaced this one. Both are null on credentials that have
    // never been renewed. graceExpiresAt serializes as an ISO-8601 UTC string like the other Instants.
    private Instant graceExpiresAt;
    private String supersededBy;

    @Override
    public String getPrimaryId() {
        return env + "#" + jti;
    }
}
