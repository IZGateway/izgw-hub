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

@RestController
@RolesAllowed({Roles.SOAP, Roles.ADMIN})
@RequestMapping("/dev/IISService")
@Lazy(false)
public class MockController2014 extends MockControllerBase  {
	@Autowired
	public MockController2014(IMessageHeaderService mshService, AccessControlRegistry registry) {
		super(mshService, SoapMessage.IIS2014_NS, "cdc-iis.wsdl", registry);
	}
}
