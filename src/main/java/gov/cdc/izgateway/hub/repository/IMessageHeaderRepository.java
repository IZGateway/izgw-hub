package gov.cdc.izgateway.hub.repository;

import gov.cdc.izgateway.model.IMessageHeader;
import gov.cdc.izgateway.repository.IRepository;

/**
 * The IMessageHeaderRepository interface supports access to message header records
 * @param <T> The type of message header
 * @author Audacious Inquiry
 */
public interface IMessageHeaderRepository<T extends IMessageHeader> extends IRepository<T> {
	/** 
	 * Delete a record by the identifier 
	 * @param id The identifier
	 */
	void deleteById(String id);
}
