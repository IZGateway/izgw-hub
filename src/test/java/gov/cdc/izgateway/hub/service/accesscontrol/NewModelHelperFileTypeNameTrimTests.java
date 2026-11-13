package gov.cdc.izgateway.hub.service.accesscontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import gov.cdc.izgateway.dynamodb.model.FileType;
import gov.cdc.izgateway.hub.repository.IAccessGroupRepository;
import gov.cdc.izgateway.hub.repository.IAllowedUserRepository;
import gov.cdc.izgateway.hub.repository.IDenyListRecordRepository;
import gov.cdc.izgateway.hub.repository.IFileTypeRepository;
import gov.cdc.izgateway.hub.repository.ISourceAttackExceptionRepository;
import gov.cdc.izgateway.hub.repository.RepositoryFactory;
import gov.cdc.izgateway.model.IFileType;
import gov.cdc.izgateway.service.IAccessControlRegistry;
import gov.cdc.izgateway.service.IDestinationService;
import gov.cdc.izgateway.service.IJurisdictionService;

/**
 * Regression test for a real incident on IGDD-3743: a {@code FileType.fileTypeName}
 * value hand-entered in the configuration console/DynamoDB with a trailing space
 * (e.g. {@code "riAnnualAggregate "}) broke the three-tier {@code reportType} match
 * in {@link NewModelHelper#getFileType(String)}, since neither exact nor
 * case-insensitive comparison tolerates whitespace. Confirmed live against
 * {@code dev.izgateway.org} via the CI {@code verify} job on PR #209.
 * <p>
 * {@link NewModelHelper#refresh()} now trims {@code fileTypeName} for both the cache
 * key and the entity's own field, since {@link NewModelHelper#getFileType(String)}
 * callers read {@code fileType.getFileTypeName()} directly off the returned entity,
 * not just the cache key.
 * </p>
 */
@SuppressWarnings("unchecked")
class NewModelHelperFileTypeNameTrimTests {

    private AccessControlService service;

    @BeforeEach
    void setUp() {
        RepositoryFactory factory = mock(RepositoryFactory.class);
        // NewModelHelper.refresh() touches all five caches unconditionally, so every
        // repository the factory can return must be a real mock (not null) or refresh() NPEs.
        when(factory.accessGroupRepository()).thenReturn(mock(IAccessGroupRepository.class));
        when(factory.denyListRecordRepository()).thenReturn(mock(IDenyListRecordRepository.class));
        IFileTypeRepository<FileType> fileTypeRepository = mock(IFileTypeRepository.class);
        when(factory.fileTypeRepository()).thenReturn(fileTypeRepository);
        when(factory.allowedUserRepository()).thenReturn(mock(IAllowedUserRepository.class));
        when(factory.sourceAttackExceptionRepository()).thenReturn(mock(ISourceAttackExceptionRepository.class));

        FileType untrimmed = new FileType();
        untrimmed.setFileTypeName("riAnnualAggregate "); // trailing space, as actually seen in dev
        when(fileTypeRepository.findAllForEnvironment()).thenReturn(List.of(untrimmed));

        service = new AccessControlService(
                factory,
                mock(IAccessControlRegistry.class),
                mock(AccessControlMigrator.class),
                mock(IDestinationService.class),
                mock(IJurisdictionService.class));
        service.newModelHelper = new NewModelHelper(service);
    }

    @Test
    void getFileType_trimsTrailingSpaceFromDynamoDbName_exactMatchSucceeds() {
        IFileType ft = service.newModelHelper.getFileType("riAnnualAggregate");

        assertNotNull(ft, "clean 'riAnnualAggregate' should exact-match despite the trailing space in DynamoDB");
        assertEquals("riAnnualAggregate", ft.getFileTypeName(),
                "the returned entity's own fileTypeName must also be trimmed, "
                + "since callers (e.g. MetadataBuilder.setReportType) read it directly");
    }

    @Test
    void getEventTypes_doesNotLeakUntrimmedName() {
        // getFileType() triggers refresh() as a side effect when the cache is empty.
        service.newModelHelper.getFileType("riAnnualAggregate");

        assertEquals(List.of("riAnnualAggregate"), List.copyOf(service.newModelHelper.getEventTypes()));
    }
}
