package gov.cdc.izgateway.hub.service.accesscontrol;

import gov.cdc.izgateway.dynamodb.model.SourceAttackExceptionRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NewModelHelper#isExemptFromSourceAttackLockout(String)} (IGDD-2805).
 *
 * <p>Uses a stub subclass to pre-populate the source-attack exception cache directly, bypassing
 * DynamoDB — same pattern as {@link NewModelHelperGetFileTypeTests}.</p>
 */
class NewModelHelperSourceAttackExceptionTests {

    /**
     * Stub subclass that pre-populates {@code sourceAttackExceptionCache} directly.
     */
    private static class StubNewModelHelper extends NewModelHelper {
        StubNewModelHelper(String... exemptedSenders) {
            super(null);
            for (String sender : exemptedSenders) {
                SourceAttackExceptionRecord record = new SourceAttackExceptionRecord();
                record.setSender(sender);
                sourceAttackExceptionCache.put(sender, record);
            }
        }

        @Override
        public void refresh() {
            // no-op — cache is pre-populated in constructor
        }
    }

    @Test
    void cachedSender_isExempt() {
        NewModelHelper helper = new StubNewModelHelper("VHA.example.gov");
        assertTrue(helper.isExemptFromSourceAttackLockout("VHA.example.gov"));
    }

    @Test
    void uncachedSender_isNotExempt() {
        NewModelHelper helper = new StubNewModelHelper("VHA.example.gov");
        assertFalse(helper.isExemptFromSourceAttackLockout("SomeOtherSender.example.gov"));
    }
}
