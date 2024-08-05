package gov.cdc.izgateway.db.repository;


import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import gov.cdc.izgateway.db.model.AccessControl;
import gov.cdc.izgateway.db.model.AccessControlId;
import gov.cdc.izgateway.utils.SystemUtils;

/**
 * Repository for access control records
 * 
 * Enables retrieval of all records for the specified environment
 * 
 * @author Audacious Inquiry
 *
 */
@Repository
public interface AccessControlRepository extends JpaRepository<AccessControl, AccessControlId> {
	/**
	 * Asks SpringJPA to construct the query by dest_type.
	 * 
	 * NOTE: This method should not be called directly.  It exists solely to support 
	 * implementation of "findAll()" which really means, find all that you are allowed
	 * to see.
	 * 
	 * @param destType	The destination type to use
	 * 
	 * @return	The list of access control records with the given destination type.
	 */
	@Query(value = "SELECT category, name, member, (allow & 1) as allow FROM accesscontrol WHERE (allow & (1 << ?1)) != 0", nativeQuery = true)
	List<AccessControl> findAllByDestTypeId(int destType);

	/**
	 * Override the JPA default implementation with findAllByDestinationIdTypeId
	 * 
	 * @return The list of access control records for this instances environment
	 */
	@Override
	default List<AccessControl> findAll() {
		return findAllByDestTypeId(SystemUtils.getDestType());
	}
}