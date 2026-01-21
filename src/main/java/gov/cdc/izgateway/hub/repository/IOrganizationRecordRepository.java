package gov.cdc.izgateway.hub.repository;

import gov.cdc.izgateway.model.IOrganizationRecord;
import gov.cdc.izgateway.repository.IRepository;

import java.util.List;

/**
 * Repository interface for managing {@link IOrganizationRecord} entities.
 * @param <T> The type of organization record
 */
public interface IOrganizationRecordRepository<T extends IOrganizationRecord> extends IRepository<T> {
    /**
     * Stores the given organization record.
     * @param organizationRecord the organization record to store
     * @return the stored organization record
     */
    T store(T organizationRecord);

    /**
     * Deletes the given organization record.
     * @param organizationRecord the organization record to delete
     */
    void delete(T organizationRecord);

    /**
     * Retrieves all organization records.
     * @return a list of all organization records
     */
    List<T> findAll();

	/**
	 * Find an organization by its name.
	 * @param organization	The organization name
	 * @return	The organization record or null if not found
	 */
	T find(String organization);
}