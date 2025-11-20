package gov.cdc.izgateway.hub;

import gov.cdc.izgateway.ads.ADSController;
import gov.cdc.izgateway.configuration.SenderConfig;
import gov.cdc.izgateway.hub.service.DestinationService;
import gov.cdc.izgateway.hub.service.accesscontrol.AccessControlService;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.service.IMessageHeaderService;
import gov.cdc.izgateway.service.impl.EndpointStatusService;
import gov.cdc.izgateway.soap.fault.Fault;
import gov.cdc.izgateway.soap.fault.UnsupportedOperationFault;
import gov.cdc.izgateway.soap.message.*;
import gov.cdc.izgateway.soap.net.MessageSender;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

@RestController
@RolesAllowed({Roles.SOAP, Roles.ADMIN})
@RequestMapping("/izgw")
@Lazy(false)
public class CDCWSDLController extends BaseGatewayController {

	@Autowired
	public CDCWSDLController(
		IMessageHeaderService mshService,
		DestinationService destinationService,
		EndpointStatusService endpointStatusService,
		MessageSender messageSender,
		ADSController adsController,
		AccessControlRegistry registry,
		AccessControlService accessControlService,
		SenderConfig hubConfig
	) {
		super(mshService, SoapMessage.IIS2011_NS, "cdc-iis-2011.wsdl", Arrays.asList(SoapMessage.IIS2014_NS),
			destinationService, endpointStatusService, messageSender, adsController, registry, accessControlService, hubConfig);
	}

    @Override
    protected boolean isHubWsdl() {
        // IIS Controller is never to be considered Hub
        return false;
    }

	@Override
	protected void validateHubHeader(SubmitSingleMessageRequest submitSingleMessage) throws Fault {
		if (submitSingleMessage.getHubHeader() != null && !submitSingleMessage.getHubHeader().isEmpty()) {
            throw new UnsupportedOperationFault("IZGW-specific HubHeader is not allowed in CDC WSDL requests.", null);
		}
	}

	@Override
	protected void customizeResponse(SubmitSingleMessageResponse response, IDestination dest) {
        // No customization needed as this is not a Hub response (no hub headers to be set)
	}

	/**
	 * Handle SOAP requests with destinationId as a path parameter
	 * This allows requests to be made to /izgw/{destinationId}
	 */
	@PostMapping(value = "/{destinationId}", produces = {
		"application/soap+xml",
		"application/soap",
		MediaType.APPLICATION_XML_VALUE,
		MediaType.TEXT_XML_VALUE,
		MediaType.TEXT_PLAIN_VALUE,
		MediaType.TEXT_HTML_VALUE
	})
	public ResponseEntity<?> submitSoapRequestWithDestination(
		@RequestBody SoapMessage soapMessage,
		@PathVariable String destinationId
	) throws Fault {
		if (soapMessage.getWsaHeaders() != null) {
			soapMessage.getWsaHeaders().setTo(destinationId);
		}

		return submitSoapRequest(soapMessage, null);
	}
}
