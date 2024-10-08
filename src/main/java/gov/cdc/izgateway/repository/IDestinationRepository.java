package gov.cdc.izgateway.repository;

import java.util.List;

import gov.cdc.izgateway.model.IDestination;

/**
 * The necessary interface for implementing a DestinationRepository
 * @author Audacious Inquiry
 * @since(version="2.2.0")
 */
public interface IDestinationRepository {

	/**
	 * Get all destinations.
	 * @return	All destinations.
	 */
	List<? extends IDestination> findAll();
	/**
	 * Get all destinations for the specified environment.
	 * @return	All destinations enabled for the specified environment.
	 */
	List<? extends IDestination> findAllByDestTypeId(int destType);
	/**
	 * Save the destination data for a destination.
	 * @param dest	The destination.
	 */
	void saveAndFlush(IDestination dest);

}
