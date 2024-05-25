package gov.cdc.izgateway.hub;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import gov.cdc.izgateway.ads.ADSController;
import gov.cdc.izgateway.configuration.SenderConfig;
import gov.cdc.izgateway.db.service.DestinationService;
import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.logging.info.DestinationInfo;
import gov.cdc.izgateway.logging.info.HostInfo;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.model.IEndpointStatus;
import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.security.Roles;
import gov.cdc.izgateway.service.IMessageHeaderService;
import gov.cdc.izgateway.service.impl.EndpointStatusService;
import gov.cdc.izgateway.soap.SoapControllerBase;
import gov.cdc.izgateway.soap.fault.Fault;
import gov.cdc.izgateway.soap.fault.SecurityFault;
import gov.cdc.izgateway.soap.fault.UnknownDestinationFault;
import gov.cdc.izgateway.soap.message.ConnectivityTestRequest;
import gov.cdc.izgateway.soap.message.ConnectivityTestResponse;
import gov.cdc.izgateway.soap.message.HasCredentials;
import gov.cdc.izgateway.soap.message.SoapMessage;
import gov.cdc.izgateway.soap.message.SubmitSingleMessageRequest;
import gov.cdc.izgateway.soap.message.SubmitSingleMessageResponse;
import gov.cdc.izgateway.soap.net.MessageSender;
import jakarta.annotation.security.RolesAllowed;

@RestController
@RolesAllowed({Roles.SOAP, Roles.ADMIN})
@RequestMapping("/IISHubService")
@Lazy(false)
public class HubController extends SoapControllerBase {

	/**
	 * The destination service used to obtain routing information.
	 */
	private final DestinationService destinationService;
	private final EndpointStatusService endpointStatusService; 
	private final MessageSender messageSender;
	private final ADSController adsController;
	
	@Value("${server.hostname:dev.izgateway.org}")
	private String serverName;
	@Value("${server.port:8080}")
	private int serverPort;
	@Value("${server.protocol:https}")
	private String serverProtocol;
	
	@Autowired 
	public HubController(
		IMessageHeaderService mshService,
		DestinationService destinationService,
		EndpointStatusService endpointStatusService,
		MessageSender messageSender,
		ADSController adsController,
		AccessControlRegistry registry,
		SenderConfig hubConfig
	) {
		// The base schema for HUB messages is still the iis-2014 schema, with the exception of HubHeader and certain faults.
		super(mshService, SoapMessage.IIS2014_NS, "cdc-iis-hub.wsdl", Arrays.asList(SoapMessage.HUB_NS, SoapMessage.IIS2014_NS));
		this.destinationService = destinationService;
		this.endpointStatusService = endpointStatusService;
		this.messageSender = messageSender;
		this.adsController = adsController;
		setMaxMessageSize(hubConfig.getMaxMessageSize());
		registry.register(this);
	}
	
	@Override
	protected DestinationService getDestinationService() {
		return destinationService;
	}
	
	@Override
	public String getServiceType() {
		return "Gateway";
	}

	@Override
	protected ResponseEntity<?> connectivityTest(ConnectivityTestRequest connectivityTest, String destinationId) throws Fault {
		if (StringUtils.isEmpty(destinationId)) {
			return super.connectivityTest(connectivityTest, destinationId);
		}
		IDestination dest = getDestination(destinationId);
		
		logDestination(dest);
		
		IEndpointStatus s = endpointStatusService.getEndpointStatus(dest);
		boolean wasCircuitBreakerThrown = s.isCircuitBreakerThrown();
		
		ResponseEntity<?> result = null;
		if (dest.isDex()) {
			// Do a status check on DEX Endpoint.
			adsController.getDestinationStatus(connectivityTest.getWsaHeaders().getMessageID(), null, null, destinationId);
			result = super.connectivityTest(connectivityTest, destinationId);
		} else {
			ConnectivityTestResponse response = messageSender.sendConnectivityTest(dest, connectivityTest);
			response.setSchema(SoapMessage.HUB_NS);  // Shift from client to Hub Schema
			result = checkResponseEntitySize(new ResponseEntity<>(response, HttpStatus.OK));
		}
		
		// We got a good result, update status
		s.setStatus(IEndpointStatus.CONNECTED);
		messageSender.getStatusChecker().updateStatus(s, wasCircuitBreakerThrown, null);
		return result;
	}
	
