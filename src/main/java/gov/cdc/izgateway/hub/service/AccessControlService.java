package gov.cdc.izgateway.hub.service;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMethod;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.enums.CSVReaderNullFieldIndicator;
import com.opencsv.exceptions.CsvValidationException;

import gov.cdc.izgateway.dynamodb.model.AccessGroup;
import gov.cdc.izgateway.dynamodb.model.AllowedUser;
import gov.cdc.izgateway.dynamodb.model.DenyListRecord;
import gov.cdc.izgateway.dynamodb.model.Event;
import gov.cdc.izgateway.dynamodb.model.FileType;
import gov.cdc.izgateway.dynamodb.model.OrganizationRecord;
import gov.cdc.izgateway.dynamodb.repository.AccessGroupRepository;
import gov.cdc.izgateway.dynamodb.repository.AllowedUserRepository;
import gov.cdc.izgateway.dynamodb.repository.DenyListRecordRepository;
import gov.cdc.izgateway.dynamodb.repository.EventRepository;
import gov.cdc.izgateway.dynamodb.repository.FileTypeRepository;
import gov.cdc.izgateway.dynamodb.repository.OrganizationRecordRepository;
import gov.cdc.izgateway.hub.repository.IAccessControlRepository;
import gov.cdc.izgateway.hub.repository.IAccessGroupRepository;
import gov.cdc.izgateway.hub.repository.IAllowedUserRepository;
import gov.cdc.izgateway.hub.repository.IDenyListRecordRepository;
import gov.cdc.izgateway.hub.repository.IFileTypeRepository;
import gov.cdc.izgateway.hub.repository.IOrganizationRecordRepository;
import gov.cdc.izgateway.hub.repository.IRepository;
import gov.cdc.izgateway.hub.repository.RepositoryFactory;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.model.IAccessControl;
import gov.cdc.izgateway.model.IAccessGroup;
import gov.cdc.izgateway.model.IFileType;
import gov.cdc.izgateway.repository.DynamoDbRepository;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.service.IAccessControlRegistry;
import gov.cdc.izgateway.service.IAccessControlService;
import gov.cdc.izgateway.utils.SystemUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Implements the IAccessControlService for IZ Gateway.
 * 
 * 
 * @author Audacious Inquiry
 */
@Slf4j
@Service
public class AccessControlService implements InitializingBean, IAccessControlService {

	private static final int MAX_CACHE_SIZE = 1000;
	private static final int REDUCE_QTY = 200;

	private final IAccessControlRepository accessControlRepository;
	private final IAccessControlRegistry registry;
    private final IAccessGroupRepository accessGroupRepository;
    private final IAllowedUserRepository allowedUserRepository;
    private final IDenyListRecordRepository denyListRecordRepository;
    private final IFileTypeRepository fileTypeRepository;
    private final IOrganizationRecordRepository organizationRecordRepository;
	private final EventRepository eventRepository;
	private final List<IRepository<?>> repositoriesToMigrate;
	private Map<String, Map<String, Boolean>> allowedUsersByGroup = Collections.emptyMap();
	private Map<String, TreeSet<String>> usersInRoles = Collections.emptyMap();
	private Map<String, Map<String, Boolean>> allowedRoutesByEvent = Collections.emptyMap();
	private boolean migrated = false;
	/**
	 * A cache of positive access control decisions. It needs to be concurrent
	 * because it can be modified by multiple threads.
	 */
	private Map<String, Set<String>> cachedControlDecisions = new ConcurrentHashMap<>();

	private int refreshPeriod = 300;

	@Value("${server.hostname:dev.izgateway.org}")
	private String serverName;
	@Value("${security.enable-blacklist:true}")
	private boolean blacklistEnabled;
	@Value("${hub.migration-data:access-controls.csv}")
	private String migrationData;
	
