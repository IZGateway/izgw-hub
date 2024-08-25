package gov.cdc.izgateway.dynamodb.model;

import java.util.List;

import gov.cdc.izgateway.dynamodb.DynamoDbEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * An entity representing a group
 * @author Audacious Inquiry
 */
@Data
@EqualsAndHashCode(callSuper=false)
public class Group extends DynamoDbEntity {
	String name;
	int environment;
	List<String> allowedMembers;
	List<String> notAllowedMembers;
	
	@Override
	public String primaryId() {
		return String.format("%d#%s", environment, name);
	}
}
