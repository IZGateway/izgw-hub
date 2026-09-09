package gov.cdc.izgateway.hub.service.accesscontrol;

import gov.cdc.izgateway.common.BadRequestException;
import gov.cdc.izgateway.configuration.AppProperties;
import gov.cdc.izgateway.dynamodb.model.SourceAttackExceptionRecord;
import gov.cdc.izgateway.hub.repository.IAccessGroupRepository;
import gov.cdc.izgateway.hub.repository.IAllowedUserRepository;
import gov.cdc.izgateway.hub.repository.IDenyListRecordRepository;
import gov.cdc.izgateway.hub.repository.IFileTypeRepository;
import gov.cdc.izgateway.hub.repository.ISourceAttackExceptionRepository;
import gov.cdc.izgateway.hub.repository.RepositoryFactory;
import gov.cdc.izgateway.logging.RequestContext;
import gov.cdc.izgateway.security.CertificatePrincipal;
import gov.cdc.izgateway.service.IAccessControlRegistry;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@code AccessControlService.handleSourceAttack} (the lockout fix) and the source-attack
 * exception CRUD it consults (IGDD-2805).
 */
class AccessControlServiceSourceAttackTests {

    private static final String SENDER = "VHA.example.gov";
    private static final String REASON = "Illegal text value in HL7Message inside: <field> element";

    private ISourceAttackExceptionRepository<SourceAttackExceptionRecord> sourceAttackExceptionRepository;
    private AccessControlService service;

