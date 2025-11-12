package gov.cdc.izgateway.hub.repository;

import gov.cdc.izgateway.model.IJurisdiction;
import gov.cdc.izgateway.repository.IRepository;

/**
 * The interface needed by JurisdictionService to access Jurisdiction information. 
 * @author Audacious Inquiry
 * @param <T> The type of Jurisdiction this repository manages
 */
public interface IJurisdictionRepository<T extends IJurisdiction> extends IRepository<T> {
}
