package gov.cdc.izgateway.dynamodb.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;

import java.io.Serializable;
import java.util.Set;
import java.util.TreeSet;

import gov.cdc.izgateway.model.IAccessGroup;
import gov.cdc.izgateway.repository.EmptySetToNullConverter;
import gov.cdc.izgateway.model.DynamoDbAudit;
import gov.cdc.izgateway.model.DynamoDbEntity;

/**
 * DynamoDB entity for AccessGroup, representing an access control group record.
 * 
 * @author Audacious Inquiry
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper=false)
@AllArgsConstructor
@NoArgsConstructor
@DynamoDbBean
public class AccessGroup extends DynamoDbAudit implements DynamoDbEntity, Serializable, IAccessGroup {
    /** The name of the access group. */
    private String groupName;
    /** The environment (destination type) of the access group. */
    private int environment;
    /** The description of the access group. */
    private String description;
    /** The roles that the members of the group have. */
    private Set<String> roles = new TreeSet<>();
    /** The users that are members of the group. */
    private Set<String> users = new TreeSet<>();
    /** The groups that are members of the group. */
    private Set<String> groups = new TreeSet<>();
    
    @DynamoDbConvertedBy(EmptySetToNullConverter.class)
    public Set<String> getRoles() {
		if (roles == null) {
			roles = new TreeSet<>();
		}
		return roles;
	}
    @DynamoDbConvertedBy(EmptySetToNullConverter.class)
    public Set<String> getUsers() {
    	if (users == null) {
			users = new TreeSet<>();
    	}
		return users;
	}
    @DynamoDbConvertedBy(EmptySetToNullConverter.class)
    public Set<String> getGroups() {
    	if (groups == null) {
    		groups = new TreeSet<>();
    	}
		return groups;
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
            if (other.getRoles() != null) {
            	this.roles.addAll(other.getRoles());
            }
            if (other.getUsers() != null) {
            	this.users.addAll(other.getUsers());
            }
            if (other.getGroups() != null) {
            	this.groups.addAll(other.getGroups());
            }
        }
    }
    
	@Override
	public String getPrimaryId() {
		return String.format("%d#%s", this.environment, this.groupName);
	}
}