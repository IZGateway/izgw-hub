package gov.cdc.izgateway.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import gov.cdc.izgateway.hub.service.JurisdictionService;
import gov.cdc.izgateway.model.AbstractEndpointStatus;
import gov.cdc.izgateway.model.DynamoDbEntity;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IEndpoint;
import gov.cdc.izgateway.model.IEndpointStatus;
import gov.cdc.izgateway.service.IJurisdictionService;
import io.swagger.v3.oas.annotations.media.Schema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import java.io.Serializable;
import java.util.TreeMap;


/**
 * This class reports on the status of a destination at a given point in time.
 */
@DynamoDbBean
@Schema(description="This class reports on the status of a destination at a given point in time.")
@SuppressWarnings("serial")
@JsonPropertyOrder({"destId", "destType", "destTypeId", "destUri", "destVersion", "status", "statusAt", "statusBy", "detail", "diagnostics", "retryStrategy"})
public class EndpointStatus extends AbstractEndpointStatus implements DynamoDbEntity, IEndpoint, Serializable, IEndpointStatus {
	/**
	 * Map of statushistory entities reported by /statushistory API 
	 * @author Audacious Inquiry
	 */
	public static class Map extends TreeMap<String, EndpointStatus>{}

	/**
	 * Create a new EndpointStatus entity.
	 */
	public EndpointStatus() {
	}
	
	
	/**
	 * Copy an EndpointStatus entity.
	 * @param that	The entity to copy
	 */
	public EndpointStatus(IEndpointStatus that) {
		super(that);
	}

	/**
	 * Create an endpoint status entity from a destination.
	 * 
	 * @param dest	The destination to copy from
	 */
	public EndpointStatus(IDestination dest) {
		super(dest);
	}
	
	@Override
	public IEndpointStatus copy() {
		return new EndpointStatus(this);
	}
	

	@Override
	public IJurisdictionService getJurisdictionService() {
		return JurisdictionService.getInstance();
	}
}
