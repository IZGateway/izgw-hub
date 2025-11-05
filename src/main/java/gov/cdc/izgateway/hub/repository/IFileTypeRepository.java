package gov.cdc.izgateway.hub.repository;

import gov.cdc.izgateway.model.IFileType;
import java.util.List;

/**
 * Repository interface for managing {@link IFileType} entities.
 */
public interface IFileTypeRepository extends IRepository<IFileType> {
    /**
     * Stores the given file type.
     * @param fileType the file type to store
     * @return the stored file type
     */
    IFileType store(IFileType fileType);

    /**
     * Deletes the given file type.
     * @param fileType the file type to delete
     */
    void delete(IFileType fileType);

    /**
     * Retrieves all file types.
     * @return a list of all file types
     */
    List<? extends IFileType> findAll();
}