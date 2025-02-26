package gov.cdc.izgateway.repository;

import gov.cdc.izgateway.model.IAccessControl;

/**
 * The IAccessControlRepository interface supports access to access control
 * information.
 *  
 * @author Audacious Inquiry
 */
public interface IAccessControlRepository extends IRepository<IAccessControl> {
	void delete(IAccessControl iAccessControl);
	IAccessControl addUserToGroup(String user, String group);
	IAccessControl removeUserFromGroup(String user, String group);
}
