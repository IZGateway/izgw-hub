package gov.cdc.izgateway.db.repository;



import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import gov.cdc.izgateway.db.model.Destination;
import gov.cdc.izgateway.hub.repository.IDestinationRepository;
import gov.cdc.izgateway.model.AbstractDestination.DestinationId;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.utils.SystemUtils;

/**
 *	Implements the destination repository using Spring-JPA 
 *	The identifier of destination is a two part structure with the 
 *	destination id, and the destination type.  Destination type indicates
 *	the environment for the destination.  The database may keep track of
 *	multiple environments.  Each hub is only allowed to access records
 *	from its own environment.  This is VERY simple partitioning.
 */
@Repository
public interface DestinationRepository extends JpaRepository<Destination, DestinationId>, IDestinationRepository {
	/**
	 * Asks SpringJPA to construct the query by dest_type.
	 * 
	 * NOTE: This method should not be called directly.  It exists solely to support 
	 * implementation of "findAll()" which really means, find all that you are allowed
	 * to see.
	 * 
	 * @param destType	The destination type to use
	 * 
	 * @return	The list of destinations with the given destination type.
	 */
	@Query(value = "SELECT * FROM destinations WHERE dest_type = :destType ", nativeQuery = true)
	List<Destination> findAllByDestTypeId(@Param("destType") int destType);

	/**
	 * Override the JPA default implementation with findAllByDestIdTypeId
	 * 
	 * @return The list of destinations for this instances environment
	 */
	@Override
	default List<Destination> findAll() {
		return findAllByDestTypeId(SystemUtils.getDestType());
	}

	@Override
	default IDestination store(IDestination entity) {
		// Check for inadvertent write to wrong partition of DB
		if (entity.getDestTypeId() != SystemUtils.getDestType()) {
			String msg = String.format("Attempt to save %s from %s into %s",
				entity.getDestId(), entity.getDestType(), SystemUtils.getDestTypeAsString());
			// This should not crash the service, but will at least log it.
			throw new SecurityException("Partition Access Error: " + msg);
		}
		if (entity instanceof Destination d) {
			return saveAndFlush(d);
		}
		return saveAndFlush(new Destination(entity));
	}
	
}