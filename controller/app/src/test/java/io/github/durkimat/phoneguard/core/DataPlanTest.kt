package io.github.durkimat.phoneguard.core

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataPlanTest {
    private val z = ZoneId.of("Europe/London")
    private fun ms(d: String) = LocalDate.parse(d).atTime(12, 0).atZone(z).toInstant().toEpochMilli()
    private fun day(m: Long) = java.time.Instant.ofEpochMilli(m).atZone(z).toLocalDate().toString()

    @Test fun theMonthRunsFromTheRenewalDay() {
        val p = DataPlan(allowance = 10_000_000_000L, renewDay = 12)
        val (s, e) = p.period(ms("2026-11-15"), z)
        assertEquals("2026-11-12", day(s)); assertEquals("2026-12-12", day(e))
        val (s2, e2) = p.period(ms("2026-11-03"), z)
        assertEquals("2026-10-12", day(s2)); assertEquals("2026-11-12", day(e2))
    }

    @Test fun anExactStartDateSetsTheDayAndNotBeforeIt() {
        val p = DataPlan(allowance = 1, start = "2026-11-20")
        assertEquals("2026-11-20", day(p.period(ms("2026-11-25"), z).first))
        assertEquals("2026-11-20", day(p.period(ms("2026-11-10"), z).first)) // before the first period: it begins at the date
        assertEquals("2027-01-20", day(p.period(ms("2027-02-10"), z).first))
    }

    @Test fun shortMonthsClampTheDay() {
        val p = DataPlan(allowance = 1, renewDay = 28)
        assertEquals("2027-02-28", day(p.period(ms("2027-03-01"), z).first))
        val q = DataPlan(allowance = 1, start = "2026-01-31")
        assertEquals("2026-02-28", day(q.period(ms("2026-03-02"), z).first)) // the 31st clamps to the month end
    }

    @Test fun textShowsGigabytesWithOneDecimalAboveAGigabyteAndMegabytesBelow() {
        assertEquals("4.8 GB left", DataView(5_200_000_000L, 10_000_000_000L, 10).leftText)
        assertEquals("820 MB left", DataView(9_180_000_000L, 10_000_000_000L, 10).leftText)
        assertEquals("0 GB left", DataView(12_000_000_000L, 10_000_000_000L, 10).leftText)
        assertEquals("10 GB", DataView.amount(10_000_000_000L)); assertEquals("7.5 GB", DataView.amount(7_500_000_000L))
    }

    @Test fun theBarFillsWithWhatIsUsedAndIsFullWhenOver() {
        assertEquals(0.52f, DataView(5_200_000_000L, 10_000_000_000L, 10).fraction, 0.001f)
        assertEquals(1f, DataView(12_000_000_000L, 10_000_000_000L, 10).fraction, 0f)
    }

    @Test fun lowDataIsTenPercentOrLessLeft() {
        assertFalse(DataView(8_900_000_000L, 10_000_000_000L, 10).low)
        assertTrue(DataView(9_000_000_000L, 10_000_000_000L, 10).low) // exactly 10% left
        assertEquals("Data low: 900 MB left of 10 GB", DataView(9_100_000_000L, 10_000_000_000L, 10).bannerText)
        assertTrue(DataView(20_000_000_000L, 10_000_000_000L, 10).low)
    }

    @Test fun noAllowanceMeansNoPlan() { assertFalse(DataPlan().set); assertTrue(DataPlan(allowance = 1).set) }
}