	@Override
	protected ResponseEntity<?> submitSingleMessage(SubmitSingleMessageRequest submitSingleMessage, String destinationId) throws Fault {
		IDestination dest = getDestination(destinationId);
		logDestination(dest);
		
		IEndpointStatus s = endpointStatusService.getEndpointStatus(dest);
		boolean wasCircuitBreakerThrown = s.isCircuitBreakerThrown();

		checkMessage(submitSingleMessage);
		SubmitSingleMessageResponse response = messageSender.sendSubmitSingleMessage(dest, submitSingleMessage);
		response.setSchema(SoapMessage.HUB_NS);	// Shift from client to Hub Schema
		response.getHubHeader().setDestinationId(dest.getDestId());
		String uri = dest.getDestinationUri();
		if (uri.startsWith("/")) {
			uri = String.format("%s://%s:%d%s", serverProtocol, serverName, serverPort, uri);
		}
		response.getHubHeader().setDestinationUri(uri);
		ResponseEntity<?> result = checkResponseEntitySize(new ResponseEntity<>(response, HttpStatus.OK));
		// A good result updates the status.
		messageSender.getStatusChecker().updateStatus(s, wasCircuitBreakerThrown, null);
		return result;
	}
	
	@Override
	protected IDestination getDestination(String destinationId) throws UnknownDestinationFault {
		if ("".equals(destinationId)) {
			throw UnknownDestinationFault.invalidDestination(destinationId, "Request has no destination value in the DestinationId element");
		} else if (null == destinationId) {
			throw UnknownDestinationFault.missingDestination("Request is missing the DestinationId element");
		} else if (!destinationId.matches(IDestination.ID_PATTERN)) {
			RequestContext.getDestinationInfo().setId(destinationId);
			throw UnknownDestinationFault.invalidDestination(destinationId, "Destination " + destinationId + " is invalid.");
		}
		IDestination dest = destinationService.findByDestId(destinationId);
		if (dest == null) {
			RequestContext.getDestinationInfo().setId(destinationId);
			throw UnknownDestinationFault.unknownDestination(destinationId, destinationId, null);
		}
		logDestination(dest);
		return dest;
	}

	private void logDestination(IDestination routing) {
        DestinationInfo destination = RequestContext.getDestinationInfo();
        destination.setId(routing.getDestId());
        
        String wsdlVersion = routing.getDestVersion();
        if (!StringUtils.isBlank(wsdlVersion)) {
        	destination.setProtocol(wsdlVersion);
        }
        
        String url = routing.getDestUri();
        // Destination logging uses the local url for diagnostic use.
        // This is only visible in logs.
        destination.setUrl(destinationService.localUrl(url));
        if (routing.getDestUri().startsWith("/")) {
        	destination.setIpAddress(HostInfo.LOCALHOST_IP4);
        	destination.setHost(HostInfo.LOCALHOST);
        	return;
        }

        String host = destinationService.serverOf(url);
        destination.setHost(host);
        InetAddress address;
		try {
			address = InetAddress.getByName(host);
            destination.setIpAddress(address.getHostAddress());
		} catch (UnknownHostException e) {
			destination.setAddressUnknown();
		}
    }

	@Override
	protected void checkCredentials(HasCredentials s) throws SecurityFault {
		// Hub Controller performs no credential checking. Credentials are not supplied in SOAP message, instead they 
		// are supplied in client certificate in TLS Connection/
	}
}