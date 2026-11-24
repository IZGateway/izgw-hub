package gov.cdc.izgateway.soap.mock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.service.IMessageHeaderService;
import gov.cdc.izgateway.soap.message.SoapMessage;
import jakarta.annotation.security.RolesAllowed;

/**
 * The mock IIS endpoint at {@code /dev/client_Service} that uses the CDC 2011 WSDL.
 */
@RestController
@RolesAllowed({Roles.SOAP, Roles.ADMIN})
@RequestMapping("/dev/client_Service")
@Lazy(false)
public class MockController2011 extends MockControllerBase {
	/**
	 * Make the controller and register it for access control.
	 * @param mshService The service that gets the expected credentials for a message header.
	 * @param registry The access control registry.
	 */
	@Autowired 
	public MockController2011(IMessageHeaderService mshService, AccessControlRegistry registry) {
		super(mshService, SoapMessage.IIS2011_NS, "cdc-iis-2011.wsdl", registry);
	}
}
