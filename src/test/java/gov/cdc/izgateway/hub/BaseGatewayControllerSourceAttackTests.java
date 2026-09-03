package gov.cdc.izgateway.hub;

import gov.cdc.izgateway.ads.ADSController;
import gov.cdc.izgateway.configuration.AppProperties;
import gov.cdc.izgateway.configuration.SenderConfig;
import gov.cdc.izgateway.hub.service.DestinationService;
import gov.cdc.izgateway.hub.service.accesscontrol.AccessControlService;
import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.logging.info.EndPointInfo;
import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.security.CertificatePrincipal;
import gov.cdc.izgateway.service.IMessageHeaderService;
import gov.cdc.izgateway.service.impl.EndpointStatusService;
import gov.cdc.izgateway.soap.fault.Fault;
import gov.cdc.izgateway.soap.fault.SecurityFault;
import gov.cdc.izgateway.soap.net.MessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit-tests {@code BaseGatewayController.handleFault} — the source-attack lockout hook (IGDD-2805).
 * No Spring context needed: this exercises the discrimination logic (fault code + endpoint) directly.
 */
class BaseGatewayControllerSourceAttackTests {

    private static final String SENDER = "sender.example.gov";
    private static final String DEST = "az";

    private AccessControlService accessControlService;
    private BaseGatewayController controller;

    @BeforeAll
    static void bootstrapAppProperties() {
        if (AppProperties.getInstance() == null) {
            new AppProperties();
        }
    }

    @BeforeEach
    void setUp() {
        accessControlService = mock(AccessControlService.class);
        controller = new BaseGatewayController(
                mock(IMessageHeaderService.class),
                "urn:test",
                "test.wsdl",
                Collections.singletonList("urn:test"),
                mock(DestinationService.class),
                mock(EndpointStatusService.class),
                mock(MessageSender.class),
                mock(ADSController.class),
                mock(AccessControlRegistry.class),
                accessControlService,
                new SenderConfig()) {
            @Override
            protected boolean isHubWsdl() {
                return true;
            }
        };

        RequestContext.init();
        RequestContext.setPrincipal(new CertificatePrincipal());
        RequestContext.getSourceInfo().setCommonName(SENDER);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("Inbound source attack (code 61, null endpoint) triggers the lockout handler")
    void inboundSourceAttack_triggersLockoutHandler() {
        SecurityFault fault = SecurityFault.sourceAttack("Illegal text value in HL7Message", null);

        controller.handleFault(fault);

        verify(accessControlService, times(1)).handleSourceAttack(eq(SENDER), any());
    }

    @Test
    @DisplayName("User-blacklisted fault (code 62) does not trigger the lockout handler")
    void userBlacklisted_doesNotTriggerLockoutHandler() {
        EndPointInfo endpoint = mock(EndPointInfo.class);
        Fault fault = SecurityFault.userBlacklisted(endpoint);

        controller.handleFault(fault);

        verify(accessControlService, never()).handleSourceAttack(any(), any());
    }

    @Test
    @DisplayName("General security fault (code 60) does not trigger the lockout handler")
    void generalSecurity_doesNotTriggerLockoutHandler() {
        Fault fault = SecurityFault.generalSecurity("Source Not Allowed", "detail", null);

        controller.handleFault(fault);

        verify(accessControlService, never()).handleSourceAttack(any(), any());
    }

    @Test
    @DisplayName("Outbound-shaped source attack (code 61, non-null endpoint) does NOT trigger the lockout handler")
    void outboundSourceAttack_doesNotTriggerLockout() {
        // Non-null endpoint is the shape MessageSender's outbound destination-response scan produces
        // (RequestContext.getDestinationInfo(), not null). Without the endpoint == null guard in
        // handleFault, this would wrongfully deny-list the original (innocent) sender.
        EndPointInfo destinationEndpoint = mock(EndPointInfo.class);
        SecurityFault fault = SecurityFault.sourceAttack("Illegal text value in response", destinationEndpoint);

        controller.handleFault(fault);

        verify(accessControlService, never()).handleSourceAttack(any(), any());
    }
}
