package gov.cdc.izgateway.hub.service.accesscontrol;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import gov.cdc.izgateway.ads.ADSUtils;
import gov.cdc.izgateway.dynamodb.model.AccessGroup;
import gov.cdc.izgateway.dynamodb.model.AllowedUser;
import gov.cdc.izgateway.dynamodb.model.DenyListRecord;
import gov.cdc.izgateway.dynamodb.model.FileType;
import gov.cdc.izgateway.dynamodb.model.SourceAttackExceptionRecord;
import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.model.IAccessGroup;
import gov.cdc.izgateway.model.IDenyListRecord;
import gov.cdc.izgateway.model.IFileType;
import gov.cdc.izgateway.repository.IRepository;
import gov.cdc.izgateway.utils.SystemUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.ArrayList;

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

	private Map<String, AccessGroup> accessGroupCache = Collections.emptyMap();
	private Map<String, DenyListRecord> denyListRecordCache = Collections.emptyMap();
	Map<String, FileType> fileTypeCache = new TreeMap<>();
	private Map<String, Set<AllowedUser>> allowedUserCache = Collections.emptyMap();
	// Package-private and pre-populated with a mutable map (not Collections.emptyMap()), like
	// fileTypeCache above, so a stub subclass in the same package can pre-populate it directly in
	// unit tests without requiring DynamoDB (IGDD-2805).
	Map<String, SourceAttackExceptionRecord> sourceAttackExceptionCache = new TreeMap<>();

	@Override
	public void refresh() {
    	accessGroupCache = refreshCache(accessControlService.accessGroupRepository, ag -> ag.getGroupName());
    	denyListRecordCache = refreshCache(accessControlService.denyListRecordRepository, dr -> dr.getPrincipal());
    	fileTypeCache = refreshCache(accessControlService.fileTypeRepository, FileType::getFileTypeName);
    	sourceAttackExceptionCache = refreshCache(accessControlService.sourceAttackExceptionRepository, SourceAttackExceptionRecord::getSender);
    	Map<String, Set<AllowedUser>> newAllowedUserCache = new TreeMap<>();
    	for (AllowedUser user : refreshCache(accessControlService.allowedUserRepository, au -> au.getDestinationId()).values()) {
    		newAllowedUserCache.computeIfAbsent(
    			user.getDestinationId(), 
    			k -> new TreeSet<>()
    		).add(user);
    	}
    	allowedUserCache = newAllowedUserCache;
	}
	
	<T> Map<String, T> refreshCache(IRepository<T> repo, Function<T, String> nameFunction) {
		TreeMap<String, T> cache = new TreeMap<>();
		repo.findAllForEnvironment().stream().forEach(r -> cache.put(nameFunction.apply(r), r));
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

	/**
	 * Look up a FileType by report type name using a three-tier match:
	 * <ol>
	 *   <li>Exact match against the cached {@code fileTypeName}.</li>
	 *   <li>Case-insensitive match (handles casing variants such as
	 *       {@code "ROUTINEIMMUNIZATION"} → {@code "routineImmunization"}).</li>
	 *   <li>Noise-word stripped match — removes {@code "vaccination"},
	 *       {@code "immunization"}, and {@code "prevention"} from both the
	 *       submitted value and each registry key before comparing.  This
	 *       allows legacy submission values such as {@code "farmerFlu"} to
	 *       match the canonical entry {@code "farmerFluVaccination"} and
	 *       preserves backward compatibility.</li>
	 * </ol>
	 *
	 * @param reportType the report type name to look up
	 * @return the matching FileType, or {@code null} if not found or input is blank
	 */
	IFileType getFileType(String reportType) {
		if (reportType == null || reportType.isBlank()) {
			return null;
		}
		if (fileTypeCache.isEmpty()) {
			refresh();
		}
		// Tier 1: exact match
		FileType exact = fileTypeCache.get(reportType);
		if (exact != null) {
			return exact;
		}
		// Tier 2: case-insensitive scan
		FileType caseInsensitive = fileTypeCache.entrySet().stream()
				.filter(e -> e.getKey().equalsIgnoreCase(reportType))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(null);
		if (caseInsensitive != null) {
			return caseInsensitive;
		}
		// Tier 3: noise-word stripped match for backward compatibility
		// (e.g. "farmerFlu" matches "farmerFluVaccination")
		String strippedInput = ADSUtils.stripNoiseWords(reportType);
		return fileTypeCache.entrySet().stream()
				.filter(e -> ADSUtils.stripNoiseWords(e.getKey()).equals(strippedInput))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(null);
	}

	@Override
	public boolean isUserInRole(String user, String role) {
		return accessGroupCache.values().stream()
			.filter(g -> g.getRoles().contains(role))
			.anyMatch(g -> isUserInGroup(user, g));
	}
	
	@Override
	public boolean isUserInGroup(String user, String group) {
		IAccessGroup accessGroup = accessGroupCache.get(group);
		if (accessGroup == null) {
			log.warn("Group {} does not exist", group);
			return false;
		}
		return isUserInGroup(user, accessGroup);
	}

	private boolean isUserInGroup(String user, IAccessGroup accessGroup) {
		Set<String> users = accessGroup.getUsers();
		if (users.contains(user) || users.contains("*")) {
			return true;
		}
		
		return accessGroup.getGroups().stream().anyMatch(g -> isUserInGroup(user, g));
	}
	
	@Override
	public Map<String, Object> getGroups() {
		return Collections.unmodifiableMap(accessGroupCache);
	}

	@Override
	public boolean canAccessDestination(String user, String destId) {
		if (allowedUserCache.isEmpty()) {
			refresh();
		}
		Set<AllowedUser> users = allowedUserCache.get(destId); 
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
		DenyListRecord dlr = denyListRecordCache.get(user);
		if (dlr != null) {
			dlr.setUpdated();
			accessControlService.denyListRecordRepository.delete(dlr);
		}
		denyListRecordCache.remove(user);
		return dlr;
	}

	@Override
	public IDenyListRecord block(String user, String reason) {
		return block(user, reason, RequestContext.getPrincipal().getName());
	}

	@Override
	public IDenyListRecord block(String user, String reason, String createdBy) {
		if (denyListRecordCache.isEmpty()) {
			refresh();
		}
		DenyListRecord dlr = denyListRecordCache.get(user);
		if (dlr != null) {
			// Already blocked
			return dlr;
		}
		dlr = accessControlService.denyListRecordRepository.createEntity();
		dlr.setPrincipal(user);
		dlr.setEnvironment(SystemUtils.getDestType());
		dlr.setReason(reason);
		dlr.setCreatedBy(createdBy);
		dlr = accessControlService.denyListRecordRepository.store(dlr);
		denyListRecordCache.put(user, dlr);
		return dlr;
	}

	@Override
	public Set<String> getDenyList() {
		return denyListRecordCache.keySet();
	}

	@Override
	public boolean isExemptFromSourceAttackLockout(String sender) {
		if (sourceAttackExceptionCache.isEmpty()) {
			refresh();
		}
		return sourceAttackExceptionCache.containsKey(sender);
	}

	/**
	 * Create (or replace) a source-attack exception record for a sender (IGDD-2805).
	 * @param sender	The sender's common name
	 * @param reason	Operator justification for the exception
	 * @return	The stored exception record
	 */
	SourceAttackExceptionRecord addSourceAttackException(String sender, String reason) {
		if (sourceAttackExceptionCache.isEmpty()) {
			refresh();
		}
		SourceAttackExceptionRecord record = accessControlService.sourceAttackExceptionRepository.createEntity();
		record.setSender(sender);
		record.setReason(reason);
		record.setEnvironment(SystemUtils.getDestType());
		record.setCreatedBy(RequestContext.getPrincipal().getName());
		record = accessControlService.sourceAttackExceptionRepository.store(record);
		sourceAttackExceptionCache.put(sender, record);
		return record;
	}

	/**
	 * Remove a sender's source-attack exception, if any (IGDD-2805).
	 * @param sender	The sender's common name
	 */
	void removeSourceAttackException(String sender) {
		if (sourceAttackExceptionCache.isEmpty()) {
			refresh();
		}
		SourceAttackExceptionRecord record = sourceAttackExceptionCache.get(sender);
		if (record != null) {
			accessControlService.sourceAttackExceptionRepository.delete(record);
		}
		sourceAttackExceptionCache.remove(sender);
	}

	/**
	 * List all configured source-attack exceptions (IGDD-2805).
	 * @return	The current exception records
	 */
	List<SourceAttackExceptionRecord> getSourceAttackExceptions() {
		return new ArrayList<>(sourceAttackExceptionCache.values());
	}
}