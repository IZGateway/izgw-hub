package gov.cdc.izgateway.hub;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import gov.cdc.izgateway.ads.ADSController;
import gov.cdc.izgateway.configuration.SenderConfig;
import gov.cdc.izgateway.hub.service.AccessControlService;
import gov.cdc.izgateway.hub.service.DestinationService;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.service.IMessageHeaderService;
import gov.cdc.izgateway.service.impl.EndpointStatusService;
import gov.cdc.izgateway.soap.message.SoapMessage;
import gov.cdc.izgateway.soap.message.SubmitSingleMessageResponse;
import gov.cdc.izgateway.soap.net.MessageSender;
import jakarta.annotation.security.RolesAllowed;

@RestController
@RolesAllowed({Roles.SOAP, Roles.ADMIN})
@RequestMapping("/IISHubService")
@Lazy(false)
public class HubWSDLController extends BaseGatewayController {

	@Autowired
	public HubWSDLController(
		IMessageHeaderService mshService,
		DestinationService destinationService,
		EndpointStatusService endpointStatusService,
		MessageSender messageSender,
		ADSController adsController,
		AccessControlRegistry registry,
		AccessControlService accessControlService,
		SenderConfig hubConfig
	) {
		// The base schema for HUB messages is still the iis-2014 schema, with the exception of HubHeader and certain faults.
		super(mshService, SoapMessage.IIS2014_NS, "cdc-iis-hub.wsdl", Arrays.asList(SoapMessage.HUB_NS, SoapMessage.IIS2014_NS),
			destinationService, endpointStatusService, messageSender, adsController, registry, accessControlService, hubConfig);
	}

    @Override
    protected boolean isHubWsdl() {
        return getDestinationService() != null;
    }

	@Override
	protected void customizeResponse(SubmitSingleMessageResponse response, IDestination dest) {
        response.setSchema(SoapMessage.HUB_NS);	// Shift from client to Hub Schema
		response.getHubHeader().setDestinationId(dest.getDestId());
		String uri = dest.getDestinationUri();
		if (uri.startsWith("/")) {
			uri = String.format("%s://%s:%d%s", serverProtocol, serverName, serverPort, uri);
		}
		response.getHubHeader().setDestinationUri(uri);
	}
}