package gov.cdc.izgateway.dynamodb.repository;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;

import gov.cdc.izgateway.dynamodb.model.Event;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.utils.StringUtils;

/**
 * Class representing the DynamoDb repository for Events.
 * Event track important activity in the system that may need to be shared between services.
 * Events create a "lock" on an activity while it is being processed to prevent duplicate processing.
 * The event has a type and a target. The type is the name of the event, and the target is the specific
 * instance of that event.
 * 
 * @author Audacious Inquiry
 */
@Slf4j
public class EventRepository extends DynamoDbRepository<Event> {
	private static final long EVENT_WAIT_TIMEOUT_MS = 10000;
	/**
	 * Construct a new JurisdictionRepository from the DynamoDb enhanced client.
	 * @param client The client
	 * @param tableName The table to use
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
		if (hasEventStarted(event.getName(), event.getTarget())) {
			long waited = 0;
			while (!hasEventFinished(event.getName(), event.getTarget())) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
				waited += 100;
				if (waited >= EVENT_WAIT_TIMEOUT_MS) {
					String msg = String.format("Event %s for target %s is still in progress after waiting %d ms",
							event.getName(), event.getTarget(), EVENT_WAIT_TIMEOUT_MS);
					log.error(msg);
					throw new IllegalStateException(msg);
				}
			}
			return null;
		}
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
	 * Store the event
	 * @param event	The event
	 * @return The updated event
	 */
	public Event store(Event event) {
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
		long started = System.currentTimeMillis() - EVENT_WAIT_TIMEOUT_MS;
		return !events.isEmpty() && events.stream().anyMatch(e -> 
			(e.getStarted() != null && e.getStarted().getTime() >= started) ||
			e.getCompleted() != null
		);
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
