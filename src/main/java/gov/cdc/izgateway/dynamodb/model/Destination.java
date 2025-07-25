package gov.cdc.izgateway.dynamodb.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import gov.cdc.izgateway.common.HasDestinationUri;
import gov.cdc.izgateway.hub.service.JurisdictionService;
import gov.cdc.izgateway.model.AbstractDestination;
import gov.cdc.izgateway.model.DateConverter;
import gov.cdc.izgateway.model.DynamoDbEntity;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IDestinationId;
import gov.cdc.izgateway.model.IEndpoint;
import gov.cdc.izgateway.service.IJurisdictionService;
import gov.cdc.izgateway.utils.SystemUtils;
import java.io.Serializable;
import java.util.Date;

/**
 * A Destination stored in a Dynamo Database
 * @author Audacious Inquiry
 *
 */
@DynamoDbBean
@SuppressWarnings("serial")
public class Destination extends AbstractDestination implements DynamoDbEntity, IEndpoint, Serializable, HasDestinationUri, IDestination {

	
	
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
	public void setId(IDestinationId id) {
		// TODO Auto-generated method stub
		
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
}
