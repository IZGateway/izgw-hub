package gov.cdc.izgateway.hub.service.accesscontrol;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.model.DbAudit;
import gov.cdc.izgateway.model.IAccessGroup;
import gov.cdc.izgateway.model.IAllowedUser;
import gov.cdc.izgateway.model.IDenyListRecord;
import gov.cdc.izgateway.model.IFileType;
import gov.cdc.izgateway.repository.IRepository;
import gov.cdc.izgateway.utils.SystemUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class NewModelHelper implements AccessControlModelHelper {
	/**
	 * 
	 */
	private final AccessControlService accessControlService;

	/**
	 * @param accessControlService
	 */
	NewModelHelper(AccessControlService accessControlService) {
		this.accessControlService = accessControlService;
	}

	private Map<String, IAccessGroup> accessGroupCache = Collections.emptyMap();
	private Map<String, IDenyListRecord> denyListRecordCache = Collections.emptyMap();
	private Map<String, IFileType> fileTypeCache = Collections.emptyMap();
	private Map<String, Set<IAllowedUser>> allowedUserCache = Collections.emptyMap();
	
	@Override
	public void refresh() {
    	accessGroupCache = this.accessControlService.newModelHelper.refreshCache(this.accessControlService.accessGroupRepository, ag -> ag.getGroupName());
    	denyListRecordCache = this.accessControlService.newModelHelper.refreshCache(this.accessControlService.denyListRecordRepository, dr -> dr.getPrincipal());
    	fileTypeCache = this.accessControlService.newModelHelper.refreshCache(this.accessControlService.fileTypeRepository, ft -> ft.getFileTypeName());
    	Map<String, Set<IAllowedUser>> newAllowedUserCache = new TreeMap<>();
    	for (IAllowedUser user : this.accessControlService.newModelHelper.refreshCache(this.accessControlService.allowedUserRepository, au -> au.getPrincipal()).values()) {
    		Set<IAllowedUser> userSet = newAllowedUserCache.computeIfAbsent(
    			user.getDestinationId(), 
    			k -> new TreeSet<>()
    		);
    		userSet.add(user);
    	}
    	allowedUserCache = newAllowedUserCache;
	}
	
	<T extends DbAudit> Map<String, T> refreshCache(IRepository<T> repo, Function<T, String> nameFunction) {
		TreeMap<String, T> cache = new TreeMap<>();
		repo.findAllForEnvironment().stream()
			.forEach(record -> cache.put(nameFunction.apply(record), record));
		return cache;
	}

	@Override
	public Map<String, TreeSet<String>> getUserRoles() {
		
		Map <String, TreeSet<String>> result = new TreeMap<>();
		if (accessGroupCache.isEmpty()) {
			refresh();
		}
		for (IAccessGroup group : accessGroupCache.values()) {
			Set<String> groupRoles = new TreeSet<>(group.getRoles());
			for (String user : group.getUsers()) {
				Set<String> roles = result.computeIfAbsent(user, k -> new TreeSet<String>());
				roles.addAll(groupRoles);
			}
			for (String memberGroup : group.getGroups()) {
				IAccessGroup ag = accessGroupCache.get(memberGroup);
				if (ag != null) {
					for (String user : ag.getUsers()) {
						Set<String> roles = result.computeIfAbsent(user, k -> new TreeSet<String>());
						roles.addAll(groupRoles);
					}
				}
			}
		}
		return result;
	}

	@Override
	public boolean isUserDenied(String user) {
		if (denyListRecordCache.isEmpty()) {
			refresh();
		}
		return this.accessControlService.blacklistEnabled && denyListRecordCache.containsKey(user);
	}
	
	@Override
	public Set<String> getEventTypes() {
		return 	fileTypeCache.keySet();
	}
	
	@Override
	public boolean isUserInRole(String user, String role) {
		for (IAccessGroup group : accessGroupCache.values()) {
			if (group.getRoles().contains(role)) {
				Set<String> users = group.getUsers(); 
				if (users.contains(user) || users.contains("*")) {
					return true;
				}
				for (String memberGroupName : group.getGroups()) {
					IAccessGroup memberGroup = accessGroupCache.get(memberGroupName);
					if (memberGroup != null && memberGroup.getUsers().contains(user)) {
						return true;
					}
				}
			}
		}
		return false;
	}
	
	@Override
	public boolean isUserInGroup(String user, String group) {
		IAccessGroup accessGroup = accessGroupCache.get(group);
		if (accessGroup == null) {
			log.warn("Group {} does not exist", group);
			return false;
		}
		Set<String> users = accessGroup.getUsers();
		if (users.contains(user) || users.contains("*")) {
			return true;
		}
		
		return accessGroup.getGroups().stream().anyMatch(g -> isUserInGroup(user, g));
	}
	
	@Override
	public Map<String, ?> getGroups() {
		return Collections.unmodifiableMap(accessGroupCache);
	}

	@Override
	public boolean canAccessDestination(String user, String destId) {
		if (allowedUserCache.isEmpty()) {
			refresh();
		}
		Set<IAllowedUser> users = allowedUserCache.get(destId); 
		if (users == null) {
			return true;  // There is no restriction on this destination.
		}
		// There is an access control list for this destination, so check the sender and verify that
		// they have not been disabled.
		return users.stream().anyMatch(au -> AccessControlModelHelper.commonNameMatches(user, au.getPrincipal()) && au.isEnabled());
	}

	@Override
	public IDenyListRecord unblock(String user) {
		if (denyListRecordCache.isEmpty()) {
			refresh();
		}
		IDenyListRecord record = denyListRecordCache.get(user);
		if (record != null) {
			record.setUpdated();
			this.accessControlService.denyListRecordRepository.delete(record);
		}
		return record;
	}

	@Override
	public Object block(String user) {
		IDenyListRecord record = this.accessControlService.denyListRecordRepository.createEntity();
		record.setPrincipal(user);
		record.setEnvironment(SystemUtils.getDestType());
		record.setCreatedBy(RequestContext.getPrincipal().getName());
		return this.accessControlService.denyListRecordRepository.store(record);
	}

	@Override
	public Set<String> getDenyList() {
		return this.accessControlService.newModelHelper.denyListRecordCache.keySet();
	}
}