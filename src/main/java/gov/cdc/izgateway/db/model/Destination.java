package gov.cdc.izgateway.db.model;

import jakarta.persistence.*;
import gov.cdc.izgateway.common.HasDestinationUri;
import gov.cdc.izgateway.hub.service.JurisdictionService;
import gov.cdc.izgateway.model.AbstractDestination;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IDestinationId;
import gov.cdc.izgateway.model.IEndpoint;
import gov.cdc.izgateway.service.IJurisdictionService;
import gov.cdc.izgateway.utils.SystemUtils;
import java.io.Serializable;

/**
 * Implementation for RDS/MyDQL Destination entity
 * @author Audacious Inquiry
 */
@SuppressWarnings("serial")
@Entity
@Table(name = "destinations")
public class Destination extends AbstractDestination implements IEndpoint, Serializable, HasDestinationUri, IDestination {
	/**
	 * Create a destination
	 */
	public Destination() {
		getId().setDestType(SystemUtils.getDestType());
	}

	/**
	 * Create a new destination that is a copy of another one
	 * @param that The destination to copy
	 */
	public Destination(IDestination that) {
		super(that);
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
