package gov.cdc.izgateway.dynamodb.model;

import java.util.List;

import gov.cdc.izgateway.dynamodb.DynamoDbEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * An entity to manage allowed events for each route.
 * 
 * @author Audacious Inquiry
 *
 */
@Data
@EqualsAndHashCode(callSuper=false)
public class Route extends DynamoDbEntity {
	String name;
	int environment;
	List<String> allowedEvents;
	
	@Override
	public String primaryId() {
		return getEnvironment() + "#" + name;
	}
}
