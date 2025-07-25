package gov.cdc.izgateway.dynamodb.repository;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import gov.cdc.izgateway.dynamodb.DynamoDbRepository;
import gov.cdc.izgateway.dynamodb.model.Destination;
import gov.cdc.izgateway.hub.repository.IDestinationRepository;
import gov.cdc.izgateway.model.AbstractDestination;
import gov.cdc.izgateway.model.IDestination;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Class representing the DynamoDb repository for Destinations.
 * 
 * @author Audacious Inquiry
 */
public class DestinationRepository extends DynamoDbRepository<Destination> implements IDestinationRepository {
	/**
	 * Construct a new DestinationRepository from the DynamoDb enhanced client.
	 * @param client The client
	 * @param tableName The table to use
	 */
	public DestinationRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
		super(Destination.class, client, tableName);
	}
	
	@Override
	public List<? extends IDestination> findAllByDestTypeId(int destType) {
		return this.findByType(Integer.toString(destType) + "#");
	}

	@Override
	public Destination store(IDestination dest) {
		if (dest == null) {
			throw new NullPointerException("Entity cannot be null");
		}
		if (dest instanceof Destination d) {
			return saveAndFlush(d);
		} 
		return saveAndFlush(new Destination(dest));
	}

	@Override
	public AbstractDestination newDestination() {
		return new Destination();
	}
}
