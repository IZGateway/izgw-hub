package gov.cdc.izgateway.dynamodb.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

import gov.cdc.izgateway.dynamodb.DateConverter;
import gov.cdc.izgateway.dynamodb.DynamoDbEntity;
import gov.cdc.izgateway.model.MappableEntity;
import gov.cdc.izgateway.utils.SystemUtils;


/**
 * Supports Event Tracking
 *
 * @author Audacious Inquiry
 */
@SuppressWarnings("serial")
@Data
@EqualsAndHashCode(callSuper=false)
@AllArgsConstructor
@NoArgsConstructor
@DynamoDbBean
public class Event extends DynamoDbEntity implements Serializable {
	// Well known events.
	/** The Migration event */
	public static final String MIGRATION = "Migration";
	/** The Startup event */
	public static final String STARTUP = "Startup";
	/** The Shutdown event */
	public static final String SHUTDOWN = "Shutdown";
	/** The Global Table Creation event */
	public static final String CREATED = "Created";
	
	/**
	 * Provides easy access to the Map class for Swagger documentation
	 */
	public static class Map extends MappableEntity<Event>{}

    @Schema(description = "The event name")
    private String name;

    @Schema(description = "The target of the event")
    private String target;
    
    @Schema(description = "Start timestamp")
    private Date started = new Date();
    
    @Schema(description = "Completed timestamp")
    private Date completed;
    
    @Schema(description = "System reporting the event")
    private String reportedBy = SystemUtils.getHostname();
    
    @Schema(description = "The Unique Id of the event")
    private String eventId = UUID.randomUUID().toString();
    
    /**
     * Create a new event.
     * @param name	The name of the event (e.g., Migration)
     */
    public Event(String name) {
    	this(name, null);
    }
    
    /**
     * Create a new event.
     * @param name	The name of the event (e.g., Migration)
     * @param target The target of the event.
     */
    public Event(String name, String target) {
    	this.name = name;
    	this.target = target;
    }    
    
	@Override
	public String getPrimaryId() {
		if (StringUtils.isEmpty(name)) {
			throw new IllegalArgumentException("Event name cannot be null or empty");
		}
		if (started == null) {
			started = new Date();
		}
		return name + "#" + StringUtils.defaultString(target) + "#" + DateConverter.convert(started);
	}
	
    /**
     * Get the start time of the event as a date
     * @return	The start time of the event
     */
    @DynamoDbConvertedBy(DateConverter.class)
    public Date getStarted() {
    	return started;
    }
    
    /**
     * Get the completion time of the event as a date
     * @return	The completion time of the event
     */
    @DynamoDbConvertedBy(DateConverter.class)
    public Date getCompleted() {
    	return completed;
    }
}
