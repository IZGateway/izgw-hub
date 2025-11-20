package gov.cdc.izgateway.hub.repository;

import gov.cdc.izgateway.model.IAccessGroup;
import gov.cdc.izgateway.repository.IRepository;

import java.util.List;

/**
 * Repository interface for managing {@link IAccessGroup} entities.
 * @param <T> the type of access group
 */
public interface IAccessGroupRepository<T extends IAccessGroup> extends IRepository<T> {
    /**
     * Stores the given access group.
     * @param group the access group to store
     * @return the stored access group
     */
    T store(T group);

    /**
     * Deletes the given access group.
     * @param group the access group to delete
     */
    void delete(T group);

    /**
     * Retrieves all access groups.
     * @return a list of all access groups
     */
    List<T> findAll();

    /**
     * Get the given access group by name.
     * @param destinationType The destination type of the access group
     * @param name the name of the access group
     * @return	 the access group with the given name, or null if not found.
     */
    T findByTypeAndName(int destinationType, String name);
}