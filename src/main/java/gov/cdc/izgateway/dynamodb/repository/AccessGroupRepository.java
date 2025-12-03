package gov.cdc.izgateway.dynamodb.repository;

import org.springframework.beans.factory.annotation.Autowired;
import gov.cdc.izgateway.dynamodb.DynamoDbRepository;
import gov.cdc.izgateway.dynamodb.model.AccessGroup;
import gov.cdc.izgateway.hub.repository.IAccessGroupRepository;
import gov.cdc.izgateway.model.IAccessGroup;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Repository for managing {@link AccessGroup} entities in DynamoDB.
 * Implements business logic for storing, deleting, and retrieving access groups.
 */
public class AccessGroupRepository extends DynamoDbRepository<AccessGroup> implements IAccessGroupRepository {
    /**
     * Constructs a new AccessGroupRepository with the given DynamoDB client and table name.
     * @param client the DynamoDB enhanced client
     * @param tableName the name of the DynamoDB table
     */
    public AccessGroupRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
        super(AccessGroup.class, client, tableName);
    }

    /**
     * Stores the given access group in DynamoDB.
     * @param group the access group to store
     * @return the stored access group
     */
    @Override
    public IAccessGroup store(IAccessGroup group) {
        if (group instanceof AccessGroup g) {
            return super.saveAndFlush(g);
        }
        return super.saveAndFlush(new AccessGroup(group));
    }

    /**
     * Deletes the given access group from DynamoDB.
     * @param group the access group to delete
     */
    @Override
    public void delete(IAccessGroup group) {
        if (group instanceof AccessGroup g) {
            delete(g.getPrimaryId());
        } else {
            AccessGroup g = new AccessGroup(group);
            delete(g.getPrimaryId());
        }
    }
}