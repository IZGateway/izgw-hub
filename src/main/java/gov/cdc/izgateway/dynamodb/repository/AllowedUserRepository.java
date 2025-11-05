package gov.cdc.izgateway.dynamodb.repository;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.Strings;
import org.springframework.beans.factory.annotation.Autowired;

import gov.cdc.izgateway.dynamodb.model.AllowedUser;
import gov.cdc.izgateway.hub.repository.IAllowedUserRepository;
import gov.cdc.izgateway.model.IAccessControl;
import gov.cdc.izgateway.model.IAllowedUser;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import gov.cdc.izgateway.utils.SystemUtils;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Repository for managing {@link AllowedUser} entities in DynamoDB.
 * Implements business logic for storing, deleting, and retrieving allowed users.
 */
@Slf4j
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

	/**
	 * Migrate access controls to allowed users.
	 * @param list	The list of access controls
	 * @param who Who created the original record in DynamoDB
	 * @param when When that happened
	 */
	public void migrateAccessControls(List<? extends IAccessControl> list, String who, Date when) {
		Map<String, AllowedUser> userMap = new LinkedHashMap<>();
		// Enable access to DEX endpoint for all users in groups starting with "ads"
		migrateForAutomatedDataSubmission(list, who, when, userMap);
		migrate(userMap.values());
	}

	private void migrateForAutomatedDataSubmission(List<? extends IAccessControl> list, String who, Date when,
			Map<String, AllowedUser> userMap) {
		for (IAccessControl ac : list) {
			if ("group".equals(ac.getCategory()) && Strings.CI.startsWith(ac.getName(), "ads") && !IAccessControl.isGroup(ac.getMember())) {
				String userName = ac.getMember();
				int[] destTypes = { SystemUtils.DESTTYPE_PROD, SystemUtils.DESTTYPE_ONBOARD, SystemUtils.DESTTYPE_STAGE, SystemUtils.DESTTYPE_DEV, SystemUtils.DESTTYPE_TEST };
				String[] dexEndpoints = { "dex", "dex-dev" };
				for (String dexEndpoint : dexEndpoints) {
					for (int destType : destTypes) {
						switch (SystemUtils.getDestType()) {
							case SystemUtils.DESTTYPE_PROD:
							case SystemUtils.DESTTYPE_ONBOARD:
								if (destType == SystemUtils.DESTTYPE_DEV || destType == SystemUtils.DESTTYPE_TEST) {
									continue;
								}
								break;
							case SystemUtils.DESTTYPE_STAGE:
								if (destType != SystemUtils.DESTTYPE_STAGE) {
									continue;
								}
								break;
							case SystemUtils.DESTTYPE_DEV:
							case SystemUtils.DESTTYPE_TEST:
								if (destType == SystemUtils.DESTTYPE_PROD || destType == SystemUtils.DESTTYPE_ONBOARD) {
									continue;
								}
								break;
							default:
								continue;
						}
						if (SystemUtils.getDestType() == destType) {
							AllowedUser u = new AllowedUser();
							u.setEnvironment(destType);
							u.setPrincipal(userName);
							u.setEnabled(true);
							u.setValidatedOn(when);
							u.setDestinationId(dexEndpoint);
							u.setCreatedBy(who);
							u.setCreatedOn(when);
							userMap.put(userName, u);
						}
					}
				}
			}
		}
	}
}