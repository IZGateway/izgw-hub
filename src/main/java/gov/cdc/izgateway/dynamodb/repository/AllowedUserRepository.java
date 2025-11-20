package gov.cdc.izgateway.dynamodb.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import org.apache.commons.lang3.Strings;
import org.springframework.beans.factory.annotation.Autowired;

import gov.cdc.izgateway.dynamodb.model.AllowedUser;
import gov.cdc.izgateway.hub.repository.IAllowedUserRepository;
import gov.cdc.izgateway.model.IAccessControl;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import gov.cdc.izgateway.service.IAccessControlService;
import gov.cdc.izgateway.utils.SystemUtils;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Repository for managing {@link AllowedUser} entities in DynamoDB.
 * Implements business logic for storing, deleting, and retrieving allowed users.
 */
@Slf4j
public class AllowedUserRepository extends DynamoDbRepository<AllowedUser> implements IAllowedUserRepository<AllowedUser> {
    /**
     * Constructs a new AllowedUserRepository with the given DynamoDB client and table name.
     * @param client the DynamoDB enhanced client
     * @param tableName the name of the DynamoDB table
     */
    public AllowedUserRepository(@Autowired DynamoDbEnhancedClient client, String tableName) {
        super(AllowedUser.class, client, tableName);
    }

	/**
	 * Migrate access controls to allowed users.
	 * @param list	The list of access controls
	 * @param who Who created the original record in DynamoDB
	 * @param when When that happened
	 */
	public void migrateAccessControls(List<? extends IAccessControl> list, String who, Date when) {
		// Enable access to DEX endpoint for all users in groups starting with "ads"
		List<AllowedUser> userMap = migrateForAutomatedDataSubmission(list, who, when);
		migrate(userMap);
	}

	private List<AllowedUser> migrateForAutomatedDataSubmission(List<? extends IAccessControl> list, String who, Date when) {
		List<AllowedUser> userMap = new ArrayList<>();
		List<Integer> destTypes = Collections.emptyList();
		switch (SystemUtils.getDestType()) {
		case SystemUtils.DESTTYPE_PROD, SystemUtils.DESTTYPE_ONBOARD:
			destTypes = List.of(SystemUtils.DESTTYPE_PROD, SystemUtils.DESTTYPE_ONBOARD);
			break;
		case SystemUtils.DESTTYPE_STAGE:
			destTypes = List.of(SystemUtils.DESTTYPE_STAGE);
			break;
		case SystemUtils.DESTTYPE_DEV, SystemUtils.DESTTYPE_TEST:
			destTypes = List.of(SystemUtils.DESTTYPE_DEV, SystemUtils.DESTTYPE_TEST);
			break;
		default:
			log.warn("Unknown destination type: {}", SystemUtils.getDestType());
			break;
		}
		for (IAccessControl ac : list) {
			if (IAccessControlService.GROUP_CATEGORY.equals(ac.getCategory()) && Strings.CI.startsWith(ac.getName(), "ads") && !IAccessControl.isGroup(ac.getMember())) {
				String userName = ac.getMember();
				String[] dexEndpoints = { "dex", "dex-dev" };
				for (String dexEndpoint : dexEndpoints) {
					for (int destType : destTypes) {
						AllowedUser u = new AllowedUser();
						u.setEnvironment(destType);
						u.setPrincipal(userName);
						u.setEnabled(true);
						u.setValidatedOn(when);
						u.setDestinationId(dexEndpoint);
						u.setCreatedBy(who);
						u.setCreatedOn(when);
						userMap.add(u);
					}
				}
			}
		}
		return userMap;
	}

	@Override
	public AllowedUser store(AllowedUser user) {
		return super.saveAndFlush(user);
	}
}