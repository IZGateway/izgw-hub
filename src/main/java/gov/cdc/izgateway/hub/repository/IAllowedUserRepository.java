package gov.cdc.izgateway.hub.repository;

import gov.cdc.izgateway.model.IAllowedUser;
import gov.cdc.izgateway.repository.IRepository;

import java.util.List;

/**
 * Repository interface for managing {@link IAllowedUser} entities.
 */
public interface IAllowedUserRepository extends IRepository<IAllowedUser> {
    /**
     * Stores the given allowed user.
     * @param user the allowed user to store
     * @return the stored allowed user
     */
    IAllowedUser store(IAllowedUser user);

    /**
     * Deletes the given allowed user.
     * @param user the allowed user to delete
     */
    void delete(IAllowedUser user);

    /**
     * Retrieves all allowed users.
     * @return a list of all allowed users
     */
    List<? extends IAllowedUser> findAll();
}