    /**
     * Create a new AccessControlService
     * @param factory The repository factory to use
     * @param registry	The registry for managing access control to methods
     */
    @Autowired
    public AccessControlService(RepositoryFactory factory, IAccessControlRegistry registry) {
        this.accessControlRepository = factory.accessControlRepository();
        this.registry = registry;
        this.accessGroupRepository = factory.accessGroupRepository();
        this.allowedUserRepository = factory.allowedUserRepository();
        this.denyListRecordRepository = factory.denyListRecordRepository();
        this.fileTypeRepository = factory.fileTypeRepository();
        this.organizationRecordRepository = factory.organizationRecordRepository();
        this.eventRepository = factory.eventRepository();
        factory.certificateStatusRepository();
        this.repositoriesToMigrate = Arrays.asList(accessGroupRepository, allowedUserRepository, denyListRecordRepository, fileTypeRepository, organizationRecordRepository);
    }
    
	@Override
	public String getServerName() {
		return serverName;
	}

	
	/**
     * Configure service to update itself periodically after initialization.
     */
    public void afterPropertiesSet() {
    	Boolean[] furtherMigrationNeeded = { false };
    	try {
    		DynamoDbRepository.setServerName(serverName);
	    	repositoriesToMigrate.stream().filter(r -> r.findAll().isEmpty()).forEach(r -> {
				log.info("Migrating data to {}", r.getClass().getSimpleName());
				furtherMigrationNeeded[0] |= migrateToNewAccessControlModel(r);
			});
    		migrateFromCSV();
	    	migrated = true;
    	} catch (ServiceConfigurationError e) {
    		log.error(Markers2.append(e), "Error during Access Control migration: {}", e.getMessage());
    		migrated = false;  // Use old model access control data if migration failed.
    	}
        log.debug("Refresh Scheduled for AccessControl");
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::refresh, 0, refreshPeriod, TimeUnit.SECONDS);
    }

    /**
     * Migrate existing access control records to the new model.
     * 
     * @param r The repository to migrate to
     * @throws ServiceConfigurationError if there is an error during migration
     */
	private boolean migrateToNewAccessControlModel(IRepository<?> r) {
		// To force a re-migration, delete "Migration" events for the specified repository type in DynamoDb 
		Event migrationEvent = eventRepository.create(new Event(Event.MIGRATION, r.getClass().getSimpleName())); 
		if (migrationEvent == null) {
			log.info("Migration already performed for {}", r.getClass().getSimpleName());
			return false;
		}
		
		List<Event> l = eventRepository.findByNameAndTarget("Created", "Database");
		Event creationEvent = l.isEmpty() ? migrationEvent : l.get(0);
		String reportedBy = serverName + "@" + creationEvent.getReportedBy();
		List<String> adsUsers = List.of("ads", "adspilot");
		boolean success = false;
		try {
			List<? extends IAccessControl> recordsToMigrate = accessControlRepository.findAll();
			if (r instanceof AccessGroupRepository agr) {
				agr.migrateAccessControls(recordsToMigrate.stream()
					.filter(ac -> "group".equals(ac.getCategory()))		// It's a group
					.filter(ac -> !"blacklist".equals(ac.getName()) &&  // but not the blacklist
								  !adsUsers.contains(ac.getName()))		// Or an ADS User group
					.toList(),
					reportedBy, 
					migrationEvent.getStarted(),
					getEnvironmentsToPopulate()
				);
				return success = true;
			} 
			if (r instanceof AllowedUserRepository aur) {
				// Only ADS Users use access controls today
				aur.migrateAccessControls(recordsToMigrate.stream()
						.filter(ac -> "group".equals(ac.getCategory()))		// It's a group
						.filter(ac -> adsUsers.contains(ac.getName()))		// It's an ADS User group
						.toList(), 
						reportedBy, 
						migrationEvent.getStarted()
				);
				return success = true;
			} 
			if (r instanceof DenyListRecordRepository dlr) {
				dlr.migrateAccessControls(recordsToMigrate.stream()
					.filter(ac -> "group".equals(ac.getCategory()))		// It's a group
					.filter(ac -> "blacklist".equals(ac.getName()))		// it's the denylist
					.toList(), 
					reportedBy, 
					migrationEvent.getStarted()
				);
				return success = true;
			} 
			if (r instanceof FileTypeRepository ftr) {
				List<? extends IFileType> fileTypes = recordsToMigrate.stream()
					.filter(ac -> "eventToRoute".equals(ac.getCategory()))		// It's a group
					.map(ac -> new FileType(ac, migrationEvent.getReportedBy(), migrationEvent.getCompleted())).toList();
				fileTypes.forEach(ft -> { ft.setCreatedBy(migrationEvent.getReportedBy()); ft.setCreatedOn(migrationEvent.getStarted()); });
				ftr.migrate(fileTypes);
				return success = true;
			} 
			if (r instanceof OrganizationRecordRepository) {
				// No migration needed for Organization Records
				return success = true;
			} 
			log.error("Unrecognized repository type for migration: {}", r.getClass().getSimpleName());
			throw new ServiceConfigurationError("Unrecognized repository type for migration: " + r.getClass().getSimpleName());
		} finally {
			migrationEvent.setCompleted(new Date());
			if (success) {
				eventRepository.update(migrationEvent);
			} else {
				eventRepository.delete(migrationEvent);
			}
		}
	}

	@Override
	public void refresh() {
        Map<String, Map<String, Boolean>> newAllowedUsersByGroup = new TreeMap<>();
        Map<String, Map<String, Boolean>> newAllowedRoutesByEvent = new TreeMap<>();
        cachedControlDecisions.clear();
        
        List<IAccessControl> controls = new ArrayList<>(accessControlRepository.findAll());
        for (IAccessControl control: controls) {
        	Map<String, Map<String, Boolean>> mapToUpdate = null;
        	switch (control.getCategory()) {
        	case GROUP_CATEGORY:
        		mapToUpdate = newAllowedUsersByGroup;
        		break;
        	case ROUTE_CATEGORY:
        		mapToUpdate = newAllowedRoutesByEvent;
        		break;
        	default:
        		log.error("Unrecognized category {} in AccessControls", control.getCategory());
        		continue;
        	}
        	Map<String, Boolean> group =
        		mapToUpdate.computeIfAbsent(
        			control.getName(),
        			k -> new LinkedHashMap<>()
        		);
			group.put(control.getMember(), control.isAllowed());
		}
        // Add the server itself to the internal group.
        newAllowedUsersByGroup.computeIfAbsent(Roles.INTERNAL, k -> new LinkedHashMap<>()).put(serverName, true);
        allowedUsersByGroup = newAllowedUsersByGroup;
        allowedRoutesByEvent = newAllowedRoutesByEvent;
        usersInRoles = getUserRoles();
        log.debug("AccessControl Refreshed");
    }
	
	/**
	 * Return true if a group exists for the specified group name.
	 * @param groupName	The name of the group
	 * @return	true if this group exists.
	 */
	public boolean groupExists(String groupName) {
		return allowedUsersByGroup.get(groupName) != null;
	}
	
	@Override
	public Map<String, TreeSet<String>> getUserRoles() {
		Set<String> users = new TreeSet<>();
		Set<String> groups = new TreeSet<>();
		Map <String, TreeSet<String>> result = new TreeMap<>();
		updateUsersAndGroups(users, groups);
		updateRoles(users, groups, result);
		return result;
	}

	private void updateUsersAndGroups(Set<String> users, Set<String> groups) {
		for (Entry<String, Map<String, Boolean>> entry : allowedUsersByGroup.entrySet()) {
			groups.add(entry.getKey());
			for (Entry<String, Boolean> userList: entry.getValue().entrySet()) {
				String user = userList.getKey();
				if (userList.getValue() == Boolean.TRUE) {
					if (!IAccessControl.isGroup(user)) {
						users.add(user);
					} else if (!"*".equals(user)) {
						groups.add(user);
					}
				} 
			}
		}
		users.add("*");
	}

	private void updateRoles(Set<String> users, Set<String> groups, Map<String, TreeSet<String>> result) {
		// Do the crosswalk of users and roles, and return membership based on implementation of this class. 
		for (String user: users) {
			for (String group: groups) {
				if (userInGroup(user, group)) {
					Set<String> roles = result.computeIfAbsent(user, k -> new TreeSet<String>());
					roles.add(group);
				}
			}
		}
	}
	
	/**
	 * Returns true if user is a member of the specified group
	 * @param user	The user
	 * @param group	The group
	 * @return true if the user is a member
	 */
	public boolean userInGroup(String user, String group) {
		if (OPEN_TO_ANY.equals(group) || "*".equals(group)) {
			return true;
		}
		
		Map<String, Boolean> membership = getAllowedUsersByGroup().get(group); 
		if (membership == null) {
			log.warn("Group {} does not exist", group);
			return false;
		}
		for (Entry<String, Boolean> entry : membership.entrySet()) {
			if (IAccessControl.isGroup(entry.getKey())) {
				if (userInGroup(user, entry.getKey())) {
					return entry.getValue();
				}
			} else if (commonNameMatches(user, entry.getKey())) {
				return entry.getValue();
			}
		}
		return false;
	}


    @Override
	public Map<String, Map<String, Boolean>> getAllowedUsersByGroup() {
		if (allowedUsersByGroup.isEmpty()) {
			refresh();
		}
    	return Collections.unmodifiableMap(allowedUsersByGroup);
    }
    
    @Override
	public Map<String, Map<String, Boolean>> getAllowedRoutesByEvent() {
		if (allowedRoutesByEvent.isEmpty()) {
			refresh();
		}
    	return Collections.unmodifiableMap(allowedRoutesByEvent);
    }
    
    @Override
	public boolean isUserInRole(String user, String role) {
		if (OPEN_TO_ANY.equals(role) || "*".equals(role)) {
			return true;
		}
		
    	if (usersInRoles.isEmpty()) {
    		refresh();
    	}
    	
    	if (userHasRole(user, role) || userHasRole("*", role)) {
    		return true;
    	}
    	
    	if (user == null) {
    		return false;
    	}
    	
    	for (String aUser : usersInRoles.keySet()) {
    		if (aUser.startsWith("*") && user.endsWith(aUser.substring(1)) && userHasRole(aUser, role)) {
				return true;
			}
    	}
    	return false;
    }
    
	@Override
	public boolean isUserBlacklisted(String user) {
		
		return blacklistEnabled && isUserInRole(user, IAccessControlRegistry.BLACKLIST_ROLE);
	}
    
    private boolean userHasRole(String user, String role) {
    	Set<String> roles = usersInRoles.get(user);
    	return roles != null && roles.contains(role);
    }
    
	@Override
	public Map<String, Boolean> getEventMap(String event) {
    	Map<String, Boolean> map = getAllowedRoutesByEvent().get(event);
		return map == null ? Collections.emptyMap() : map;
	}
	
	@Override
	public Set<String> getEventTypes() {
		return getAllowedRoutesByEvent().keySet(); 
	}

	@Override
	public boolean isRouteAllowed(String route, String event) {
    	if (allowedRoutesByEvent.isEmpty()) {
    		refresh();
    	}
    	Map<String, Boolean> map = getEventMap(event);
		Boolean allowed = map.get(route);
		if (allowed == null) {
			log.warn("Route {} not found for event {}", route, event);
			return false;
		}
		
		return allowed;
    }

	/**
	 * Determing if a URL path matches an access control rule path
	 * @param path	The URL path
	 * @param rulePath	The path in the access control rule
	 * @return true if the two match
	 */
	public static boolean pathsMatch(String path, String rulePath) {
	    if (path.endsWith(rulePath)) {
	        return true;
	    }
	    if (!rulePath.endsWith("/*")) {
	        return false;
	    }
	    // Strip the terminal * and check for the intermediate path
	    rulePath = rulePath.substring(0, rulePath.length() - 1);
	    return path.contains(rulePath);
	}

	/** 
	 * Check for a common name match against a pattern
	 * @param cn    The common name
	 * @param pattern   The pattern to check for
	 * @return  true if there is a match
	 * 
	 * Pattern is a value like *, or *.izgateway.org or a full DNS name like dev.-project.org
	 * * matches everything
	 * *.suffix matches any common name that has the same suffix
	 * Full DNS names match if the strings match. 
	 */
	private static boolean commonNameMatches(String cn, String pattern) {
		// Most common case, handle it first.
		if (pattern.equals(cn)) {
			return true;
		}
		
	    if ("*".equals(pattern) || OPEN_TO_ANY.equals(pattern)) {
	        return true;
	    }
	    
	    if (cn == null) {
	        return false;
	    }
	    
	    return pattern.startsWith("*.") && cn.endsWith(pattern.substring(1));
	}

	/**
	 * Set the server name.  Present to support testing.
	 * @param serverName	The name of the server.
	 */
	@Override
	public void setServerName(String serverName) {
		this.serverName = serverName;
	}
	
	@Override
	public List<String> getAllowedRoles(RequestMethod method, String path) {
		return registry.getAllowedRoles(method, path);
	}

	@Override
	public Boolean checkAccess(String user, String method, String path) {
    	List<String> roles = getAllowedRoles(RequestMethod.valueOf(method), path);
    	// Check for user being blacklisted
    	if (isUserBlacklisted(user)) {
			log.warn("Access attempted to protected path {} {} by blacklisted user {}", method, path, user);
			// User was blacklisted, but the endpoint accepts that.
    		return roles.contains(IAccessControlRegistry.BLACKLIST_ROLE);
		}
    	// Timing is important.  A user that was previously admitted could
    	// later be blacklisted, so we do the blacklist checks first, and those
    	// aren't cached.
		if (wasUserPreviouslyAdmitted(user, method, path)) {
			return true;
		}
    	for (String role: roles) {
    		if (isUserInRole(user, role)) {
    			saveAdmittedUser(user, method, path);
    			return true;
    		}
    	}
    	return roles.isEmpty() ? null : false;
	}
	
    /**
     * Add a new certificate allowed to access the specified path.
     * @param user  The user.
     * @param path  The path the user is allowed to access.
     */
    private void saveAdmittedUser(String user, String method, String path) {
        // Keep the cache from growing uncontrollably.
        if (cachedControlDecisions.size() > MAX_CACHE_SIZE) {
            truncateCache();
        }
        Set<String> s = cachedControlDecisions.computeIfAbsent(user, k -> new LinkedHashSet<>());
        s.add(method + " " + path);
    }
    
    private boolean wasUserPreviouslyAdmitted(String user, String method, String path) {
    	Set<String> s = cachedControlDecisions.get(user);
    	return s != null && s.contains(method + " " + path);
    }

	private void truncateCache() {
		// Lop off some entries.  We really don't care which ones. LRU would be best
		// but it's not really going to matter much.
		int i = 0;
		for (String key: cachedControlDecisions.keySet()) {
		    if (++i > REDUCE_QTY) {
		        break;
		    }
		    cachedControlDecisions.remove(key);
		}
	}

	@Override
	public boolean isMemberOf(String user, String group) {
		return userInGroup(user, group);
	}

	@Override
	public IAccessControl removeUserFromBlacklist(String user) {
		try {
			return accessControlRepository.removeUserFromGroup(user, Roles.BLACKLIST);
		} finally {
			refresh();
		}
	}
	
	@Override
	public IAccessControl addUserToBlacklist(String user) {
		try {
			return accessControlRepository.addUserToGroup(user, Roles.BLACKLIST);
		} finally {
			refresh();
		}
	}

	private static int[] getEnvironmentsToPopulate() throws ServiceConfigurationError {
		switch (SystemUtils.getDestType()) {
		case SystemUtils.DESTTYPE_PROD, SystemUtils.DESTTYPE_ONBOARD:
			return new int[] { SystemUtils.DESTTYPE_PROD, SystemUtils.DESTTYPE_ONBOARD };
		case SystemUtils.DESTTYPE_STAGE:
			return new int[] { SystemUtils.DESTTYPE_STAGE };
		case SystemUtils.DESTTYPE_DEV, SystemUtils.DESTTYPE_TEST:
			return new int[] { SystemUtils.DESTTYPE_DEV, SystemUtils.DESTTYPE_TEST };
		default:
			throw new ServiceConfigurationError("Unknown environment type: " + SystemUtils.getDestType());
		}
	}

	/**
	 * Migrate access controls from access-controls.csv to allowed users.
	 * @throws ServiceConfigurationError if there is an error reading the CSV file
	 */
	@SuppressWarnings("unused")
	private boolean migrateFromCSV() throws ServiceConfigurationError {
		Event migrationEvent = eventRepository.create(new Event(Event.MIGRATION, "ImportAllowedUsers")); 
		if (migrationEvent == null) {
			log.info("Migration already performed for ImportAllowedUsers");
			return false;
		}
		boolean success = false;
		try (
			FileReader isr = new FileReader(migrationData, StandardCharsets.UTF_8);
			CSVReader csvr = new CSVReaderBuilder(isr).withSkipLines(1).withFieldAsNull(CSVReaderNullFieldIndicator.BOTH).build();
		) {
			String[] row = null;
			int[] environments = getEnvironmentsToPopulate();
			
			createAllowedUsers(csvr, environments);
			addDevOpsPrincipals(csvr);
			return success = true;
		} catch (IOException e) {
			log.error(Markers2.append(e), "Error reading access-controls.csv");
			throw new ServiceConfigurationError("Error reading access-controls.csv", e);
		} finally {
			if (success) {
				log.info("Migration to Allowed Users from CSV completed successfully");
				migrationEvent.setCompleted(new Date());
				eventRepository.update(migrationEvent);
			} else {
				log.info("Migration to Allowed Users from CSV failed");
				eventRepository.delete(migrationEvent);
			}
		}
	}

	private void createAllowedUsers(CSVReader csvr, int[] environments) throws IOException {
		Map<String, OrganizationRecord> orgMap = new LinkedHashMap<>();
		Map<String, AllowedUser> allowedUserMap = new LinkedHashMap<>();
		String[] row;
		while (true) {
			// Type,Source,Can Access,Id,Organization Name,Onboarding Cert Common Name,Prod Cert Common Name,Other Cert 1,Other Cert 2
			try {
				row = csvr.readNext();
			} catch (CsvValidationException e) {
				log.error(Markers2.append(e), "CSV is invalid for access-controls.csv at line: {}", e.getLineNumber());
				continue;
			}
			if (row == null || row[0] == null) {
				break;
			}
			// Type,Source,Can Access,Id,Organization Name,Onboarding Cert Common Name,Prod Cert Common Name,Other Cert 1,Other Cert 2
			String type = row[0];
			String orgName = row.length < 1 ? null : row[1];
			@SuppressWarnings("unused")
			String jurisdictionName = row.length < 2 ? null : row[2];
			String destinationId = row.length < 3 ? null : row[3];
			@SuppressWarnings("unused")
			String organizationCertName = row.length < 4 ? null : row[4];
			
			String[] finalRow = row;
			@SuppressWarnings("unused")
			OrganizationRecord orgRecord = orgMap.computeIfAbsent(orgName, 
					k -> createOrgRecord(type, orgName, Arrays.asList(finalRow).subList(5, finalRow.length)));
			
			for (int env : environments) {
				List<Integer> certsToProcess = null;
				switch (env) {
				case SystemUtils.DESTTYPE_PROD, SystemUtils.DESTTYPE_DEV:
					certsToProcess = List.of(6);
					break;
				case SystemUtils.DESTTYPE_ONBOARD, SystemUtils.DESTTYPE_STAGE, SystemUtils.DESTTYPE_TEST:
					certsToProcess = List.of(5, 7, 8);
				}
				for (int certToProcess : certsToProcess) {
					String certCommonName = row.length <= certToProcess ? null : row[certToProcess];
					if (certCommonName == null || certCommonName.isBlank()) {
						continue;
					}
					String key = String.format("%s#%s#%s", env, destinationId, certCommonName);
					allowedUserMap.computeIfAbsent(key, k -> createAllowedUserRecord(env, destinationId, certCommonName));				
				}
			}
		}
		organizationRecordRepository.migrate(orgMap.values());
		allowedUserRepository.migrate(allowedUserMap.values());
	}

	private void addDevOpsPrincipals(CSVReader csvr) throws IOException  {
		String[] row = null;
		Set<String> monitoringCert = new LinkedHashSet<>();
		Set<String> devOpsStaff = new LinkedHashSet<>();
		Set<String> preprodCerts = new LinkedHashSet<>();
		Set<String> onboardingCerts = new LinkedHashSet<>();
		Set<String> prodCerts = new LinkedHashSet<>();
		Set<String> developmentCerts = new LinkedHashSet<>();
		try {
			// Skip header line
			row = csvr.readNext();
		} catch (CsvValidationException e) {
			log.error(Markers2.append(e), "CSV malformed for access-controls.csv at line: {}", e.getLineNumber());
		}
		while (true) {
			// Type,Principal,Organization
			try {
				row = csvr.readNext();
			} catch (CsvValidationException e) {
				log.error(Markers2.append(e), "CSV is invalid for access-controls.csv at line: {}", e.getLineNumber());
				continue;
			}
			if (row == null) {
				break;
			}
			String type = row[0];
			String principal = row.length < 1 ? null : row[1];
			String organization = row.length < 2 ? null : row[2];
			OrganizationRecord orgRecord = this.organizationRecordRepository.find(organization);
			if (orgRecord == null) {
				orgRecord = createOrgRecord(type, organization, List.of(principal));
			} else if (!orgRecord.getPrincipalNames().contains(principal)) {
				orgRecord.addPrincipalName(principal);
			}
			organizationRecordRepository.store(orgRecord);
			switch (type.toLowerCase()) {
			case "monitoring":	monitoringCert.add(principal); break;
			case "staff":		devOpsStaff.add(principal); break;
			case "preprod":		preprodCerts.add(principal); break;
			case "onboarding":	onboardingCerts.add(principal); break;
			case "prod":		prodCerts.add(principal); break;
			case "development":	developmentCerts.add(principal); break;
			default:
				log.error("Unrecognized type {} in access-controls.csv", type);
			}
		}
		
		if (SystemUtils.getDestType() == SystemUtils.DESTTYPE_STAGE) {
			addSystemCerts(monitoringCert, SystemUtils.DESTTYPE_STAGE, Roles.SOAP);
			addSystemCerts(devOpsStaff, SystemUtils.DESTTYPE_STAGE, Roles.ADMIN);
			addSystemCerts(preprodCerts, SystemUtils.DESTTYPE_STAGE, Roles.INTERNAL);
			addToDenyList(onboardingCerts, SystemUtils.DESTTYPE_STAGE);
			addToDenyList(prodCerts, SystemUtils.DESTTYPE_STAGE);
			addToDenyList(developmentCerts, SystemUtils.DESTTYPE_STAGE);
		}
		
		if (SystemUtils.getDestType() == SystemUtils.DESTTYPE_ONBOARD || SystemUtils.getDestType() == SystemUtils.DESTTYPE_PROD) {
			addSystemCerts(monitoringCert, SystemUtils.DESTTYPE_PROD, Roles.SOAP);
			addSystemCerts(devOpsStaff, SystemUtils.DESTTYPE_PROD, Roles.ADMIN);
			addSystemCerts(onboardingCerts, SystemUtils.DESTTYPE_PROD, Roles.INTERNAL);
			addToDenyList(preprodCerts, SystemUtils.DESTTYPE_PROD);
			addToDenyList(prodCerts, SystemUtils.DESTTYPE_PROD);
			addToDenyList(developmentCerts, SystemUtils.DESTTYPE_PROD);
			
			addSystemCerts(monitoringCert, SystemUtils.DESTTYPE_ONBOARD, Roles.SOAP);
			addSystemCerts(devOpsStaff, SystemUtils.DESTTYPE_ONBOARD, Roles.ADMIN);
			addSystemCerts(prodCerts, SystemUtils.DESTTYPE_ONBOARD, Roles.INTERNAL);
			addToDenyList(preprodCerts, SystemUtils.DESTTYPE_ONBOARD);
			addToDenyList(onboardingCerts, SystemUtils.DESTTYPE_ONBOARD);
			addToDenyList(developmentCerts, SystemUtils.DESTTYPE_ONBOARD);
		}
		
		if (SystemUtils.getDestType() == SystemUtils.DESTTYPE_DEV || SystemUtils.getDestType() == SystemUtils.DESTTYPE_TEST) {
			addSystemCerts(monitoringCert, SystemUtils.DESTTYPE_DEV, Roles.SOAP);
			addSystemCerts(devOpsStaff, SystemUtils.DESTTYPE_DEV, Roles.ADMIN);
			addSystemCerts(developmentCerts, SystemUtils.DESTTYPE_DEV, Roles.INTERNAL);
			addToDenyList(preprodCerts, SystemUtils.DESTTYPE_DEV);
			addToDenyList(onboardingCerts, SystemUtils.DESTTYPE_DEV);
			addToDenyList(prodCerts, SystemUtils.DESTTYPE_DEV);
			
			addSystemCerts(monitoringCert, SystemUtils.DESTTYPE_TEST, Roles.SOAP);
			addSystemCerts(monitoringCert, SystemUtils.DESTTYPE_TEST, Roles.SOAP);
			addSystemCerts(devOpsStaff, SystemUtils.DESTTYPE_TEST, Roles.ADMIN);
			addSystemCerts(developmentCerts, SystemUtils.DESTTYPE_TEST, Roles.INTERNAL);
			addToDenyList(preprodCerts, SystemUtils.DESTTYPE_TEST);
			addToDenyList(onboardingCerts, SystemUtils.DESTTYPE_TEST);
			addToDenyList(prodCerts, SystemUtils.DESTTYPE_TEST);
		}
	}

	/**
	 * Give the specified principals access to IZ Gateway REST and SOAP APIs  
	 * @param principalsToAllow	The principals to allow
	 * @param destType	The destination type they are being allowed to
	 * @param role The role to assign
	 */
	private void addSystemCerts(Collection<String> principalsToAllow, int destType, String role) {
		IAccessGroup g = accessGroupRepository.findByTypeAndName(destType, role);
		if (g == null) {
			g = new AccessGroup();
			g.setEnvironment(destType);
			g.setGroupName(role);
			g.getRoles().add(role);
			g.getUsers().addAll(principalsToAllow);
			accessGroupRepository.store(g);
		}
	}

	/**
	 * Given the specified principals, add them to the deny list.
	 * @param principalsToDeny	The principals to deny
	 * @param destType	The destination type they are being denied from
	 */
	private void addToDenyList(Set<String> principalsToDeny, int destType) {
		for (String principal : principalsToDeny) {
			DenyListRecord dlr = new DenyListRecord();
			dlr.setEnvironment(destType);
			dlr.setPrincipal(principal);
			denyListRecordRepository.store(dlr);
		}
	}

	private OrganizationRecord createOrgRecord(String type, String orgName, Collection<String> finalRow) {
		OrganizationRecord org = new OrganizationRecord();
		org.setType(type);
		org.setOrganizationName(orgName);
		for (String certCn : finalRow) {
			if (certCn != null && !certCn.isBlank()) {
				org.addPrincipalName(certCn);
			}
		}
		return org;
	}
	
	private AllowedUser createAllowedUserRecord(int env, String destinationId, String principal) {
		AllowedUser u = new AllowedUser();
		u.setEnvironment(env);
		u.setDestinationId(destinationId);
		u.setPrincipal(principal);
		u.setEnabled(true);
		u.setValidatedOn(u.getUpdatedOn());
		return u;
	}
}