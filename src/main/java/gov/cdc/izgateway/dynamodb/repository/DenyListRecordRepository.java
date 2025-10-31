package gov.cdc.izgateway.dynamodb.repository;

import org.springframework.beans.factory.annotation.Autowired;
import gov.cdc.izgateway.dynamodb.DynamoDbRepository;
import gov.cdc.izgateway.dynamodb.model.DenyListRecord;
import gov.cdc.izgateway.hub.repository.IDenyListRecordRepository;
import gov.cdc.izgateway.model.IDenyListRecord;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Repository for managing {@link DenyListRecord} entities in DynamoDB.
 * Implements business logic for storing, deleting, and retrieving deny list records.
 */
public class DenyListRecordRepository extends DynamoDbRepository<DenyListRecord> implements IDenyListRecordRepository {
    /**
     * Constructs a new DenyListRecordRepository with the given DynamoDB client and table name.
     * @param client the DynamoDB enhanced client
     * @param tableName the name of the DynamoDB table
     */
    public DenyListRecordRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
        super(DenyListRecord.class, client, tableName);
    }

    /**
     * Stores the given deny list record in DynamoDB.
     * @param record the deny list record to store
     * @return the stored deny list record
     */
    @Override
    public IDenyListRecord store(IDenyListRecord record) {
        if (record instanceof DenyListRecord r) {
            return super.saveAndFlush(r);
        }
        return super.saveAndFlush(new DenyListRecord(record));
    }

    /**
     * Deletes the given deny list record from DynamoDB.
     * @param record the deny list record to delete
     */
    @Override
    public void delete(IDenyListRecord record) {
        if (record instanceof DenyListRecord r) {
            delete(r.getPrimaryId());
        } else {
            DenyListRecord r = new DenyListRecord(record);
            delete(r.getPrimaryId());
        }
    }
}