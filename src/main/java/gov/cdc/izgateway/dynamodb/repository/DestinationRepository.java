package gov.cdc.izgateway.dynamodb.repository;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import gov.cdc.izgateway.dynamodb.DynamoDbRepository;
import gov.cdc.izgateway.dynamodb.model.Destination;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.repository.IDestinationRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Class representing the DynamoDb repository for Destinations.
 * 
 * @author Audacious Inquiry
 */
@Repository
public class DestinationRepository extends DynamoDbRepository<Destination> implements IDestinationRepository {
	/**
	 * Construct a new DestinationRepository from the DynamoDb enhanced client.
	 * @param client The client
	 */
	public DestinationRepository(@Autowired DynamoDbEnhancedClient client) {
		super(Destination.class, client);
	}
	
	@Override
	public List<? extends IDestination> findAllByDestTypeId(int destType) {
		return this.findByType(Integer.toString(destType) + "#");
	}

	@Override
	public Destination saveAndFlush(IDestination dest) {
		if (dest == null) {
			throw new NullPointerException("Entity cannot be null");
		}
		if (dest instanceof Destination d) {
			return saveAndFlush(d);
		} 
		return saveAndFlush(new Destination(dest));
	}
}
