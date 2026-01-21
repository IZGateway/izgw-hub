package gov.cdc.izgateway.dynamodb.repository;

import org.springframework.beans.factory.annotation.Autowired;

import gov.cdc.izgateway.dynamodb.model.FileType;
import gov.cdc.izgateway.hub.repository.IFileTypeRepository;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Repository for managing {@link FileType} entities in DynamoDB.
 * Implements business logic for storing, deleting, and retrieving file types.
 */
public class FileTypeRepository extends DynamoDbRepository<FileType> implements IFileTypeRepository<FileType> {
    /**
     * Constructs a new FileTypeRepository with the given DynamoDB client and table name.
     * @param client the DynamoDB enhanced client
     * @param tableName the name of the DynamoDB table
     */
    public FileTypeRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
        super(FileType.class, client, tableName);
    }

    /**
     * Stores the given file type in DynamoDB.
     * @param fileType the file type to store
     * @return the stored file type
     */
    @Override
    public FileType store(FileType fileType) {
        return saveAndFlush(fileType);
    }
}