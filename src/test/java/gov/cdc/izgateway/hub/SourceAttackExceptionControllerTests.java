package gov.cdc.izgateway.hub;

import gov.cdc.izgateway.dynamodb.model.SourceAttackExceptionRecord;
import gov.cdc.izgateway.hub.service.accesscontrol.AccessControlService;
import gov.cdc.izgateway.security.AccessControlRegistry;
import gov.cdc.izgateway.security.Roles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link SourceAttackExceptionController} (IGDD-2805): request delegation and role gating.
 */
class SourceAttackExceptionControllerTests {

    private static final String PATH = "/rest/sourceAttackExceptions";

    private AccessControlService accessControlService;
    private SourceAttackExceptionController controller;

    @BeforeEach
    void setUp() {
        accessControlService = mock(AccessControlService.class);
        controller = new SourceAttackExceptionController(mock(AccessControlRegistry.class), accessControlService);
    }

    @Test
    @DisplayName("list() delegates to AccessControlService")
    void list_delegatesToService() {
        List<SourceAttackExceptionRecord> expected = List.of(new SourceAttackExceptionRecord());
        when(accessControlService.listSourceAttackExceptions()).thenReturn(expected);

        assertEquals(expected, controller.list());
    }

    @Test
    @DisplayName("create() delegates to AccessControlService with the request's sender and reason")
    void create_delegatesToService() {
        SourceAttackExceptionRecord created = new SourceAttackExceptionRecord();
        when(accessControlService.createSourceAttackException("VHA.example.gov", "known false positive")).thenReturn(created);

        SourceAttackExceptionController.CreateExceptionRequest request = new SourceAttackExceptionController.CreateExceptionRequest();
        request.setSender("VHA.example.gov");
        request.setReason("known false positive");

        assertEquals(created, controller.create(request));
    }

    @Test
    @DisplayName("delete() delegates to AccessControlService")
    void delete_delegatesToService() {
        controller.delete("VHA.example.gov");

        verify(accessControlService).deleteSourceAttackException("VHA.example.gov");
    }

    @Test
    @DisplayName("Only the ADMIN role is registered for this controller's routes")
    void onlyAdminRoleIsAllowed() {
        AccessControlRegistry registry = new AccessControlRegistry();
        registry.register(SourceAttackExceptionController.class, PATH);

        assertEquals(List.of(Roles.ADMIN), registry.getAllowedRoles(RequestMethod.POST, PATH));
        assertEquals(List.of(Roles.ADMIN), registry.getAllowedRoles(RequestMethod.GET, PATH));
        assertEquals(List.of(Roles.ADMIN), registry.getAllowedRoles(RequestMethod.DELETE, PATH + "/VHA.example.gov"));
    }
}
