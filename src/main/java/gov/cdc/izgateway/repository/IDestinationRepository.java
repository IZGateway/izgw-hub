package gov.cdc.izgateway.repository;

import java.util.List;

import gov.cdc.izgateway.model.IDestination;

/**
 * The necessary interface for implementing a DestinationRepository
 * @author Audacious Inquiry
 * @since(version="2.2.0")
 */
public interface IDestinationRepository extends IRepository<IDestination> {

	/**
	 * Get all destinations for the specified environment.
	 * @return	All destinations enabled for the specified environment.
	 */
	List<? extends IDestination> findAllByDestTypeId(int destType);

}