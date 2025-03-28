package gov.cdc.izgateway.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import gov.cdc.izgateway.common.Constants;
import gov.cdc.izgateway.dynamodb.DynamoDbEntity;
import gov.cdc.izgateway.hub.service.JurisdictionService;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IEndpoint;
import gov.cdc.izgateway.model.IEndpointStatus;
import gov.cdc.izgateway.model.IJurisdiction;
import gov.cdc.izgateway.model.RetryStrategy;
import gov.cdc.izgateway.utils.SystemUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;

import com.fasterxml.jackson.annotation.JsonFormat.Shape;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.util.Date;
import java.util.TreeMap;


/**
 * This class reports on the status of a destination at a given point in time.
 */
@DynamoDbBean
@Schema(description="This class reports on the status of a destination at a given point in time.")
@Data
@EqualsAndHashCode(callSuper=false)
@SuppressWarnings("serial")
@JsonPropertyOrder({"destId", "destType", "destTypeId", "destUri", "destVersion", "status", "statusAt", "statusBy", "detail", "diagnostics", "retryStrategy"})
public class EndpointStatus extends DynamoDbEntity implements IEndpoint, Serializable, IEndpointStatus {
	/**
	 * Map of statushistory entities reported by /statushistory API 
	 * @author Audacious Inquiry
	 */
	public static class Map extends TreeMap<String, EndpointStatus>{}

	@JsonIgnore
	@Schema(description="The identifier of this status record", hidden=true)
    private int statusId;
	public int getStatusId() {
		return statusId;
	}
    
	@Schema(description="The identifier of destination")
    private String destId;
	@Setter(AccessLevel.NONE)
    private int destType = SystemUtils.getDestType();
	
	@Schema(description="When the status was captured")
    @JsonFormat(shape=Shape.STRING, pattern = Constants.TIMESTAMP_FORMAT)
    private Date statusAt;
	@Schema(description="Which instance captured the status")
	private String statusBy;
    
	@Schema(description="The destination endpoint URI")
    private String destUri;
	@Schema(description="The schema version supported by the endpoint")
    private String destVersion;
	@Schema(description="The endpoint status")
    private String status;
	@Schema(description="THe reason for the present status")
    private String detail;
	@Schema(description="Retry strategy for accessing the endpoint")
    private String retryStrategy;
	@Schema(description="Any diagnostics to address the status")
    private String diagnostics;

	@JsonIgnore
	@Schema(description="The identifier of the jurisdiction for this endpoint", hidden=true)
	private int jurisdictionId;

	/**
	 * Create a new EndpointStatus entity.
	 */
	public EndpointStatus() { 
		destType = SystemUtils.getDestType();
	}
	
	
	/**
	 * Copy an EndpointStatus entity.
	 * @param that	The entity to copy
	 */
	public EndpointStatus(IEndpointStatus that) { 
		statusId = that.getStatusId();
		destId = that.getDestId();
		destType = that.getDestTypeId();
		statusAt = that.getStatusAt();
		statusBy = that.getStatusBy();
		detail = that.getDetail();
		retryStrategy = that.getRetryStrategy();
		destUri = that.getDestUri();
		diagnostics = that.getDiagnostics();
		jurisdictionId = that.getJurisdictionId();
		destVersion = that.getDestVersion();
		status = that.getStatus();
	}

	/**
	 * Create an endpoint status entity from a destination.
	 * 
	 * @param dest	The destination to copy from
	 */
	public EndpointStatus(IDestination dest) {
		this();
		if (dest == null) {
			throw new IllegalArgumentException("dest must not be null");
		}
		this.destId = dest.getDestId();
		this.destUri = dest.getDestUri();
		this.destType = dest.getDestTypeId();
		this.destVersion = dest.getDestVersion();
		this.statusBy = SystemUtils.getHostname();
		this.statusAt = new Date();
		this.jurisdictionId = dest.getJurisdictionId();
	}
	
	@Override
	public IEndpointStatus copy() {
		return new EndpointStatus(this);
	}
	
	/**
	 * Set status and provenance for it.
	 * @param status	The status value to set.
	 */
	@Override
	public void setStatus(String status) {
		this.statusAt = new Date();
		this.statusBy = SystemUtils.getHostname();
		this.status = status;
	}

	/**
	 * Return true if the destination is connected.
	 * @return true if the destination is connected.
	 */
	@Override
	@JsonIgnore
	@DynamoDbIgnore
	@Schema(hidden=true)
	public boolean isConnected() {
		return CONNECTED.equalsIgnoreCase(status);
	}
	
	@Override
	@DynamoDbIgnore
	@JsonIgnore
	@Schema(hidden=true)
	public boolean isCircuitBreakerThrown() {
		return CIRCUIT_BREAKER_THROWN.equalsIgnoreCase(status);
	}
	
	/**
	 * Return true if, according to the current status, a new
	 * connection should be attempted.  A recent failure due to
	 * an invalid inbound message should NOT disable an outbound
	 * endpoint.
	 * 
	 * @return true if the destination is connected.
	 */
	@Override
	@DynamoDbIgnore
	@JsonIgnore
	@Schema(hidden=true)
	public boolean isAvailable() {
		return CONNECTED.equalsIgnoreCase(status) ||
				RetryStrategy.CORRECT_MESSAGE.toString().equalsIgnoreCase(retryStrategy);
	}
	
	@Schema(description="The environment the destination is being used in")
	@DynamoDbIgnore
	public String getDestType() {
		return SystemUtils.getDestTypes().get(destType-1);
	}
	
	@Override
	public void setDestTypeId(int destType) {
		this.destType = destType;
	}

	@Schema(description="The identifier for environment the destination is being used in", hidden=true)
	public int getDestTypeId() {
		return destType;
	}
	
	@JsonIgnore
	@Schema(description="The identifier for the jurisdiction responsible for the endpoint", hidden=true)
	public int getJurisdictionId() {
		return jurisdictionId;
	}
	
	@DynamoDbIgnore
	@Schema(description="Returns the name of the jurisdiction responsible for the endpoint")
	public String getJurisdictionName() {
		IJurisdiction j = JurisdictionService.getInstance().getJurisdiction(jurisdictionId);
		return j == null ? null : j.getName();
	}
	
	@DynamoDbIgnore
	@Schema(description="Returns the description of the jurisdiction responsible for the endpoint")
	public String getJurisdictionDesc() {
		IJurisdiction j = JurisdictionService.getInstance().getJurisdiction(jurisdictionId);
		return j == null ? null : j.getDescription();
	}
	
	@Override
	public String connected() {
        setStatus(IEndpointStatus.CONNECTED);
        setDetail(null);
        setDiagnostics(null);
        setRetryStrategy(null);
        return getStatus();
	}

	@Override
	public String getPrimaryId() {
		return String.format("%tFT%tH", statusAt, statusAt);
	}
}
