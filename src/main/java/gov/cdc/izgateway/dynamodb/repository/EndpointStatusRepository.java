package gov.cdc.izgateway.dynamodb.repository;

import java.time.Period;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;

import gov.cdc.izgateway.dynamodb.DynamoDbRepository;
import gov.cdc.izgateway.dynamodb.model.EndpointStatus;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IEndpointStatus;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Class representing the DynamoDb repository for EndpointStatus.
 * 
 * Unlike previous versions, IZGW 2.2 will track status in DynamoDb rather than in Elastic
 * 
 * TODO:
 * 1. Need to set time-to-live (TTL) attribute on these entities so that they are deleted
 * after an hour.
 * 2. Update the last entity as long as it is within the 15 minute period (e.g., don't let history grow
 * w/o bounds).
 * 3. Figure out how query works for this entity.
 *  
 * 
 * @author Audacious Inquiry
 */
public class EndpointStatusRepository extends DynamoDbRepository<EndpointStatus> implements gov.cdc.izgateway.repository.EndpointStatusRepository {
	/**
	 * Construct a new EndpointStatusRepository from the DynamoDb enhanced client.
	 * @param client The client
	 */
	public EndpointStatusRepository(@Autowired DynamoDbEnhancedClient client) {
		super(EndpointStatus.class, client);
	}
	
	@Override
	public IEndpointStatus saveAndFlush(IEndpointStatus dest) {
		if (dest == null) {
			throw new NullPointerException("Entity cannot be null");
		}
		if (dest instanceof EndpointStatus d) {
			return super.saveAndFlush(d);
		} 
		return super.saveAndFlush(new EndpointStatus(dest));
	}

	@Override
	public IEndpointStatus findById(String id) {
		return find(id);
	}

	@Override
	public boolean removeById(String id) {
		boolean result = find(id) != null;
		this.delete(id);
		return result;
	}

	@Override
	public List<? extends IEndpointStatus> find(int maxQuarterHours, String[] include) {
		long time = System.currentTimeMillis() - (TimeUnit.MINUTES.toMillis(15) * maxQuarterHours);
		return null;
	}
	

	@Override
	public boolean refresh() {
		// Does nothing in the DynamoDb case
		return true;
	}

	@Override
	public IEndpointStatus newEndpointStatus() {
		return new EndpointStatus();
	}

	@Override
	public IEndpointStatus newEndpointStatus(IDestination dest) {
		return new EndpointStatus(dest);
	}
}
