package gov.cdc.izgateway.dynamodb.repository;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;

import gov.cdc.izgateway.dynamodb.model.Destination;
import gov.cdc.izgateway.hub.repository.IDestinationRepository;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Class representing the DynamoDb repository for Destinations.
 * 
 * @author Audacious Inquiry
 */
public class DestinationRepository extends DynamoDbRepository<Destination> implements IDestinationRepository<Destination> {
	/**
	 * Construct a new DestinationRepository from the DynamoDb enhanced client.
	 * @param client The client
	 * @param tableName The table to use
	 */
	public DestinationRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
		super(Destination.class, client, tableName);
	}
	
	@Override
	public List<Destination> findAllByDestTypeId(int destType) {
		return this.findByType(Integer.toString(destType) + "#");
	}

	@Override
	public Destination store(Destination dest) {
		if (dest == null) {
			throw new NullPointerException("Entity cannot be null");
		}
		return saveAndFlush(dest);
	}

	@Override
	public Destination newDestination() {
		return new Destination();
	}
}
