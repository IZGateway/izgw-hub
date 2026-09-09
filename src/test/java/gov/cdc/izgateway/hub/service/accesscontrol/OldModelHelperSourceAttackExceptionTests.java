package gov.cdc.izgateway.hub.service.accesscontrol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

/**
 * {@code OldModelHelper.isExemptFromSourceAttackLockout} is a new-model-only feature (IGDD-2805) —
 * the old model has no equivalent concept, so it always returns {@code false}.
 */
class OldModelHelperSourceAttackExceptionTests {

    @Test
    void alwaysReturnsFalse() {
        OldModelHelper helper = new OldModelHelper(mock(AccessControlService.class));
        assertFalse(helper.isExemptFromSourceAttackLockout("any.sender.example.gov"));
    }
}
