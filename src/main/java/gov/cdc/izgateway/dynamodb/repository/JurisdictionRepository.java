package gov.cdc.izgateway.dynamodb.repository;

import org.springframework.beans.factory.annotation.Autowired;
import gov.cdc.izgateway.dynamodb.DynamoDbRepository;
import gov.cdc.izgateway.dynamodb.model.Jurisdiction;
import gov.cdc.izgateway.model.IJurisdiction;
import gov.cdc.izgateway.repository.IJurisdictionRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Class representing the DynamoDb repository for Jurisdictions.
 * 
 * @author Audacious Inquiry
 */
public class JurisdictionRepository extends DynamoDbRepository<Jurisdiction> implements IJurisdictionRepository {
	/**
	 * Construct a new JurisdictionRepository from the DynamoDb enhanced client.
	 * @param client The client
	 */
	public JurisdictionRepository(@Autowired DynamoDbEnhancedClient client) {
		super(Jurisdiction.class, client);
	}
	
	@Override
	public IJurisdiction store(IJurisdiction entity) {
		if (entity instanceof Jurisdiction j) {
			return super.saveAndFlush(j);
		}
		return super.saveAndFlush(new Jurisdiction(entity));
	}
}
