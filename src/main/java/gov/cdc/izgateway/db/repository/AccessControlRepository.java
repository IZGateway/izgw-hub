package gov.cdc.izgateway.db.repository;


import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
	

	/**
	 * Add a new access control entry.
	 * This is ONLY called to add a blacklisted user 
	 * @param category	The category
	 * @param name		The name
	 * @param member	The member
	 * @param allow		The flag settings.
	 */
    @Modifying
    @Query(value = "insert into accesscontrol(category, name, member, allow) values(?1, ?2, ?3, ?4)", nativeQuery = true)
    void insertAccessControl(String category, String name, String member, int allow);
    
    /**
     * Update a user.
     * Presently, this is only used by IZ Gateway to create a new blacklist entry, which should always
     * be an insert operation.  It's technically possible that two systems could be trying to create
     * the same entry at the same time, perhaps even in two different environments. Because it's a
     * blacklist entry, it applies to ALL environments.  A user blacklisted by one IZGW environment
     * shouldn't be allowed into any other environments.
     * 
     * This will need rework to support adding users to groups generally, and so will the 
     * delete capabilities.
     * 
     * @param accessControl the entry for the user to blacklist
     * @return The stored access control entry
     */
    @SuppressWarnings("unchecked")
	@Transactional
	@Override
	default AccessControl saveAndFlush(AccessControl accessControl) {
		insertAccessControl(
			accessControl.getCategory(),
			accessControl.getName(),
			accessControl.getMember(),
			accessControl.isAllowed() ? 0x7F : 0x7E
		);
		flush();
		return accessControl;
	}
}