    @BeforeAll
    static void bootstrapAppProperties() {
        // Constructing a TransactionData (via RequestContext.init) reads the AppProperties singleton to
        // decide PHI masking. Outside a Spring context nothing has published it, so publish a default.
        if (AppProperties.getInstance() == null) {
            new AppProperties();
        }
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        RepositoryFactory factory = mock(RepositoryFactory.class);
        // NewModelHelper.refresh() touches all five caches unconditionally, so every repository the
        // factory can return must be a real mock (not null) or refresh() NPEs.
        when(factory.accessGroupRepository()).thenReturn(mock(IAccessGroupRepository.class));
        when(factory.denyListRecordRepository()).thenReturn(mock(IDenyListRecordRepository.class));
        when(factory.fileTypeRepository()).thenReturn(mock(IFileTypeRepository.class));
        when(factory.allowedUserRepository()).thenReturn(mock(IAllowedUserRepository.class));
        sourceAttackExceptionRepository = mock(ISourceAttackExceptionRepository.class);
        when(factory.sourceAttackExceptionRepository()).thenReturn(sourceAttackExceptionRepository);

        service = spy(new AccessControlService(
                factory,
                mock(IAccessControlRegistry.class),
                mock(AccessControlMigrator.class),
                mock(IDestinationService.class),
                mock(IJurisdictionService.class)));
        // Constructed directly rather than via afterPropertiesSet(), which starts a real scheduled
        // executor — out of scope for a unit test (see CheckAccessToDestinationTests for the same call).
        service.newModelHelper = new NewModelHelper(service);
        service.setMigrated(true);

        RequestContext.init();
        RequestContext.setPrincipal(new CertificatePrincipal());
        RequestContext.getSourceInfo().setCommonName(SENDER);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("Lockout disabled: sender is not added to the deny list")
    void lockoutDisabled_doesNotDenyList() {
        service.sourceAttackLockoutEnabled = false;

        service.handleSourceAttack(SENDER, REASON);

        verify(service, never()).addUserToDenyList(any(), any());
    }

    @Test
    @DisplayName("Lockout enabled, no exception configured: sender is added to the deny list")
    void lockoutEnabled_noException_addsToDenyList() {
        service.sourceAttackLockoutEnabled = true;
        doReturn(false).when(service).isExemptFromSourceAttackLockout(SENDER);
        doReturn(null).when(service).addUserToDenyList(any(), any(), any());

        service.handleSourceAttack(SENDER, REASON);

        verify(service).addUserToDenyList(SENDER, REASON, "system:source-attack");
    }

    @Test
    @DisplayName("Auto-lockout attributes the deny-list record to the system actor, not the blocked sender")
    void lockoutEnabled_attributesRecordToSystemActor() {
        // RequestContext's principal during handleFault is the sender's own identity (it's their
        // request that got flagged) — block() must not use it as createdBy, or the audit trail would
        // misleadingly read "sender blocked sender" (IGDD-2805 review follow-up).
        List<gov.cdc.izgateway.dynamodb.model.DenyListRecord> backingStore = new ArrayList<>();
        IDenyListRecordRepository<gov.cdc.izgateway.dynamodb.model.DenyListRecord> denyListRecordRepository =
                mock(IDenyListRecordRepository.class);
        when(denyListRecordRepository.createEntity()).thenReturn(new gov.cdc.izgateway.dynamodb.model.DenyListRecord());
        when(denyListRecordRepository.store(any())).thenAnswer(inv -> {
            gov.cdc.izgateway.dynamodb.model.DenyListRecord record = inv.getArgument(0);
            backingStore.add(record);
            return record;
        });
        when(denyListRecordRepository.findAllForEnvironment()).thenAnswer(inv -> backingStore);
        RepositoryFactory factory = mock(RepositoryFactory.class);
        when(factory.accessGroupRepository()).thenReturn(mock(IAccessGroupRepository.class));
        when(factory.denyListRecordRepository()).thenReturn(denyListRecordRepository);
        when(factory.fileTypeRepository()).thenReturn(mock(IFileTypeRepository.class));
        when(factory.allowedUserRepository()).thenReturn(mock(IAllowedUserRepository.class));
        when(factory.sourceAttackExceptionRepository()).thenReturn(mock(ISourceAttackExceptionRepository.class));
        AccessControlService localService = new AccessControlService(
                factory,
                mock(IAccessControlRegistry.class),
                mock(AccessControlMigrator.class),
                mock(IDestinationService.class),
                mock(IJurisdictionService.class));
        localService.newModelHelper = new NewModelHelper(localService);
        localService.setMigrated(true);
        localService.sourceAttackLockoutEnabled = true;

        localService.handleSourceAttack(SENDER, REASON);

        assertEquals(1, backingStore.size());
        assertEquals("system:source-attack", backingStore.get(0).getCreatedBy());
        assertEquals(SENDER, backingStore.get(0).getPrincipal());
    }

    @Test
    @DisplayName("Lockout enabled, sender has a configured exception: sender is not added to the deny list")
    void lockoutEnabled_exceptionConfigured_doesNotDenyList() {
        service.sourceAttackLockoutEnabled = true;
        doReturn(true).when(service).isExemptFromSourceAttackLockout(SENDER);

        service.handleSourceAttack(SENDER, REASON);

        verify(service, never()).addUserToDenyList(any(), any());
    }

    @Test
    @DisplayName("Creating, listing, and deleting a source-attack exception round-trips")
    void createListDelete_roundTrips() {
        // AccessControlService.createSourceAttackException/deleteSourceAttackException both wrap their
        // in-place cache mutation with a finally { refresh(); } that reloads every cache from the
        // repositories (same pattern as addUserToDenyList/removeUserFromDenyList) — so the mock
        // repository needs to behave like a tiny fake, not just answer individual calls in isolation,
        // or the refresh would immediately overwrite the mutation with an empty result.
        List<SourceAttackExceptionRecord> backingStore = new ArrayList<>();
        when(sourceAttackExceptionRepository.createEntity()).thenReturn(new SourceAttackExceptionRecord());
        when(sourceAttackExceptionRepository.store(any())).thenAnswer(inv -> {
            SourceAttackExceptionRecord record = inv.getArgument(0);
            backingStore.add(record);
            return record;
        });
        when(sourceAttackExceptionRepository.findAllForEnvironment()).thenAnswer(inv -> backingStore);
        doAnswer(inv -> {
            backingStore.remove(inv.getArgument(0));
            return null;
        }).when(sourceAttackExceptionRepository).delete(any());

        SourceAttackExceptionRecord created = service.createSourceAttackException(SENDER, REASON);
        assertEquals(SENDER, created.getSender());
        assertEquals(REASON, created.getReason());

        List<SourceAttackExceptionRecord> exceptions = service.listSourceAttackExceptions();
        assertEquals(1, exceptions.size());
        assertEquals(SENDER, exceptions.get(0).getSender());
        assertTrue(service.isExemptFromSourceAttackLockout(SENDER));

        service.deleteSourceAttackException(SENDER);
        assertTrue(service.listSourceAttackExceptions().isEmpty());
        assertFalse(service.isExemptFromSourceAttackLockout(SENDER));
    }

    @Test
    @DisplayName("Blank sender is rejected")
    void blankSender_isRejected() {
        assertThrows(BadRequestException.class, () -> service.createSourceAttackException(" ", REASON));
    }

    @Test
    @DisplayName("Blank reason is rejected")
    void blankReason_isRejected() {
        assertThrows(BadRequestException.class, () -> service.createSourceAttackException(SENDER, " "));
    }
}
