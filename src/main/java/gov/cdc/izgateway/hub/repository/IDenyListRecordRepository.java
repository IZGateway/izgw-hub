package gov.cdc.izgateway.hub.repository;

import gov.cdc.izgateway.model.IDenyListRecord;
import gov.cdc.izgateway.repository.IRepository;

import java.util.List;

/**
 * Repository interface for managing {@link IDenyListRecord} entities.
 * 
 * @param <T> the type of deny list record
 */
public interface IDenyListRecordRepository<T extends IDenyListRecord> extends IRepository<T> {
    /**
     * Stores the given deny list record.
     * @param denyListRecord the deny list record to store
     * @return the stored deny list record
     */
    T store(T denyListRecord);

    /**
     * Deletes the given deny list record.
     * @param denyListRecord the deny list record to delete
     */
    void delete(T denyListRecord);

    /**
     * Retrieves all deny list records.
     * @return a list of all deny list records
     */
    List<T> findAll();
}