package gov.cdc.izgateway.dynamodb.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;

import java.io.Serializable;
import java.util.Date;

import gov.cdc.izgateway.model.IAllowedUser;
import gov.cdc.izgateway.model.DateConverter;
import gov.cdc.izgateway.model.DynamoDbAudit;
import gov.cdc.izgateway.model.DynamoDbEntity;

/**
 * DynamoDB entity for AllowedUser, representing a user allowed to send to a destination.
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper=false)
@AllArgsConstructor
@NoArgsConstructor
@DynamoDbBean
public class AllowedUser extends DynamoDbAudit implements DynamoDbEntity, Serializable, IAllowedUser {
    private String destinationId;
    private int environment;
    private String organization;
    private String principal;
    private boolean enabled;
    private Date validatedOn;
    /**
     * Copy constructor
     * @param other the other AllowedUser object to copy from
     */
    public AllowedUser(IAllowedUser other) {
        super(other);
        if (other != null) {
            this.destinationId = other.getDestinationId();
            this.environment = other.getEnvironment();
            this.principal = other.getPrincipal();
            this.organization = other.getOrganization();
            this.enabled = other.isEnabled();
            this.validatedOn = other.getValidatedOn();
        }
    }
	@Override
	public String getPrimaryId() {
		return String.format("%d#%s#%s", this.environment, this.destinationId, this.principal);
	}
    @Override
    @DynamoDbConvertedBy(DateConverter.class)
    public Date getValidatedOn() {
    	return validatedOn;
    }
    @Override
    @DynamoDbConvertedBy(DateConverter.class)
    public void setValidatedOn(Date validatedOn) {
    	this.validatedOn = validatedOn;
    }
}