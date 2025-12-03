package gov.cdc.izgateway.hub.repository;

import gov.cdc.izgateway.model.IAccessGroup;
import java.util.List;

/**
 * Repository interface for managing {@link IAccessGroup} entities.
 */
public interface IAccessGroupRepository {
    /**
     * Stores the given access group.
     * @param group the access group to store
     * @return the stored access group
     */
    IAccessGroup store(IAccessGroup group);

    /**
     * Deletes the given access group.
     * @param group the access group to delete
     */
    void delete(IAccessGroup group);

    /**
     * Retrieves all access groups.
     * @return a list of all access groups
     */
    List<? extends IAccessGroup> findAll();
}