package gov.cdc.izgateway.hub;

import gov.cdc.izgateway.ads.ADSController;
import gov.cdc.izgateway.configuration.SenderConfig;
import gov.cdc.izgateway.hub.service.AccessControlService;
import gov.cdc.izgateway.hub.service.DestinationService;
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
import gov.cdc.izgateway.soap.message.*;
import gov.cdc.izgateway.soap.net.MessageSender;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Base controller class that contains common functionality shared between HubWSDLController and CDCWSDLController.
 */
public abstract class BaseGatewayController extends SoapControllerBase {

    /**
     * The destination service used to obtain routing information.
     */
    protected final DestinationService destinationService;
    protected final EndpointStatusService endpointStatusService;
    protected final MessageSender messageSender;
    protected final ADSController adsController;
    protected final AccessControlService accessControlService;

    @Value("${server.hostname:dev.izgateway.org}")
    protected String serverName;
    @Value("${server.port:8080}")
    protected int serverPort;
    @Value("${server.protocol:https}")
    protected String serverProtocol;

    protected BaseGatewayController(
            IMessageHeaderService mshService,
            String baseNamespace,
            String wsdlResource,
            List<String> supportedNamespaces,
            DestinationService destinationService,
            EndpointStatusService endpointStatusService,
            MessageSender messageSender,
            ADSController adsController,
            AccessControlRegistry registry,
            AccessControlService accessControlService,
            SenderConfig hubConfig
    ) {
        super(mshService, baseNamespace, wsdlResource, supportedNamespaces);
        this.destinationService = destinationService;
        this.endpointStatusService = endpointStatusService;
        this.messageSender = messageSender;
        this.adsController = adsController;
        this.accessControlService = accessControlService;
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
        messageSender.getStatusChecker().updateStatus(s, dest, null);
        return result;
    }

    protected boolean isAdministrator() {
        return RequestContext.getRoles().contains(Roles.ADMIN) && !RequestContext.getRoles().contains(Roles.NOT_ADMIN_HEADER);
    }

    /**
     * Checks to see if a user can send to the destination.
     * The method allows access controls to be set on a destination to allow or prohibit users
     * access to a specific destination.  If no access controls have been set up, then users
     * are simply expected to follow policy, but there is no other enforcement.
     *
     * Checks to see an access control group exists for the specified destination.
     * 1. If none does, simply returns.
     *
     * 2. If the user has the ADMIN role, also returns.
     *
     * 3. Otherwise, checks to see if the user is a member of the group given by the destination name, and if
     * they are, returns.
     *
     * 4. If they are not a member, this method throws a security fault notifying the user that they
     * cannot send messages to the specified group.
     *
     * @throws SecurityFault if the user is not permitted to access the destination.
     */
    protected void checkAccess(String destGroup) throws SecurityFault {
        destGroup = StringUtils.upperCase(destGroup);
        if (!accessControlService.groupExists(destGroup)) {
            return;
        }
        if (isAdministrator()) {
            return;
        }

        if (!accessControlService.isMemberOf(RequestContext.getSourceInfo().getCommonName(), destGroup)) {
            throw SecurityFault.generalSecurity("Source Not Allowed", destGroup, null);
        }
    }

    @Override
    protected ResponseEntity<?> submitSingleMessage(SubmitSingleMessageRequest submitSingleMessage, String destinationId) throws Fault {
    	// The special "timeout" destination is used for testing timeouts.  TODO: Remove this before production deployment.
		if ("timeout".equalsIgnoreCase(destinationId)) {
			// Simulate a timeout by not responding for a while.
			try {
				Thread.sleep(TimeUnit.MINUTES.toMillis(6));
			} catch (InterruptedException e) {
				// OK, we were interrupted
				Thread.currentThread().interrupt();
			}
		}
		
        // Validate HubHeader if needed (subclasses can override this)
        validateHubHeader(submitSingleMessage);

        IDestination dest = getDestination(destinationId);
        logDestination(dest);

        checkAccess(destinationId);
        IEndpointStatus s = endpointStatusService.getEndpointStatus(dest);
        checkMessage(submitSingleMessage);
        SubmitSingleMessageResponse response = messageSender.sendSubmitSingleMessage(dest, submitSingleMessage);
        response.updateAction(isHubWsdl());

        // Allow subclasses to customize response processing
        customizeResponse(response, dest);

        ResponseEntity<?> result = checkResponseEntitySize(new ResponseEntity<>(response, HttpStatus.OK));
        // A good result updates the status.
        messageSender.getStatusChecker().updateStatus(s, dest, null);
        return result;
    }

    /**
     * Template method for validating HubHeader. Subclasses can override to provide specific validation.
     */
    protected void validateHubHeader(SubmitSingleMessageRequest submitSingleMessage) throws Fault {
        // Default implementation does nothing
    }

    /**
     * Template method for customizing the response. Subclasses can override to provide specific customization.
     */
    protected void customizeResponse(SubmitSingleMessageResponse response, IDestination dest) {
        // Default implementation does nothing
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

    protected void logDestination(IDestination routing) {
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
