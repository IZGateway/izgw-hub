package gov.cdc.izgateway.dynamodb.repository;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import gov.cdc.izgateway.dynamodb.DynamoDbRepository;
import gov.cdc.izgateway.dynamodb.model.Event;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.utils.StringUtils;

/**
 * Class representing the DynamoDb repository for Events.
 * Event track important activity in the system that may need to be shared between services.
 * 
 * @author Audacious Inquiry
 */
public class EventRepository extends DynamoDbRepository<Event> {
	
	/**
	 * Construct a new JurisdictionRepository from the DynamoDb enhanced client.
	 * @param client The client
	 */
	public EventRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
		super(Event.class, client, tableName);
	}
	
	/**
	 * Create the event
	 * @param event	The event
	 * @return The event, or null if the event already exists.
	 */
	public Event create(Event event) {
		return this.saveIfNotExists(event);
	}
	
	/**
	 * Update the event
	 * @param event	The event
	 * @return The updated event
	 */
	public Event update(Event event) {
		return this.saveAndFlush(event);
	}
	
	
	/**
	 * Find all events with the given name
	 * @param name	The name of the event.
	 * @return	The list of events
	 */
	public List<Event> findByName(String name) {
		return findByType(name + "#");
	}
	
	/**
	 * Find all events with the given name and target.
	 * @param name	The name of the event. 
	 * @param target	The target of the event.
	 * @return	The events.
	 */
	public List<Event> findByNameAndTarget(String name, String target) {
		if (StringUtils.isEmpty(name)) {
			throw new IllegalArgumentException("Name cannot be null or empty");
		}
		if (StringUtils.isEmpty(target)) {
			return findByType(name + "#");
		}
		return findByType(name + "#" + target + "#");
	}

	/**
	 * Determine if a particular event has been started
	 * @param name	The name of the event
	 * @param target	The target of the event
	 * @return	true if the event was started, false if not.
	 */
	public boolean hasEventStarted(String name, String target) {
		List<Event> events = findByNameAndTarget(name, target);
		return !events.isEmpty();
	}
	
	/**
	 * Determine if a particular event has completed
	 * @param name	The name of the event
	 * @param target	The target of the event
	 * @return	true if the event was completed, false if not.
	 */
	public boolean hasEventFinished(String name, String target) {
		List<Event> events = findByNameAndTarget(name, target);
		return events.stream().anyMatch(e -> e.getCompleted() != null);
	}
}
