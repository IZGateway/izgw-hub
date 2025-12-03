package gov.cdc.izgateway.dynamodb.repository;

import org.springframework.beans.factory.annotation.Autowired;
import gov.cdc.izgateway.dynamodb.DynamoDbRepository;
import gov.cdc.izgateway.dynamodb.model.AllowedUser;
import gov.cdc.izgateway.hub.repository.IAllowedUserRepository;
import gov.cdc.izgateway.model.IAllowedUser;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Repository for managing {@link AllowedUser} entities in DynamoDB.
 * Implements business logic for storing, deleting, and retrieving allowed users.
 */
public class AllowedUserRepository extends DynamoDbRepository<AllowedUser> implements IAllowedUserRepository {
    /**
     * Constructs a new AllowedUserRepository with the given DynamoDB client and table name.
     * @param client the DynamoDB enhanced client
     * @param tableName the name of the DynamoDB table
     */
    public AllowedUserRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
        super(AllowedUser.class, client, tableName);
    }

    /**
     * Stores the given allowed user in DynamoDB.
     * @param user the allowed user to store
     * @return the stored allowed user
     */
    @Override
    public IAllowedUser store(IAllowedUser user) {
        if (user instanceof AllowedUser u) {
            return super.saveAndFlush(u);
        }
        return super.saveAndFlush(new AllowedUser(user));
    }

    /**
     * Deletes the given allowed user from DynamoDB.
     * @param user the allowed user to delete
     */
    @Override
    public void delete(IAllowedUser user) {
        if (user instanceof AllowedUser u) {
            delete(u.getPrimaryId());
        } else {
            AllowedUser u = new AllowedUser(user);
            delete(u.getPrimaryId());
        }
    }
}