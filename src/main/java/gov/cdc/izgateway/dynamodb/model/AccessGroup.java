package gov.cdc.izgateway.dynamodb.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import java.io.Serializable;
import java.util.Date;
import java.util.List;
import gov.cdc.izgateway.model.IAccessGroup;
import gov.cdc.izgateway.model.DynamoDbAudit;
import gov.cdc.izgateway.model.DynamoDbEntity;

/**
 * DynamoDB entity for AccessGroup, representing an access control group record.
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper=false)
@AllArgsConstructor
@NoArgsConstructor
@DynamoDbBean
public class AccessGroup extends DynamoDbAudit implements DynamoDbEntity, Serializable, IAccessGroup {
    private String groupName;
    private Integer environment;
    private String description;
    private List<String> roles;
    private List<String> users;
    private List<String> groups;
    private Date lastChanged;
	@Override
	public String getPrimaryId() {
		return String.format("%d#%s", this.environment, this.groupName);
	}

    /**
     * Copy constructor
     * @param other	the other AccessGroup object to copy from
     */
    public AccessGroup(IAccessGroup other) {
        super(other);
        if (other != null) {
            this.groupName = other.getGroupName();
            this.environment = other.getEnvironment();
            this.description = other.getDescription();
            this.roles = other.getRoles() != null ? List.copyOf(other.getRoles()) : null;
            this.users = other.getUsers() != null ? List.copyOf(other.getUsers()) : null;
            this.groups = other.getGroups() != null ? List.copyOf(other.getGroups()) : null;
            this.lastChanged = other.getLastChanged();
        }
    }
}