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
	
	Set<String> getEventTypes();

	Object unblock(String user);

	Object block(String user);

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