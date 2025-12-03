package gov.cdc.izgateway.dynamodb.repository;

import org.springframework.beans.factory.annotation.Autowired;

import gov.cdc.izgateway.dynamodb.model.Jurisdiction;
import gov.cdc.izgateway.hub.repository.IJurisdictionRepository;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Class representing the DynamoDb repository for Jurisdictions.
 * 
 * @author Audacious Inquiry
 */
public class JurisdictionRepository extends DynamoDbRepository<Jurisdiction> implements IJurisdictionRepository<Jurisdiction> {
	/**
	 * Construct a new JurisdictionRepository from the DynamoDb enhanced client.
	 * @param client The client
	 * @param tableName The table to use
	 */
	public JurisdictionRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
		super(Jurisdiction.class, client, tableName);
	}
	
	@Override
	public Jurisdiction store(Jurisdiction entity) {
		return saveAndFlush(entity);
	}
}
