package gov.cdc.izgateway.hub;

import gov.cdc.izgateway.ads.ADSController;
import gov.cdc.izgateway.configuration.AppProperties;
import gov.cdc.izgateway.configuration.SenderConfig;
import gov.cdc.izgateway.hub.service.DestinationService;
import gov.cdc.izgateway.hub.service.accesscontrol.AccessControlService;
import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.security.CertificatePrincipal;
import gov.cdc.izgateway.service.IMessageHeaderService;
import gov.cdc.izgateway.service.impl.EndpointStatusService;
import gov.cdc.izgateway.soap.net.MessageSender;
import gov.cdc.izgateway.soap.net.SoapMessageConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Proves the full exception-handling chain for a real inbound source-attack detection (IGDD-2805):
 * Spring MVC argument resolution runs the real {@code SoapMessageConverter}/{@code SoapMessageReader}
 * (izgw-core), which throws a {@code SoapConversionException} wrapping
 * {@code SecurityFault.sourceAttack}; that must be caught by {@code SoapControllerBase}'s
 * controller-local {@code @ExceptionHandler(SoapConversionException.class)}
 * ({@code handleBadXML}) — not the global {@code ExceptionHandling} {@code @ControllerAdvice} — and
 * dispatch virtually into the overridden {@code handleFault}.
 * <p>
 * Uses {@code MockMvcBuilders.standaloneSetup}, not a full {@code @SpringBootTest}: this repo's SOAP
 * flows are otherwise only exercised via Newman/Postman against a deployed environment, and a full
 * Spring context needs a real DynamoDB. Registering {@code ExceptionHandling} as controller advice here
 * is what makes the "controller-local handler wins over the global advice" precedence a real assertion,
 * not a trivially-true one (standalone setup does not register advice unless asked).
 * </p>
 */
class HubWSDLControllerSourceAttackTests {

    private static final String SENDER = "VHA.example.gov";

    // Adapted from testing/scripts/IZGW_2.0_Integration_Test.postman_collection.json's
    // "Send correctly formatted SOAP Request to the CDC WSDL endpoint" example, with the HL7 content
    // replaced by a value containing "javascript" — the exact false-positive pattern from the ticket
    // (a patient name containing the word "javascript").
    private static final String ATTACK_PAYLOAD =
            "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" "
            + "xmlns:urn=\"urn:cdc:iisb:hub:2014\" xmlns:urn1=\"urn:cdc:iisb:2014\">"
            + "<soap:Header xmlns:wsa=\"http://www.w3.org/2005/08/addressing\">"
            + "<urn:HubRequestHeader><urn:DestinationId>dev</urn:DestinationId></urn:HubRequestHeader>"
            + "<wsa:Action>urn:cdc:iisb:hub:2014:IISHubPortType:SubmitSingleMessageRequest</wsa:Action>"
            + "<wsa:MessageID>TEST-IGDD-2805</wsa:MessageID>"
            + "</soap:Header>"
            + "<soap:Body>"
            + "<urn1:SubmitSingleMessageRequest>"
            + "<urn1:FacilityID>IZG</urn1:FacilityID>"
            + "<urn1:Hl7Message>MSH|^~\\&amp;|APP|FAC|APP|FAC|20260101||VXU^V04|1|P|2.5.1\r"
            + "PID|1||12345^^^MYEHR^MR||javascript^Patient^Test\r</urn1:Hl7Message>"
            + "</urn1:SubmitSingleMessageRequest>"
            + "</soap:Body>"
            + "</soap:Envelope>";

    private AccessControlService accessControlService;
    private MockMvc mockMvc;

    @BeforeAll
    static void bootstrapAppProperties() {
        if (AppProperties.getInstance() == null) {
            new AppProperties();
        }
    }

    @BeforeEach
    void setUp() {
        accessControlService = mock(AccessControlService.class);
        HubWSDLController controller = new HubWSDLController(
                mock(IMessageHeaderService.class),
                mock(DestinationService.class),
                mock(EndpointStatusService.class),
                mock(MessageSender.class),
                mock(ADSController.class),
                mock(AccessControlRegistry.class),
                accessControlService,
                new SenderConfig());

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new SoapMessageConverter(SoapMessageConverter.INBOUND))
                .setControllerAdvice(new ExceptionHandling())
                .build();

        RequestContext.init();
        RequestContext.setPrincipal(new CertificatePrincipal());
        RequestContext.getSourceInfo().setCommonName(SENDER);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("A source attack detected during request parsing reaches handleFault, not just the global advice")
    void inboundSourceAttack_reachesHandleFault() throws Exception {
        mockMvc.perform(post("/IISHubService")
                .contentType(MediaType.APPLICATION_XML)
                .content(ATTACK_PAYLOAD));

        // If SoapControllerBase's controller-local @ExceptionHandler(SoapConversionException.class)
        // did not win over the global ExceptionHandling advice, or if handleFault's override were not
        // reached via virtual dispatch, this would never be called.
        verify(accessControlService).handleSourceAttack(any(), any());
    }
}
