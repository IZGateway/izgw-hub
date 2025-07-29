package gov.cdc.izgateway.dynamodb.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbIgnore;
import gov.cdc.izgateway.hub.service.JurisdictionService;
import gov.cdc.izgateway.model.AbstractDestination;
import gov.cdc.izgateway.model.DateConverter;
import gov.cdc.izgateway.model.DynamoDbEntity;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IEndpoint;
import gov.cdc.izgateway.service.IJurisdictionService;
import gov.cdc.izgateway.utils.SystemUtils;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A Destination stored in a Dynamo Database
 * @author Audacious Inquiry
 *
 */
@DynamoDbBean
@SuppressWarnings("serial")
public class Destination extends AbstractDestination implements DynamoDbEntity, IEndpoint, Serializable, IDestination {
	
	@Override
	@DynamoDbConvertedBy(DateConverter.class)
	public Date getMaintStart() {
		return super.getMaintStart();
	}

	@Override
	@DynamoDbConvertedBy(DateConverter.class)
	public Date getMaintEnd() {
		return super.getMaintEnd();
	}
	
	@Override
	@DynamoDbConvertedBy(DateConverter.class)
	public Date getPassExpiry() {
		return super.getPassExpiry();
	}

	/**
	 * Create a new Destination
	 */
	public Destination() {
		getId().setDestType(SystemUtils.getDestType());
	}
	
	/** 
	 * Make Dynamo ignore the DestinationId field.
	 */
	@Override
	@DynamoDbIgnore
	public DestinationId getId() {
		return super.getId();
	}

	/**
	 * Create a new Destination as a copy of an existing one.
	 * @param that The destination to make a copy of
	 */
	public Destination(IDestination that) {
		super(that);
	}
	
	@Override
	public String getPrimaryId() {
		return getId().toString();
	}

	@Override
	public Destination safeCopy() {
		Destination p = new Destination(this);
		p.maskCredentials();
		return p;
	}


	@Override
	public IJurisdictionService getJurisdictionService() {
		return JurisdictionService.getInstance();
	}
	
	@Override
	@Schema(description = "The destination identifier")
	public String getDestId() {
		return getId().getDestId();
	}

	/**
	 * Set the destination id. A setter must be available
	 * for DynamoDb to operate on this field. 
	 * @param id
	 */
	public void setDestId(String id) {
		getId().setDestId(id);
	}
	
	@Override
	@JsonIgnore
	public int getDestTypeId() {
		return getId().getDestType();
	}

	@Override
	public void setDestTypeId(int destType) {
		getId().setDestType(destType);
	}
}
