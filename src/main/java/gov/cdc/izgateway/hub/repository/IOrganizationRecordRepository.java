package gov.cdc.izgateway.hub.repository;

import gov.cdc.izgateway.model.IOrganizationRecord;
import java.util.List;

/**
 * Repository interface for managing {@link IOrganizationRecord} entities.
 */
public interface IOrganizationRecordRepository {
    /**
     * Stores the given organization record.
     * @param organizationRecord the organization record to store
     * @return the stored organization record
     */
    IOrganizationRecord store(IOrganizationRecord organizationRecord);

    /**
     * Deletes the given organization record.
     * @param organizationRecord the organization record to delete
     */
    void delete(IOrganizationRecord organizationRecord);

    /**
     * Retrieves all organization records.
     * @return a list of all organization records
     */
    List<? extends IOrganizationRecord> findAll();
}