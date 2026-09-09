package gov.cdc.izgateway.hub.service.accesscontrol;

import gov.cdc.izgateway.configuration.AppProperties;
import gov.cdc.izgateway.dynamodb.model.DenyListRecord;
import gov.cdc.izgateway.hub.HubWSDLController;
import gov.cdc.izgateway.hub.repository.IAccessGroupRepository;
import gov.cdc.izgateway.hub.repository.IAllowedUserRepository;
import gov.cdc.izgateway.hub.repository.IDenyListRecordRepository;
import gov.cdc.izgateway.hub.repository.IFileTypeRepository;
import gov.cdc.izgateway.hub.repository.ISourceAttackExceptionRepository;
import gov.cdc.izgateway.hub.repository.RepositoryFactory;
import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.security.CertificatePrincipal;
import gov.cdc.izgateway.service.IDestinationService;
import gov.cdc.izgateway.service.IJurisdictionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves acceptance criterion #1 ("Source Attack Exceptions correctly trigger sender shutout") —
 * IGDD-2805 — at the access-control layer: once a sender is on the deny list, {@code checkAccess}
 * for the real {@code /IISHubService} POST route rejects it.
 * <p>
 * The route's allowed roles are read from a real {@link AccessControlRegistry} registered against the
 * real {@code HubWSDLController.class} (not a hardcoded {@code {SOAP, ADMIN}} assumption), so this test
 * would fail if {@code BLACKLIST_ROLE} were ever added to that controller's {@code @RolesAllowed} — the
 * one thing that would silently defeat the lockout (see design.md "Access Control").
 * </p>
 */
class SourceAttackShutoutTests {

    private static final String SENDER = "VHA.example.gov";

    private AccessControlService service;
    private IDenyListRecordRepository<DenyListRecord> denyListRecordRepository;

    @BeforeAll
    static void bootstrapAppProperties() {
        if (AppProperties.getInstance() == null) {
            new AppProperties();
        }
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        RepositoryFactory factory = mock(RepositoryFactory.class);
        when(factory.accessGroupRepository()).thenReturn(mock(IAccessGroupRepository.class));
        when(factory.fileTypeRepository()).thenReturn(mock(IFileTypeRepository.class));
        when(factory.allowedUserRepository()).thenReturn(mock(IAllowedUserRepository.class));
        when(factory.sourceAttackExceptionRepository()).thenReturn(mock(ISourceAttackExceptionRepository.class));

        // Behaves like a tiny fake so the finally { refresh(); } inside addUserToDenyList sees the
        // record it just stored, same reasoning as AccessControlServiceSourceAttackTests.
        denyListRecordRepository = mock(IDenyListRecordRepository.class);
        List<DenyListRecord> backingStore = new ArrayList<>();
        when(denyListRecordRepository.createEntity()).thenReturn(new DenyListRecord());
        when(denyListRecordRepository.store(any())).thenAnswer(inv -> {
            DenyListRecord record = inv.getArgument(0);
            backingStore.add(record);
            return record;
        });
        when(denyListRecordRepository.findAllForEnvironment()).thenAnswer(inv -> backingStore);
        when(factory.denyListRecordRepository()).thenReturn(denyListRecordRepository);

        // Real registry, registered against the real controller class — see class Javadoc.
        AccessControlRegistry registry = new AccessControlRegistry();
        registry.register(HubWSDLController.class, "/IISHubService");

        service = new AccessControlService(
                factory, registry, mock(AccessControlMigrator.class),
                mock(IDestinationService.class), mock(IJurisdictionService.class));
        service.newModelHelper = new NewModelHelper(service);
        service.setMigrated(true);
        service.blacklistEnabled = true; // matches production default (security.enable-blacklist: true)

        RequestContext.init();
        RequestContext.setPrincipal(new CertificatePrincipal());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("A deny-listed sender is rejected on the real /IISHubService submission path")
    void deniedSender_isRejectedOnRealSubmissionPath() {
        service.addUserToDenyList(SENDER, "source attack");

        Boolean allowed = service.checkAccess(SENDER, "POST", "/IISHubService");

        assertEquals(Boolean.FALSE, allowed,
                "a deny-listed sender must be rejected on the SOAP submission path, not merely recorded");
    }
}
