package gov.cdc.izgateway.hub.repository;

import gov.cdc.izgateway.model.IFileType;
import gov.cdc.izgateway.repository.IRepository;

import java.util.List;

/**
 * Repository interface for managing {@link IFileType} entities.
 * @param <T> The type of FileType
 */
public interface IFileTypeRepository<T extends IFileType> extends IRepository<T> {
    /**
     * Stores the given file type.
     * @param fileType the file type to store
     * @return the stored file type
     */
    T store(T fileType);

    /**
     * Deletes the given file type.
     * @param fileType the file type to delete
     */
    void delete(T fileType);

    /**
     * Retrieves all file types.
     * @return a list of all file types
     */
    List<T> findAll();
}