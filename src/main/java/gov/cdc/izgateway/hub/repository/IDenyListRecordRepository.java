package gov.cdc.izgateway.hub.repository;

import gov.cdc.izgateway.model.IDenyListRecord;
import gov.cdc.izgateway.repository.IRepository;

import java.util.List;

/**
 * Repository interface for managing {@link IDenyListRecord} entities.
 */
public interface IDenyListRecordRepository extends IRepository<IDenyListRecord> {
    /**
     * Stores the given deny list record.
     * @param record the deny list record to store
     * @return the stored deny list record
     */
    IDenyListRecord store(IDenyListRecord record);

    /**
     * Deletes the given deny list record.
     * @param record the deny list record to delete
     */
    void delete(IDenyListRecord record);

    /**
     * Retrieves all deny list records.
     * @return a list of all deny list records
     */
    List<? extends IDenyListRecord> findAll();
}