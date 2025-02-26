package gov.cdc.izgateway.hub.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMethod;

import gov.cdc.izgateway.hub.repository.IAccessControlRepository;
import gov.cdc.izgateway.hub.repository.RepositoryFactory;
import gov.cdc.izgateway.model.IAccessControl;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.service.IAccessControlRegistry;
import gov.cdc.izgateway.service.IAccessControlService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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

	private Map<String, Map<String, Boolean>> allowedUsersByGroup = Collections.emptyMap();
	private Map<String, TreeSet<String>> usersInRoles = Collections.emptyMap();
	private Map<String, Map<String, Boolean>> allowedRoutesByEvent = Collections.emptyMap();

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

    /**
     * Create a new AccessControlService
     * @param factory The repository factory to use
     * @param registry	The registry for managing access control to methods
     */
    @Autowired
    public AccessControlService(RepositoryFactory factory, IAccessControlRegistry registry) {
        this.accessControlRepository = factory.accessControlRepository();
        this.registry = registry;
    }
    
	@Override
	public String getServerName() {
		return serverName;
	}

	/**
     * Configure service to update itself periodically after initialization.
     */
    public void afterPropertiesSet() { 
        log.debug("Refresh Scheduled for AccessControl");
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::refresh, 0, refreshPeriod, TimeUnit.SECONDS);
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
					if (!isGroup(user)) {
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
			if (isGroup(entry.getKey())) {
				if (userInGroup(user, entry.getKey())) {
					return entry.getValue();
				}
			} else if (commonNameMatches(user, entry.getKey())) {
				return entry.getValue();
			}
		}
		return false;
	}


	/**
	 * Groups are simple names following the pattern [0-9]*[a-zA-Z]+[a-zA-Z0-9]*.
	 * In other words, they must contain only letters or digits, and must contain
	 * at least one letter, and cannot be named "localhost".
	 * 
	 * @param	member	The pattern to check for a group name
	 * @return	True if this is a group name, false for a user name pattern or a serial number
	 */
	private static final boolean isGroup(String member) {
		return !StringUtils.contains(member, '.') && !StringUtils.isNumeric(member) && !"localhost".equals(member);
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
}
