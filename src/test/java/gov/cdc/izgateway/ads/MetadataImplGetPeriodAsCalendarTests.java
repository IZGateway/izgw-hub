package gov.cdc.izgateway.ads;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Calendar;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MetadataImpl#getPeriodAsCalendar()}, specifically its handling
 * of the bare-{@code YYYY} annual period format.
 * <p>
 * Before this fix, a bare year period's month was left at whatever
 * {@code Calendar.getInstance()} returned at construction time (the real-world "now"),
 * since neither the quarterly nor monthly branch recognized it. This was only harmless
 * because {@link MetadataBuilder#checkDate} divides by 12 for annual types, making the
 * month comparison always succeed regardless of its value. This test asserts the method
 * itself is now deterministic, independent of that caller-side masking.
 * </p>
 */
class MetadataImplGetPeriodAsCalendarTests {

    @Test
    void getPeriodAsCalendar_bareYear_defaultsToJanuaryDeterministically() {
        MetadataImpl meta = new MetadataImpl();
        meta.setPeriod("2025");

        Calendar cal = meta.getPeriodAsCalendar();

        assertEquals(2025, cal.get(Calendar.YEAR));
        assertEquals(Calendar.JANUARY, cal.get(Calendar.MONTH));
    }

    @Test
    void getPeriodAsCalendar_quarterly_stillWorks() {
        MetadataImpl meta = new MetadataImpl();
        meta.setPeriod("2025Q4");

        Calendar cal = meta.getPeriodAsCalendar();

        assertEquals(2025, cal.get(Calendar.YEAR));
        assertEquals(Calendar.OCTOBER, cal.get(Calendar.MONTH)); // Q4 -> month index 9
    }

    @Test
    void getPeriodAsCalendar_monthly_stillWorks() {
        MetadataImpl meta = new MetadataImpl();
        meta.setPeriod("2025-FEB");

        Calendar cal = meta.getPeriodAsCalendar();

        assertEquals(2025, cal.get(Calendar.YEAR));
        assertEquals(Calendar.FEBRUARY, cal.get(Calendar.MONTH));
    }
}
