package gov.cdc.izgateway.hub.service.accesscontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Map.Entry;

import gov.cdc.izgateway.dynamodb.model.AccessControl;
import gov.cdc.izgateway.dynamodb.model.AccessGroup;
import gov.cdc.izgateway.model.IAccessControl;
import gov.cdc.izgateway.model.IAccessGroup;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.service.IAccessControlRegistry;
import gov.cdc.izgateway.service.IAccessControlService;
import gov.cdc.izgateway.utils.SystemUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class OldModelHelper implements AccessControlModelHelper {
	/** The access control service this helper is associated with. */
	private final AccessControlService accessControlService;
	/** The server's own access control entry. */
	private AccessControl serverAccess; 

	/**
	 * @param accessControlService
	 */
	OldModelHelper(AccessControlService accessControlService) {
		this.accessControlService = accessControlService;
		serverAccess = new AccessControl(
			IAccessControlService.GROUP_CATEGORY,
			Roles.INTERNAL,
			this.accessControlService.getServerName(), 
			SystemUtils.getDestType()
		);
	}

	private Map<String, Set<IAccessControl>> allowedUsersByGroup = Collections.emptyMap();
	private Map<String, TreeSet<String>> usersInRoles = Collections.emptyMap();
	private Map<String, Map<String, Boolean>> allowedRoutesByEvent = Collections.emptyMap();
	
	
	@Override
	public void refresh() {
		Map<String, Set<IAccessControl>> newAllowedUsersByGroup = new TreeMap<>();
        Map<String, Map<String, Boolean>> newAllowedRoutesByEvent = new TreeMap<>();
    	getControls(newAllowedUsersByGroup, newAllowedRoutesByEvent);
        // Add the server itself to the internal group.
        newAllowedUsersByGroup.computeIfAbsent(Roles.INTERNAL, k -> new LinkedHashSet<>()).add(serverAccess);
        allowedUsersByGroup = newAllowedUsersByGroup;
        allowedRoutesByEvent = newAllowedRoutesByEvent;
        usersInRoles = getUserRoles();
	}
	
	@Override
	public Map<String, TreeSet<String>> getUserRoles() {
		Map <String, TreeSet<String>> result = new TreeMap<>();
		if (allowedUsersByGroup.isEmpty()) {
			refresh();
		}
		Set<String> users = new TreeSet<>();
		Set<String> groups = new TreeSet<>();
		updateUsersAndGroups(users, groups);
		updateRoles(users, groups, result);
		return result;
	}
	
	private void updateUsersAndGroups(Set<String> users, Set<String> groups) { 
		for (Entry<String, Set<IAccessControl>> entry : allowedUsersByGroup.entrySet()) {
			users.add(entry.getKey());
			for (IAccessControl ac: entry.getValue()) {
				String member = ac.getMember();
				if (!ac.isAllowed() || member == null) {
					continue;
				}
				if (!IAccessControl.isGroup(member)) {
					users.add(member);
				} else {
					groups.add(member);
				}
			}
		}
		users.add("*");
	}
	
	private void updateRoles(Set<String> users, Set<String> groups, Map<String, TreeSet<String>> result) {
		// Do the crosswalk of users and roles, and return membership based on implementation of this class. 
		for (String user: users) {
			for (String group: groups) {
				if (isUserInGroup(user, group)) {
					Set<String> roles = result.computeIfAbsent(user, k -> new TreeSet<String>());
					roles.add(group);
				}
			}
		}
	}
	
	private void getControls(
		Map<String, Set<IAccessControl>> newAllowedUsersByGroup,
		Map<String, Map<String, Boolean>> newAllowedRoutesByEvent
	) {
		List<IAccessControl> controls = new ArrayList<>(accessControlService.accessControlRepository.findAllForEnvironment());
        for (IAccessControl control: controls) {
        	switch (control.getCategory()) {
        	case IAccessControlService.GROUP_CATEGORY:
        		newAllowedUsersByGroup
					.computeIfAbsent(
						control.getName(),
	        			k -> new LinkedHashSet<>()
	        		)
	        		.add(control);
        		break;
        	case IAccessControlService.ROUTE_CATEGORY:
        		newAllowedRoutesByEvent.computeIfAbsent(
        			control.getName(),
		        	k -> new LinkedHashMap<>()
		        ).put(control.getMember(), control.isAllowed());
        		break;
        	default:
        		log.error("Unrecognized category {} in AccessControls", control.getCategory());
        		continue;
        	}
		}
	}

	@Override
	public Map<String, Object> getGroups() {
		if (allowedUsersByGroup.isEmpty()) {
			refresh();
		}
		Map<String, Object> results = new TreeMap<>();
		for (Entry<String, Set<IAccessControl>> entry : allowedUsersByGroup.entrySet()) {
			String groupName = entry.getKey();
			Set<IAccessControl> groups = entry.getValue();
			for (IAccessControl acGroup : groups) {
				IAccessGroup group = (IAccessGroup)results.computeIfAbsent(
					groupName, k -> createAccessGroupFromControl(acGroup)
				);
				// If the group is for a named role, add the role to the group.
				if (Roles.values().contains(groupName)) {
					group.getRoles().add(groupName);
				}
				String userMember = acGroup.getMember();
				if (IAccessControl.isGroup(userMember)) {
					group.getGroups().add(userMember);
				} else {
					group.getUsers().add(userMember);
				}
				results.put(group.getGroupName(), group);
			}
		}
    	return results;
	}

	private AccessGroup createAccessGroupFromControl(IAccessControl acGroup) {
		AccessGroup group = new AccessGroup();
		group.setGroupName(acGroup.getName());
		group.setEnvironment(SystemUtils.getDestType());
		return group;
	}

	@Override
	public boolean isUserInRole(String user, String role) {
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
	
	private boolean userHasRole(String user, String role) {
    	Set<String> roles = usersInRoles.get(user);
    	return roles != null && roles.contains(role);
    }
	
	@Override
	public boolean isUserInGroup(String user, String group) {
		IAccessGroup g = (IAccessGroup)getGroups().get(group); 
		if (g == null) {
			log.warn("Group {} does not exist", group);
			return false;
		}

		for (String u: g.getUsers()) {
			if (AccessControlModelHelper.commonNameMatches(user, u)) {
				return true;
			}
		}
		
		for (String groupName: g.getGroups()) {
			if (isUserInGroup(user, groupName)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean isUserDenied(String user) {
		return accessControlService.blacklistEnabled && isUserInRole(user, IAccessControlRegistry.BLACKLIST_ROLE);
	}

	@Override
	public Set<String> getEventTypes() {
		return allowedRoutesByEvent.keySet();
	}

	@Override
	public IAccessControl unblock(String user) {
		return this.accessControlService.accessControlRepository.removeUserFromGroup(user, Roles.BLACKLIST);
	}

	@Override
	public IAccessControl block(String user) {
		return this.accessControlService.accessControlRepository.addUserToGroup(user, Roles.BLACKLIST);
	}

	@Override
	public Set<String> getDenyList() {
		Set<IAccessControl> denyList = allowedUsersByGroup.get(IAccessControlRegistry.BLACKLIST_ROLE);
		Set<String> result = new TreeSet<>();
		denyList.stream().filter(e -> e.isAllowed()).forEach(e -> result.add(e.getMember()));
		return result;
	}

	@Override
	public boolean canAccessDestination(String user, String destId) {
		// The old model does not have destination access controls
		return true;
	}
}