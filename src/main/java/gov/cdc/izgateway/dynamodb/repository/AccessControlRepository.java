package gov.cdc.izgateway.dynamodb.repository;

import org.springframework.beans.factory.annotation.Autowired;

import gov.cdc.izgateway.dynamodb.model.AccessControl;
import gov.cdc.izgateway.hub.repository.IAccessControlRepository;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import gov.cdc.izgateway.service.IAccessControlService;
import gov.cdc.izgateway.utils.SystemUtils;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Class representing the DynamoDb repository for AccessControls.
 * 
 * @author Audacious Inquiry
 */
public class AccessControlRepository extends DynamoDbRepository<AccessControl> implements IAccessControlRepository<AccessControl> {
	/**
	 * Construct a new AccessControlRepository from the DynamoDb enhanced client.
	 * @param client The client
	 * @param tableName The table name
	 */
	public AccessControlRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
		super(AccessControl.class, client, tableName);
	}
	
	@Override
	public AccessControl store(AccessControl h) {
		return super.saveAndFlush(h);
	}

	@Override
	public AccessControl addUserToGroup(String user, String group) {
		AccessControl c = new AccessControl(IAccessControlService.GROUP_CATEGORY, group, user, SystemUtils.getDestType());
		return super.saveAndFlush(c);
	}

	@Override
	public AccessControl removeUserFromGroup(String user, String group) {
		AccessControl c = new AccessControl(IAccessControlService.GROUP_CATEGORY, group, user, SystemUtils.getDestType());
		super.delete(c.getPrimaryId());
		return c;
	}
}
