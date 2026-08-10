package gov.cdc.izgateway.hub.service.accesscontrol;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMethod;

import gov.cdc.izgateway.dynamodb.model.AccessControl;
import gov.cdc.izgateway.dynamodb.model.AccessGroup;
import gov.cdc.izgateway.dynamodb.model.AllowedUser;
import gov.cdc.izgateway.dynamodb.model.DenyListRecord;
import gov.cdc.izgateway.dynamodb.model.FileType;
import gov.cdc.izgateway.dynamodb.model.Jurisdiction;
import gov.cdc.izgateway.hub.repository.IAccessControlRepository;
import gov.cdc.izgateway.hub.repository.IAccessGroupRepository;
import gov.cdc.izgateway.hub.repository.IAllowedUserRepository;
import gov.cdc.izgateway.hub.repository.IDenyListRecordRepository;
import gov.cdc.izgateway.hub.repository.IFileTypeRepository;
import gov.cdc.izgateway.hub.repository.RepositoryFactory;
import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.logging.markers.Markers2;
import gov.cdc.izgateway.hub.security.ApiKeyPrincipal;
import gov.cdc.izgateway.hub.security.UseType;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IFileType;
import gov.cdc.izgateway.model.IJurisdiction;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.service.IAccessControlRegistry;
import gov.cdc.izgateway.service.IAccessControlService;
import gov.cdc.izgateway.service.IDestinationService;
import gov.cdc.izgateway.service.IJurisdictionService;
import gov.cdc.izgateway.soap.fault.SecurityFault;
import lombok.extern.slf4j.Slf4j;
import lombok.Getter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.Set;
import java.util.stream.Collectors;
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
    /** Resolves a destId to its destination so the destination's jurisdiction can be read (IGDD-3257). */
    private final IDestinationService destinationService;
    /** Supplies the destination jurisdiction's allowedUseTypes (IGDD-3257). */
    private final IJurisdictionService jurisdictionService;

    @Getter
    @Value("${hub.access-control.action:warn}")
    protected String accessControlAction;

    /**
     * Action taken when an API-key credential's useTypes do not intersect the destination jurisdiction's
     * allowedUseTypes: {@code deny} rejects the message, anything else logs a warning and allows it.
     * Defaults to {@code warn} — see {@link #checkUseTypeAccessToDestination(String)} for why.
     */
    @Getter
    @Value("${hub.access-control.use-type-action:warn}")
    protected String useTypeAction;

    private OldModelHelper oldModelHelper; 
    private NewModelHelper newModelHelper;
    private AccessControlModelHelper currentModelHelper;
	
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
     * @param migrator The access control migrator
     * @param destinationService	Resolves destIds for the use-type check; injected lazily so access
     * 		control does not participate in destination-service startup ordering
     * @param jurisdictionService	Supplies destination jurisdictions for the use-type check; injected lazily
     * 		for the same reason
     */
    @Autowired
    public AccessControlService(RepositoryFactory factory, IAccessControlRegistry registry, AccessControlMigrator migrator,
    		@Lazy IDestinationService destinationService, @Lazy IJurisdictionService jurisdictionService) {
        this.accessControlRepository = factory.accessControlRepository();
        this.registry = registry;
        this.accessGroupRepository = factory.accessGroupRepository();
        this.allowedUserRepository = factory.allowedUserRepository();
        this.denyListRecordRepository = factory.denyListRecordRepository();
        this.fileTypeRepository = factory.fileTypeRepository();
        this.migrator = migrator;
        this.destinationService = destinationService;
        this.jurisdictionService = jurisdictionService;
    }
    
	/**
     * Configure service to update itself periodically after initialization.
     */
    public void afterPropertiesSet() {
    	// Initialize both model helpers here because they need access to the service after
    	// it has been constructed.
	    newModelHelper = new NewModelHelper(this);
	    currentModelHelper = oldModelHelper = new OldModelHelper(this);
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
		log.debug("Access check: user={} roles={}", user,
				Roles.values().stream().filter(r -> isUserInRole(user, r)).collect(Collectors.toSet()));
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
    
    // Note: the positive-decision cache is keyed by user identity (cert CN or JWT upn), not by
    // individual token. For JWT callers this means a later lower-role token for the same upn can
    // bypass role evaluation via this cache until refresh() clears it (~300s). This is intentional:
    // it matches the existing cert-auth behavior (CN cached, not individual cert) and revocation
    // still works — revoked tokens are rejected at authentication before reaching this method.
    // A token pair with different role sets for the same upn is an unusual configuration.
    // Follow-up: if per-token role isolation is needed, include jti or role-set hash in the cache key.
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
		if (isUserInRole(user, Roles.ADMIN)) {
			return true;
		}
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
	public Object addUserToDenyList(String user, String reason) {
		try {
			return currentModelHelper.block(user, reason);
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

	/**
	 * Look up a FileType by report type name (case-insensitive).
	 * Delegates to NewModelHelper which holds the fileTypeCache.
	 *
	 * @param reportType the report type name (e.g. "routineImmunization")
	 * @return the matching FileType, or null if not found
	 */
	@Override
	public IFileType getFileType(String reportType) {
		return newModelHelper.getFileType(reportType);
	}

	/**
	 * Check access to a destination and throw a fault or log a warning if access would not be granted.
	 * @param destId	The destination
	 * @throws SecurityFault	If access is denied
	 */
	@Override
	public void checkAccessToDestination(String destId) throws SecurityFault {
        String sender = RequestContext.getSourceInfo().getCommonName();
        if (!canAccessDestination(sender, destId)) {
            SecurityFault fault = SecurityFault.generalSecurity("Source Not Allowed", String.format("%s is not permitted to send messages to %s", sender, destId), null);
        	if (!accessControlAction.equalsIgnoreCase("deny")) {
        		// Log a warning but allow the message to be sent
				log.warn(Markers2.append(fault), "Access control violation warning: {}", fault.getMessage());
				return;
			}
        	RequestContext.getTransactionData().setProcessError(fault);
        	throw fault;
        }
        checkUseTypeAccessToDestination(destId);
	}

	/**
	 * Enforce the use-type rule of IGDD-3140 / IGDD-3257: the calling credential's {@code useTypes} MUST
	 * intersect the <b>destination</b> jurisdiction's {@code allowedUseTypes}.
	 *
	 * <p>This applies only to API-key (JWT) callers, because {@code useTypes} is a property of an
	 * {@code ApiKeyCredential}; mTLS certificate callers have no credential record and are unaffected.
	 * Note this is a deliberately different question from role authorization — roles come solely from the
	 * DynamoDB AccessGroup table for both caller types (see the {@code jwt-upn-authorization} change);
	 * use-types are credential-scoped data-sharing policy and exist only on the API-key path.</p>
	 *
	 * <p>The check is per-destination, not per-credential: a sender's credential is bound to its own
	 * jurisdiction but may transmit to many destinations, so the same credential can be authorized for one
	 * destination and denied by the next.</p>
	 *
	 * <p>Governed by {@code hub.access-control.use-type-action}, which mirrors
	 * {@code hub.access-control.action}: {@code deny} rejects with a {@link SecurityFault};
	 * anything else (the default {@code warn}) logs the violation and allows the message. The default is
	 * {@code warn} because an empty {@code allowedUseTypes} denies all API-key senders, so the rule cannot
	 * be enforced safely until jurisdiction and credential use-type data has been seeded (IGDD-3258).</p>
	 *
	 * @param destId	The destination being addressed
	 * @throws SecurityFault	If the intersection is empty and the configured action is {@code deny}
	 */
	private void checkUseTypeAccessToDestination(String destId) throws SecurityFault {
		if (!(RequestContext.getPrincipal() instanceof ApiKeyPrincipal apiKey)) {
			return;
		}

		IDestination dest = destinationService.findByDestId(destId);
		if (dest == null) {
			// Unknown destination — reported as UnknownDestinationFault by the caller; nothing to check.
			return;
		}

		IJurisdiction jurisdiction = jurisdictionService.getJurisdiction(dest.getJurisdictionId());
		Set<String> allowedUseTypes = jurisdiction instanceof Jurisdiction j ? j.getAllowedUseTypes() : null;

		SecurityFault fault = useTypeViolation(apiKey, destId, allowedUseTypes);
		if (fault == null) {
			return;
		}
		if (!useTypeAction.equalsIgnoreCase("deny")) {
			log.warn(Markers2.append(fault), "Use type access control violation warning: {}", fault.getMessage());
			return;
		}
		RequestContext.getTransactionData().setProcessError(fault);
		throw fault;
	}

	/**
	 * Pure decision function, extracted for testability: return the fault describing a use-type violation,
	 * or {@code null} when the credential's useTypes intersect the destination jurisdiction's
	 * allowedUseTypes.
	 *
	 * @param apiKey			The calling API-key principal
	 * @param destId			The destination being addressed
	 * @param allowedUseTypes	The destination jurisdiction's allowedUseTypes; may be {@code null} or empty,
	 * 							both of which deny
	 * @return the fault to warn on or throw, or {@code null} if access is permitted
	 */
	static SecurityFault useTypeViolation(ApiKeyPrincipal apiKey, String destId, Set<String> allowedUseTypes) {
		if (UseType.intersects(apiKey.getUseTypes(), allowedUseTypes)) {
			return null;
		}
		return SecurityFault.generalSecurity("Use Type Not Allowed",
				String.format("Credential %s (useTypes=%s) for %s is not permitted to send to %s (allowedUseTypes=%s)",
						apiKey.getJti(), apiKey.getUseTypes(), apiKey.getUpn(), destId, allowedUseTypes),
				null);
	}
}