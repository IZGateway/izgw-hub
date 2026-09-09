package gov.cdc.izgateway.dynamodb.repository;

import org.springframework.beans.factory.annotation.Autowired;

import gov.cdc.izgateway.dynamodb.model.SourceAttackExceptionRecord;
import gov.cdc.izgateway.hub.repository.ISourceAttackExceptionRepository;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Repository for managing {@link SourceAttackExceptionRecord} entities in DynamoDB (IGDD-2805).
 */
public class SourceAttackExceptionRepository extends DynamoDbRepository<SourceAttackExceptionRecord>
        implements ISourceAttackExceptionRepository<SourceAttackExceptionRecord> {
    /**
     * Constructs a new SourceAttackExceptionRepository with the given DynamoDB client and table name.
     * @param client the DynamoDB enhanced client
     * @param tableName the name of the DynamoDB table
     */
    public SourceAttackExceptionRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
        super(SourceAttackExceptionRecord.class, client, tableName);
    }

    /**
     * Stores the given source-attack exception record in DynamoDB.
     * @param exception the source-attack exception record to store
     * @return the stored source-attack exception record
     */
    @Override
    public SourceAttackExceptionRecord store(SourceAttackExceptionRecord exception) {
        return super.saveAndFlush(exception);
    }
}
