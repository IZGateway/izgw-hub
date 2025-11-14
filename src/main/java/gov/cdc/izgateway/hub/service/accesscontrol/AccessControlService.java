package gov.cdc.izgateway.hub.service.accesscontrol;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMethod;

import gov.cdc.izgateway.dynamodb.model.AccessControl;
import gov.cdc.izgateway.dynamodb.model.AccessGroup;
import gov.cdc.izgateway.dynamodb.model.AllowedUser;
import gov.cdc.izgateway.dynamodb.model.DenyListRecord;
import gov.cdc.izgateway.dynamodb.model.FileType;
import gov.cdc.izgateway.hub.repository.IAccessControlRepository;
import gov.cdc.izgateway.hub.repository.IAccessGroupRepository;
import gov.cdc.izgateway.hub.repository.IAllowedUserRepository;
import gov.cdc.izgateway.hub.repository.IDenyListRecordRepository;
import gov.cdc.izgateway.hub.repository.IFileTypeRepository;
import gov.cdc.izgateway.hub.repository.RepositoryFactory;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.service.IAccessControlRegistry;
import gov.cdc.izgateway.service.IAccessControlService;
import lombok.extern.slf4j.Slf4j;
import lombok.Getter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.Set;
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

	final IAccessControlRepository<AccessControl> accessControlRepository;
    final IAccessGroupRepository<AccessGroup> accessGroupRepository;
    final IAllowedUserRepository<AllowedUser> allowedUserRepository;
    final IDenyListRecordRepository<DenyListRecord> denyListRecordRepository;
    final IFileTypeRepository<FileType> fileTypeRepository;
    
	private final IAccessControlRegistry registry;
    private final AccessControlMigrator migrator;
	
    OldModelHelper oldModelHelper; 
    NewModelHelper newModelHelper;
    AccessControlModelHelper currentModelHelper = oldModelHelper;
	
    @Getter
	private boolean migrated = false;
	/**
	 * A cache of positive access control decisions. It needs to be concurrent
	 * because it can be modified by multiple threads.
	 */
	private Map<String, Set<String>> cachedControlDecisions = new ConcurrentHashMap<>();

	private int refreshPeriod = 300;

	@Getter
	@Value("${server.hostname:dev.izgateway.org}") 
	String serverName;
	
	@Value("${security.enable-blacklist:true}") 
	boolean blacklistEnabled;
	
	@Value("${hub.migration-data:access-controls.csv}")
	private String migrationData;
	
    /**
     * Create a new AccessControlService
     * @param factory The repository factory to use
     * @param registry	The registry for managing access control to methods
     */
    @Autowired
    public AccessControlService(RepositoryFactory factory, IAccessControlRegistry registry, AccessControlMigrator migrator) {
        this.accessControlRepository = factory.accessControlRepository();
        this.registry = registry;
        this.accessGroupRepository = factory.accessGroupRepository();
        this.allowedUserRepository = factory.allowedUserRepository();
        this.denyListRecordRepository = factory.denyListRecordRepository();
        this.fileTypeRepository = factory.fileTypeRepository();
        this.migrator = migrator;
    }
    
	/**
     * Configure service to update itself periodically after initialization.
     */
    public void afterPropertiesSet() {
    	// Initialize both model helpers here because they need access to the service after
    	// it has been constructed.
	    newModelHelper = new NewModelHelper(this);
	    oldModelHelper = new OldModelHelper(this);
    	try {
        	migrated = migrator.checkForMigration();
        	currentModelHelper = migrated ? newModelHelper : oldModelHelper;
    	} catch (ServiceConfigurationError e) {
    		log.error(Markers2.append(e), "Error during Access Control migration: {}", e.getMessage());
    		migrated = false;  // Use old model access control data if migration failed.
    	}
        log.debug("Refresh Scheduled for AccessControl");
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::refresh, 0, refreshPeriod, TimeUnit.SECONDS);
    }

    @Override
	public void refresh() {  
        cachedControlDecisions.clear();
        currentModelHelper.refresh();
    }
    
	@Override
	public Map<String, TreeSet<String>> getUserRoles() {
		return currentModelHelper.getUserRoles();
	}

    @Override
	public Map<String, Object> getGroups() {
    	return currentModelHelper.getGroups();
    }
    
    @Override
	public boolean isUserInRole(String user, String role) {
		return currentModelHelper.isUserInRole(user, role);
    }
    
	@Override
	public boolean isUserInGroup(String user, String group) {
		return currentModelHelper.isUserInGroup(user, group);
	}
    
	@Override
	public boolean isUserDenied(String user) {
		return currentModelHelper.isUserDenied(user);
	}
    
	@Override
	public Set<String> getEventTypes() {
		return currentModelHelper.getEventTypes();
	}

	@Override
	public List<String> getAllowedRoles(RequestMethod method, String path) {
		return registry.getAllowedRoles(method, path);
	}

	@Override
	public Boolean checkAccess(String user, String method, String path) {
    	List<String> roles = getAllowedRoles(RequestMethod.valueOf(method), path);
    	// Timing is important.  A user that was previously admitted could
    	// later be denied, so we do the denylist checks first, and those
    	// are not cached.
    	if (isUserDenied(user)) {
			log.warn("Access attempted to protected path {} {} by denied user {}", method, path, user);
			// User was denied, but the endpoint accepts that.
    		return roles.contains(IAccessControlRegistry.BLACKLIST_ROLE);
		}
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
	public boolean canAccessDestination(String user, String destId) {
		return currentModelHelper.canAccessDestination(user,  destId);
	}

	@Override
	public Object removeUserFromDenyList(String user) {
		try {
			return currentModelHelper.unblock(user);
		} finally {
			refresh();
		}
	}
	
	@Override
	public Object addUserToDenyList(String user) {
		try {
			return currentModelHelper.block(user);
		} finally {
			refresh();
		}
	}

	@Override
	public Set<String> getDenyList() {
		return currentModelHelper.getDenyList();
	}
	
	/**
	 * Sets the migrated status, used for unit testing when accessing a database that has already been migrated.
	 * @param migrated	true if migration has been performed
	 */
	public void setMigrated(boolean migrated) {
		this.migrated = migrated;
		this.currentModelHelper = migrated ? newModelHelper : oldModelHelper;
		refresh();
	}
}