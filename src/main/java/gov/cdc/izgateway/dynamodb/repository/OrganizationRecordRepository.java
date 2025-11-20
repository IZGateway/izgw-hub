package gov.cdc.izgateway.dynamodb.repository;

import org.springframework.beans.factory.annotation.Autowired;

import gov.cdc.izgateway.dynamodb.model.OrganizationRecord;
import gov.cdc.izgateway.hub.repository.IOrganizationRecordRepository;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Repository for managing {@link OrganizationRecord} entities in DynamoDB.
 * Implements business logic for storing, deleting, and retrieving organization records.
 */
public class OrganizationRecordRepository extends DynamoDbRepository<OrganizationRecord> implements IOrganizationRecordRepository<OrganizationRecord> {
    /**
     * Constructs a new OrganizationRecordRepository with the given DynamoDB client and table name.
     * @param client the DynamoDB enhanced client
     * @param tableName the name of the DynamoDB table
     */
    public OrganizationRecordRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
        super(OrganizationRecord.class, client, tableName);
    }

    /**
     * Stores the given organization record in DynamoDB.
     * @param org the organization record to store
     * @return the stored organization record
     */
    @Override
    public OrganizationRecord store(OrganizationRecord org) {
        return super.saveAndFlush(org);
    }
}