package gov.cdc.izgateway.hub.service.accesscontrol;

import gov.cdc.izgateway.configuration.AppProperties;
import gov.cdc.izgateway.dynamodb.model.Jurisdiction;
import gov.cdc.izgateway.hub.repository.RepositoryFactory;
import gov.cdc.izgateway.hub.security.ApiKeyPrincipal;
import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.model.IDestination;
import gov.cdc.izgateway.security.CertificatePrincipal;
import gov.cdc.izgateway.service.IAccessControlRegistry;
import gov.cdc.izgateway.service.IDestinationService;
import gov.cdc.izgateway.service.IJurisdictionService;
import gov.cdc.izgateway.soap.fault.SecurityFault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests how {@code AccessControlService.checkAccessToDestination} composes its two independent checks:
 * the source/destination rule (configurable warn-or-deny via {@code hub.access-control.action}) and the
 * API-key use-type rule (always deny, IGDD-3257).
 *
 * <p>The case that motivated these tests: with the default {@code action=warn}, a source violation used
 * to {@code return} early and skip the use-type check entirely, so the two policies were coupled by
 * control flow. Warn mode must suppress its own rule only, not the ones after it.</p>
 *
 * @author Audacious Inquiry
 */
class CheckAccessToDestinationTests {

    private static final String DEST_ID = "az";
    private static final int JURISDICTION_ID = 42;
    private static final String JTI = "018f4e2a-5678-7abc-8def-000000000002";
    private static final String SENDER_CN = "sender.example.gov";

    private IDestinationService destinationService;
    private IJurisdictionService jurisdictionService;
    private AccessControlService service;

    @BeforeAll
    static void bootstrapAppProperties() {
        // Constructing a TransactionData (via RequestContext.init) reads the AppProperties singleton to
        // decide PHI masking. Outside a Spring context nothing has published it, so publish a default —
        // the constructor registers itself as the instance.
        if (AppProperties.getInstance() == null) {
            new AppProperties();
        }
    }

    @BeforeEach
    void setUp() {
        destinationService = mock(IDestinationService.class);
        jurisdictionService = mock(IJurisdictionService.class);
        // A spy so canAccessDestination can be stubbed: the real implementation needs the model helpers
        // built by afterPropertiesSet(), which is out of scope here — the composition is what matters.
        service = spy(new AccessControlService(
                mock(RepositoryFactory.class),
                mock(IAccessControlRegistry.class),
                mock(AccessControlMigrator.class),
                destinationService,
                jurisdictionService));
        // RequestContext supplies the sender common name, the principal, and the TransactionData that
        // records the process error.
        RequestContext.init();
        RequestContext.getSourceInfo().setCommonName(SENDER_CN);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    /** Stub the source/destination rule's outcome. */
    private void sourceAccess(boolean permitted) {
        // any() rather than anyString(): a caller with no resolved common name passes null here, which
        // anyString() would not match — the stub would silently fall through to the real implementation.
        doReturn(permitted).when(service).canAccessDestination(any(), any());
    }

    /** Put an API-key caller with the given useTypes on the request context. */
    private void apiKeyCaller(Set<String> useTypes) {
        RequestContext.setPrincipal(
                new ApiKeyPrincipal("42", JTI, "test.example.gov", "http://localhost:3000", useTypes));
    }

    /** Point DEST_ID at a jurisdiction with the given policy. */
    private void destinationPolicy(Set<String> allowedUseTypes) {
        IDestination dest = mock(IDestination.class);
        when(dest.getJurisdictionId()).thenReturn(JURISDICTION_ID);
        when(destinationService.findByDestId(DEST_ID)).thenReturn(dest);
        Jurisdiction jurisdiction = new Jurisdiction();
        jurisdiction.setJurisdictionId(JURISDICTION_ID);
        jurisdiction.setAllowedUseTypes(allowedUseTypes);
        when(jurisdictionService.getJurisdiction(anyInt())).thenReturn(jurisdiction);
    }

    @Test
    @DisplayName("A warn-only source violation does not suppress the use-type check")
    void sourceViolationInWarnMode_stillEnforcesUseType() {
        service.accessControlAction = "warn";
        sourceAccess(false);
        apiKeyCaller(Set.of("PATIENT"));
        destinationPolicy(Set.of("PROVIDER"));

        SecurityFault fault = assertThrows(SecurityFault.class,
                () -> service.checkAccessToDestination(DEST_ID));

        assertEquals("Use Type Not Allowed", fault.getSummary());
        // The rejection is recorded on the transaction so it shows up in the request log, not just the throw.
        assertTrue(RequestContext.getTransactionData().getHasProcessError());
        assertTrue(RequestContext.getTransactionData().getProcessError().startsWith("Use Type Not Allowed"),
                "process error should record the use-type denial: "
                        + RequestContext.getTransactionData().getProcessError());
    }

    @Test
    @DisplayName("A warn-only source violation still allows a message whose use types are permitted")
    void sourceViolationInWarnMode_useTypeSatisfied_allows() {
        service.accessControlAction = "warn";
        sourceAccess(false);
        apiKeyCaller(Set.of("PROVIDER"));
        destinationPolicy(Set.of("PROVIDER", "PATIENT"));

        assertDoesNotThrow(() -> service.checkAccessToDestination(DEST_ID));
    }

    @Test
    @DisplayName("A denied source check throws its own fault and short-circuits the use-type check")
    void sourceDenied_shortCircuits() {
        service.accessControlAction = "deny";
        sourceAccess(false);
        apiKeyCaller(Set.of("PATIENT"));

        SecurityFault fault = assertThrows(SecurityFault.class,
                () -> service.checkAccessToDestination(DEST_ID));

        assertEquals("Source Not Allowed", fault.getSummary());
        assertTrue(fault.getDetail().contains(SENDER_CN), "should name the sender: " + fault.getDetail());
        // The message is already rejected, so the destination is never resolved for a use-type decision.
        verifyNoInteractions(destinationService);
    }

    @Test
    @DisplayName("A permitted source with disjoint use types is still rejected")
    void sourcePermitted_useTypeViolation_throws() {
        service.accessControlAction = "deny";
        sourceAccess(true);
        apiKeyCaller(Set.of("PATIENT"));
        destinationPolicy(Set.of("PROVIDER"));

        SecurityFault fault = assertThrows(SecurityFault.class,
                () -> service.checkAccessToDestination(DEST_ID));

        assertEquals("Use Type Not Allowed", fault.getSummary());
    }

    @Test
    @DisplayName("A certificate caller is unaffected by the use-type rule, even in warn mode")
    void certificateCaller_skipsUseTypeCheck() {
        service.accessControlAction = "warn";
        sourceAccess(false);
        RequestContext.setPrincipal(new CertificatePrincipal());

        assertDoesNotThrow(() -> service.checkAccessToDestination(DEST_ID));
        // useTypes is a property of an ApiKeyCredential; a cert caller has none, so the rule cannot apply
        // and the destination is never resolved for it.
        verifyNoInteractions(destinationService);
    }

    @Test
    @DisplayName("An API-key caller to an unknown destination is left to the caller's UnknownDestinationFault")
    void unknownDestination_doesNotThrowUseTypeFault() {
        service.accessControlAction = "warn";
        sourceAccess(true);
        apiKeyCaller(Set.of("PATIENT"));
        when(destinationService.findByDestId(DEST_ID)).thenReturn(null);

        assertDoesNotThrow(() -> service.checkAccessToDestination(DEST_ID));
    }
}
