package gov.cdc.izgateway.hub.repository;

import gov.cdc.izgateway.model.IAllowedUser;
import gov.cdc.izgateway.repository.IRepository;

import java.util.List;

/**
 * Repository interface for managing {@link IAllowedUser} entities.
 * @param <T> The type of AllowedUser 
 */
public interface IAllowedUserRepository<T extends IAllowedUser> extends IRepository<T> {
    /**
     * Stores the given allowed user.
     * @param user the allowed user to store
     * @return the stored allowed user
     */
    T store(T user);

    /**
     * Deletes the given allowed user.
     * @param user the allowed user to delete
     */
    void delete(T user);

    /**
     * Retrieves all allowed users.
     * @return a list of all allowed users
     */
    List<T> findAll();
}