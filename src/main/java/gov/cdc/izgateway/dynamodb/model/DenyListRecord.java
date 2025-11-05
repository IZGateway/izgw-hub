package gov.cdc.izgateway.dynamodb.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import java.io.Serializable;
import gov.cdc.izgateway.model.IDenyListRecord;
import gov.cdc.izgateway.utils.SystemUtils;
import gov.cdc.izgateway.model.DynamoDbAudit;
import gov.cdc.izgateway.model.DynamoDbEntity;
import gov.cdc.izgateway.model.IAccessControl;

/**
 * DynamoDB entity for DenyListRecord, representing a user denied access to IZ Gateway.
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper=false)
@AllArgsConstructor
@NoArgsConstructor
@DynamoDbBean
public class DenyListRecord extends DynamoDbAudit implements DynamoDbEntity, Serializable, IDenyListRecord {
    private String principal;
    private Integer environment;
    private String reason;
	@Override
	public String getPrimaryId() {
		// The sort key is {environment}#{principal}
		return String.format("%d#%s", this.environment, this.principal);
	}

    /**
     * Copy constructor
     * @param other	the other DenyListRecord object to copy from
     */
    public DenyListRecord(IDenyListRecord other) {
        super(other);
        if (other != null) {
            this.principal = other.getPrincipal();
            this.environment = other.getEnvironment();
            this.reason = other.getReason();
        }
    }

	public DenyListRecord(IAccessControl ac, String reportedBy, java.util.Date reportedOn) {
		setPrincipal(ac.getMember());
		setEnvironment(SystemUtils.getDestType());
		setReason("Migrated from Access Control list");
		setCreatedBy(reportedBy);
		setCreatedOn(reportedOn);
	}
}