package gov.cdc.izgateway.hub.repository;

import gov.cdc.izgateway.model.IAccessControl;
import gov.cdc.izgateway.repository.IRepository;

/**
 * The IAccessControlRepository interface supports access to access control
 * information.
 * 
 * @param <T> The type of access control
 *  
 * @author Audacious Inquiry
 */
public interface IAccessControlRepository<T extends IAccessControl> extends IRepository<T> {
	/**
	 * Delete the given access control.
	 * @param iAccessControl	The access control to delete
	 */
	void delete(T iAccessControl);
	/**
	 * Add the given user to the given group.
	 * @param user The user
	 * @param group The group
	 * @return The access control created
	 */
	IAccessControl addUserToGroup(String user, String group);
	/**
	 * Remove the given user from the given group.
	 * @param user The user
	 * @param group The group
	 * @return The access control removed
	 */
	IAccessControl removeUserFromGroup(String user, String group);
}
