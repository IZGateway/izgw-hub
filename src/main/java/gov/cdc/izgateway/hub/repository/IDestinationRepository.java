package gov.cdc.izgateway.hub.repository;

import java.util.List;

import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.repository.IRepository;

/**
 * The necessary interface for implementing a DestinationRepository
 * @author Audacious Inquiry
 * @since(version="2.2.0")
 */
public interface IDestinationRepository extends IRepository<IDestination> {

	/**
	 * Get all destinations for the specified environment.
	 * @param destType The type of destination to find
	 * @return	All destinations enabled for the specified environment.
	 */
	List<? extends IDestination> findAllByDestTypeId(int destType);

	/**
	 * @return a new Destination for a repository
	 */
	IDestination newDestination();

}
