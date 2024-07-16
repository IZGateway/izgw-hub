package gov.cdc.izgateway.soap.mock;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.service.IMessageHeaderService;
import gov.cdc.izgateway.soap.SoapControllerBase;
import gov.cdc.izgateway.soap.fault.Fault;
import gov.cdc.izgateway.soap.fault.SecurityFault;
import gov.cdc.izgateway.soap.message.HasCredentials;
import gov.cdc.izgateway.soap.message.SubmitSingleMessageRequest;
import gov.cdc.izgateway.soap.mock.perf.PerformanceSimulatorInterface;
import gov.cdc.izgateway.soap.mock.perf.PerformanceSimulatorMultiton;

public class MockControllerBase extends SoapControllerBase implements InitializingBean {
	@Value("${iis.max-message-size:65536}")
	private int iisMaxMesageSize;

	protected MockControllerBase(IMessageHeaderService mshService, String messageNamespace, String wsdl,
			AccessControlRegistry registry) {
		super(mshService, messageNamespace, wsdl, null);
		if (registry != null) {
			registry.register(this);
		}
	}

	public void afterPropertiesSet() {
		super.setMaxMessageSize(iisMaxMesageSize);
	}

	@Override
	protected void checkCredentials(HasCredentials s) throws SecurityFault {
		MockCredentialValidator.checkCredentials(mshService, s);
	}

	@Override
	protected ResponseEntity<?> submitSingleMessage( // NOSONAR ? is intentional
		SubmitSingleMessageRequest submitSingleMessage,
		String destinationId
	) throws Fault {
		PerformanceSimulatorInterface p = PerformanceSimulatorMultiton.getInstance(submitSingleMessage.findTestCaseIdentifier()); 
		if (p != null) {
			ResponseEntity<?> r = p.simulateError(RequestContext.getResponse()); 
			if (r != null) {
				return r;
			}
			return new ResponseEntity<>(p.getResponse(submitSingleMessage), HttpStatus.OK);
		}
		return super.submitSingleMessage(submitSingleMessage, destinationId);
	}
}
