package gov.cdc.izgateway.dynamodb.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import gov.cdc.izgateway.model.IOrganizationRecord;
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
    private Set<String> principalNames = new LinkedHashSet<>();
    private String organizationName;
	public Set<String> getPrincipalNames() {
		return Collections.unmodifiableSet(principalNames);
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
        this.principalNames = new LinkedHashSet<>();
        if (other != null) {
            if (other.getPrincipalNames() != null) {
                this.principalNames.addAll(other.getPrincipalNames());
            }
            this.organizationName = other.getOrganizationName();
        }
    }
}