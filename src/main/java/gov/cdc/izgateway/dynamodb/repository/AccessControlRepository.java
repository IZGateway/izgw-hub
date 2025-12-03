package gov.cdc.izgateway.dynamodb.repository;

import org.springframework.beans.factory.annotation.Autowired;
import gov.cdc.izgateway.dynamodb.DynamoDbRepository;
import gov.cdc.izgateway.dynamodb.model.AccessControl;
import gov.cdc.izgateway.hub.repository.IAccessControlRepository;
import gov.cdc.izgateway.model.IAccessControl;
import gov.cdc.izgateway.utils.SystemUtils;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Class representing the DynamoDb repository for AccessControls.
 * 
 * @author Audacious Inquiry
 */
public class AccessControlRepository extends DynamoDbRepository<AccessControl> implements IAccessControlRepository {
	/**
	 * Construct a new AccessControlRepository from the DynamoDb enhanced client.
	 * @param client The client
	 * @param tableName The table name
	 */
	public AccessControlRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
		super(AccessControl.class, client, tableName);
	}
	
	@Override
	public IAccessControl store(IAccessControl h) {
		if (h instanceof AccessControl h2) {
			return super.saveAndFlush(h2);
		}
		return super.saveAndFlush(new AccessControl(h));
	}

	@Override
	public void delete(IAccessControl control) 
	{
		if (control instanceof AccessControl c) {
			delete(c.getPrimaryId());
		} else {
			AccessControl c = new AccessControl(control);
			delete(c.getPrimaryId());
		}
	}

	@Override
	public IAccessControl addUserToGroup(String user, String group) {
		AccessControl c = new AccessControl("group", group, user, SystemUtils.getDestType());
		return super.saveAndFlush(c);
	}

	@Override
	public IAccessControl removeUserFromGroup(String user, String group) {
		AccessControl c = new AccessControl("group", group, user, SystemUtils.getDestType());
		super.delete(c.getPrimaryId());
		return c;
	}
}
