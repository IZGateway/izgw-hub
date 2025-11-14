package gov.cdc.izgateway.dynamodb.repository;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

import gov.cdc.izgateway.dynamodb.model.AccessGroup;
import gov.cdc.izgateway.hub.repository.IAccessGroupRepository;
import gov.cdc.izgateway.model.IAccessControl;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.service.IAccessControlService;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Repository for managing {@link AccessGroup} entities in DynamoDB.
 * Implements business logic for storing, deleting, and retrieving access groups.
 */
public class AccessGroupRepository extends DynamoDbRepository<AccessGroup> implements IAccessGroupRepository<AccessGroup> {
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
    public AccessGroup store(AccessGroup group) {
        return saveAndFlush(group);
    }

    /**
     * Deletes the given access group from DynamoDB.
     * @param group the access group to delete
     */
    @Override
    public void delete(AccessGroup group) {
        delete(group.getPrimaryId());
    }

	/**
	 * Migrate access controls to access groups.
	 * @param list	The list of access controls
	 * @param when 
	 * @param who 
	 * @param environments 
	 */
	public void migrateAccessControls(List<? extends IAccessControl> list, String who, Date when, int[] environments) {
		Map<String, AccessGroup> groupMap = new LinkedHashMap<>();
		for (IAccessControl ac : list) {
			if (IAccessControlService.GROUP_CATEGORY.equals(ac.getCategory())) {
				String groupName = ac.getName();
				for (int env: environments) {
					AccessGroup group = groupMap.computeIfAbsent(groupName, 
						k -> createGroupFromAccessControl(who, when, groupName, env));
					if (IAccessControl.isGroup(ac.getMember())) {
						group.getGroups().add(ac.getMember());
					} else if (!ac.getMember().contains("*.")) { // Skip wildcard users
						group.getUsers().add(ac.getMember());
					}
				}
			}
		}
		migrate(groupMap.values());
	}

	private AccessGroup createGroupFromAccessControl(String who, Date when, String groupName, int env) {
		AccessGroup g = new AccessGroup();
		g.setGroupName(groupName);
		g.setEnvironment(env);
		g.setCreatedBy(who);
		g.setCreatedOn(when);
		switch (groupName) {
		case "admin":
			g.setDescription("Administrators with full access");
			g.getRoles().add(Roles.ADMIN);
			break;
		case "ads":
			g.setDescription("Automated Data Submission user");
			g.getRoles().add(Roles.ADS);
			break;
		case "adspilot":
			g.setDescription("Automated Data Submission pilot user");
			g.getRoles().add(Roles.ADS);
			break;
		case "internal":
			g.setDescription("Internal application use");
			g.getRoles().add(Roles.INTERNAL);
			break;
		case "operations":
			g.setDescription("Operation and support staff");
			g.getRoles().add(Roles.OPERATIONS);
			g.getRoles().add(Roles.SOAP);
			g.getRoles().add(Roles.USERS);
			break;
		case "soap":
			g.setDescription("SOAP API user");
			g.getRoles().add(Roles.SOAP);
			g.getRoles().add(Roles.USERS);
			break;
		case "users":
			g.setDescription("REST API user");
			g.getRoles().add(Roles.USERS);
			break;
		default:
			break;
		}
		return g;
	}
	
	@Override
	public AccessGroup findByTypeAndName(int type, String name) {
		return super.find(String.format("%d#%s", type, name));
	}
}
