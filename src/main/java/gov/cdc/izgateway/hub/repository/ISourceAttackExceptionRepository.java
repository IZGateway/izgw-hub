package gov.cdc.izgateway.hub.repository;

import gov.cdc.izgateway.dynamodb.model.SourceAttackExceptionRecord;
import gov.cdc.izgateway.repository.IRepository;

import java.util.List;

/**
 * Repository interface for managing {@link SourceAttackExceptionRecord} entities (IGDD-2805).
 * <p>
 * Bound directly to the concrete izgw-hub entity rather than an izgw-core marker interface — see
 * {@link SourceAttackExceptionRecord}'s Javadoc for why.
 * </p>
 *
 * @param <T> the type of source-attack exception record
 */
public interface ISourceAttackExceptionRepository<T extends SourceAttackExceptionRecord> extends IRepository<T> {
    /**
     * Stores the given source-attack exception record.
     * @param exception the exception record to store
     * @return the stored exception record
     */
    T store(T exception);

    /**
     * Deletes the given source-attack exception record.
     * @param exception the exception record to delete
     */
    void delete(T exception);

    /**
     * Retrieves all source-attack exception records.
     * @return a list of all source-attack exception records
     */
    List<T> findAll();
}
