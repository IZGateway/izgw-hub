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
    private Instant issuedAt;
    private Instant expiresAt;
    private Instant revokedAt;
    private String revokedBy;

    @Override
    public String getPrimaryId() {
        return env + "#" + jti;
    }
}
