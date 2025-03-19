package gov.cdc.izgateway.db.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import gov.cdc.izgateway.common.Constants;
import gov.cdc.izgateway.hub.service.JurisdictionService;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IEndpoint;
import gov.cdc.izgateway.model.IEndpointStatus;
import gov.cdc.izgateway.model.IJurisdiction;
import gov.cdc.izgateway.model.RetryStrategy;
import gov.cdc.izgateway.soap.fault.Fault;
import gov.cdc.izgateway.utils.SystemUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonFormat.Shape;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.util.Date;
import java.util.TreeMap;


/**
 * This class reports on the status of a destination at a given point in time.
 */
@Schema(description="This class reports on the status of a destination at a given point in time.")
@Data
@SuppressWarnings("serial")
@JsonPropertyOrder({"destId", "destType", "destTypeId", "destUri", "destVersion", "status", "statusAt", "statusBy", "detail", "diagnostics", "retryStrategy"})
public class EndpointStatus implements IEndpoint, Serializable, IEndpointStatus {
	public static class Map extends TreeMap<String, EndpointStatus>{}

	@JsonIgnore
	@Schema(description="The identifier of this status record", hidden=true)
    private int statusId;
    
	@Schema(description="The identifier of destination")
    private String destId;
	@Getter(AccessLevel.NONE)
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

	public EndpointStatus() { }
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
	@Schema(hidden=true)
	public boolean isConnected() {
		return CONNECTED.equalsIgnoreCase(status);
	}
	
	@Override
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
	@JsonIgnore
	@Schema(hidden=true)
	public boolean isAvailable() {
		return CONNECTED.equalsIgnoreCase(status) ||
				RetryStrategy.CORRECT_MESSAGE.toString().equalsIgnoreCase(retryStrategy);
	}
	
	@Schema(description="THe environment the destination is being used in")
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
	@Schema(description="The identifiier for the jurisdiction responsible for the endpoint", hidden=true)
	public int getJurisdictionId() {
		return jurisdictionId;
	}
	@Schema(description="Returns the name of the jurisdiction responsible for the endpoint")
	public String getJurisdictionName() {
		IJurisdiction j = JurisdictionService.getInstance().getJurisdiction(jurisdictionId);
		return j == null ? null : j.getName();
	}
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
        setStatusAt(new Date());
        setStatusBy(SystemUtils.getHostname());
        return getStatus();
	}
	
	/**
	 * Update the status from a fault.
	 * @param f	The fault to update it from
	 * @return The status
	 */
	public EndpointStatus fromFault(Fault f) {
		setStatus(f.getSummary());
		setDetail(f.getDetail());
		setDiagnostics(f.getDiagnostics());
		setRetryStrategy(f.getRetry().toString());
		setStatusAt(new Date());
		setStatusBy(SystemUtils.getHostname());
		return this;
	}
}
