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

import gov.cdc.izgateway.model.IOrganizationRecord;
import gov.cdc.izgateway.repository.EmptySetToNullConverter;
import gov.cdc.izgateway.model.DynamoDbAudit;
import gov.cdc.izgateway.model.DynamoDbEntity;

/**
 * DynamoDB entity for OrganizationRecord, representing an organization record with principal names and audit info.
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper=false)
@AllArgsConstructor
@NoArgsConstructor
@DynamoDbBean
public class OrganizationRecord extends DynamoDbAudit implements DynamoDbEntity, Serializable, IOrganizationRecord {
	/** The type of organization, e.g., IIS, Provider, Consumer, CDC, Support */
	private String type;
    private Set<String> principalNames = new TreeSet<>();
    private String organizationName;
    
    @DynamoDbConvertedBy(EmptySetToNullConverter.class)
	public Set<String> getPrincipalNames() {
    	if (principalNames == null) {
			principalNames = new TreeSet<>();
		}
		return principalNames;
	}

	@Override
	public boolean addPrincipalName(String principalName) {
		return principalNames.add(principalName);
	}
	@Override
	public boolean removePrincipalName(String principalName) {
		return principalNames.add(principalName);
	}
	@Override
	public String getPrimaryId() {
		return organizationName;
	}

    /**
     * Copy constructor
     * @param other	the other OrganizationRecord object to copy from
     */
    public OrganizationRecord(IOrganizationRecord other) {
        super(other);
        if (other != null) {
            this.organizationName = other.getOrganizationName();
            if (other.getPrincipalNames() != null) {
                this.principalNames.addAll(other.getPrincipalNames());
            }
        }
    }
}