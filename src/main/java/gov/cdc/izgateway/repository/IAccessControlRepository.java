package gov.cdc.izgateway.repository;

import java.util.List;
import java.util.Optional;

import gov.cdc.izgateway.model.IAccessControl;
import gov.cdc.izgateway.model.IAccessControlId;

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
