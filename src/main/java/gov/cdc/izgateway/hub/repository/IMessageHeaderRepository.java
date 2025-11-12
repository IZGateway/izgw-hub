package gov.cdc.izgateway.hub.repository;

import gov.cdc.izgateway.model.IMessageHeader;
import gov.cdc.izgateway.repository.IRepository;

/**
 * The IMessageHeaderRepository interface supports access to message header records
 * @author Audacious Inquiry
 */
public interface IMessageHeaderRepository extends IRepository<IMessageHeader> {
	/** 
	 * Delete a record by the identifier 
	 * @param id The identifier
	 */
	void deleteById(String id);
}
