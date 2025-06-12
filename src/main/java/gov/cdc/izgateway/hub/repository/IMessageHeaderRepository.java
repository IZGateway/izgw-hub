package gov.cdc.izgateway.hub.repository;

import gov.cdc.izgateway.model.IMessageHeader;

public interface IMessageHeaderRepository extends IRepository<IMessageHeader> {
	/* Delete a record by the identifier */
	void deleteById(String id);
}
