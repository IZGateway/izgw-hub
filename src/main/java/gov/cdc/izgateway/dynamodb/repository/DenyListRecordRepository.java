package gov.cdc.izgateway.dynamodb.repository;

import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;

import gov.cdc.izgateway.dynamodb.model.DenyListRecord;
import gov.cdc.izgateway.hub.repository.IDenyListRecordRepository;
import gov.cdc.izgateway.model.IAccessControl;
import gov.cdc.izgateway.model.IDenyListRecord;
import gov.cdc.izgateway.repository.DynamoDbRepository;
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
	 * Migrate access controls to deny list records.
	 * @param list	The list of access controls
	 * @param who	The originator of the original access control record
	 * @param when	When that happened
	 */
	public void migrateAccessControls(List<? extends IAccessControl> list, String who, Date when) {
		for (IAccessControl ac : list) {
			if (!ac.getCategory().equals("group") || !ac.getName().equals("blacklist")) {
				continue;
			}
			DenyListRecord dlr = new DenyListRecord(ac, who, when);
			super.saveAndFlush(dlr);
		}
		
	}

	@Override
	public void delete(IDenyListRecord record) {
		if (record instanceof DenyListRecord r) {
			delete(r);
		} else {
			delete(new DenyListRecord(record));
		}
		
	}
}