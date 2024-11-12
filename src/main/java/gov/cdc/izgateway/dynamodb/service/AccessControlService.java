package gov.cdc.izgateway.dynamodb.service;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMethod;

import gov.cdc.izgateway.db.repository.AccessControlRepository;
import gov.cdc.izgateway.model.IAccessControl;
import gov.cdc.izgateway.service.IAccessControlRegistry;
import gov.cdc.izgateway.service.IAccessControlService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Implements the IAccessControlService for IZ Gateway.
 * 
 * @author Audacious Inquiry
 */
@Slf4j
@Service
public class AccessControlService implements InitializingBean, IAccessControlService {
	public AccessControlService(AccessControlRepository accessControlRepository, IAccessControlRegistry registry) {
	}

	@Override
	public String getServerName() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void afterPropertiesSet() {
	}

	@Override
	public void refresh() {
	}

	public boolean groupExists(String groupName) {
		return false;
	}

	@Override
	public Map<String, TreeSet<String>> getUserRoles() {
		return null;
	}

	public boolean userInGroup(String user, String group) {
		return false;
	}

	@Override
	public Map<String, Map<String, Boolean>> getAllowedUsersByGroup() {
		return null;
	}

	@Override
	public Map<String, Map<String, Boolean>> getAllowedRoutesByEvent() {
		return null;
	}

	@Override
	public boolean isUserInRole(String user, String role) {
		return false;
	}

	@Override
	public boolean isUserBlacklisted(String user) {
		return false;
	}

	@Override
	public Map<String, Boolean> getEventMap(String event) {
		return null;
	}

	@Override
	public Set<String> getEventTypes() {
		return null;
	}

	@Override
	public boolean isRouteAllowed(String route, String event) {
		return false;
	}

	@Override
	public void setServerName(String serverName) {
	}

	@Override
	public List<String> getAllowedRoles(RequestMethod method, String path) {
		return null;
	}

	@Override
	public Boolean checkAccess(String user, String method, String path) {
		return null;
	}

	@Override
	public boolean isMemberOf(String user, String group) {
		return false;
	}

	@Override
	public IAccessControl removeUserFromBlacklist(String user) {
		return null;
	}

	@Override
	public IAccessControl addUserToBlacklist(String user) {
		return null;
	}
}
