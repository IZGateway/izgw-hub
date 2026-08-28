package gov.cdc.izgateway.hub.service.accesscontrol;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

interface AccessControlModelHelper {
	void refresh();
	
	Map<String, TreeSet<String>> getUserRoles();
	
	Map<String, Object> getGroups();
	
	boolean isUserInRole(String user, String role);
	

	boolean canAccessDestination(String user, String destId);

	boolean isUserDenied(String user);

	/**
	 * Determine whether a sender is exempt from source-attack auto-lockout (IGDD-2805).
	 * New-model-only: {@code OldModelHelper} always returns {@code false}.
	 * @param sender	The sender's common name
	 * @return true if the sender has a configured source-attack exception
	 */
	boolean isExemptFromSourceAttackLockout(String sender);

	Set<String> getEventTypes();

	Object unblock(String user);

	Object block(String user, String reason);

	/**
	 * Block a user, attributing the action to an explicit actor rather than the caller's own
	 * {@code RequestContext} principal (IGDD-2805). Used by automated lockouts (e.g. source-attack
	 * auto-lockout), where the calling identity is the sender being blocked, not the actor
	 * responsible for the block.
	 * @param user		The user (sender) to block
	 * @param reason	Reason for the block
	 * @param createdBy	The actor to record as having created the block
	 * @return the deny-list record, or model-specific equivalent
	 */
	default Object block(String user, String reason, String createdBy) {
		return block(user, reason);
	}

	Set<String> getDenyList();

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
	public static boolean commonNameMatches(String cn, String pattern) {
		// Most common case, handle it first.
		if (pattern.equals(cn)) {
			return true;
		}
		
	    if ("*".equals(pattern)) {
	        return true;
	    }
	    
	    if (cn == null) {
	        return false;
	    }
	    
	    return pattern.startsWith("*.") && cn.endsWith(pattern.substring(1));
	}

	boolean isUserInGroup(String user, String group);
}