package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import app.aaps.plugins.aps.openAPSFCL.vnext.database.CycleDoseRow
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class DoseContextHistoryTest {

    private val zone = ZoneId.of("Europe/Amsterdam")
    private val day = LocalDate.of(2026, 10, 10)

    private fun row(hour: Int, sterkte: Int, delivered: Double, bolus: Double = 0.0, smbLimited: Boolean = false) =
        CycleDoseRow(
            timestampMs = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(),
            sterktePct = sterkte, timingPct = 100, volhoudendheidPct = 100, nachtFactorPct = 100,
            doseDistributionStyle = "A", nightResponseStyle = "B",
            deliveredTotal = delivered, realDeliveredBasalU = 0.5, realDeliveredBolusU = 0.0,
            externalBolusU = 0.0, aapsMultiplier = 1.0, aigfPct = 100.0, aigfBPct = 100.0,
            activityInsulinPct = 100.0, guardMaxSmbLimited = smbLimited, guardIobLimited = false,
            effectiveISF = 50.0, iob = 1.0, bolus = bolus
        )

    @Test
    fun `rows of one day become one line with sums and the most used setting`() {
        val rows = listOf(row(8, 100, 0.4), row(9, 110, 0.6, bolus = 0.3), row(10, 110, 0.2, smbLimited = true))
        val d = DoseContextHistory.daysFromRows(rows, zone).single()
        assertThat(d.date).isEqualTo(day)
        assertThat(d.cycles).isEqualTo(3)
        assertThat(d.fclU).isWithin(0.001).of(1.2)
        assertThat(d.realU).isWithin(0.001).of(1.5)
        assertThat(d.sterktePct).isEqualTo(110)
        assertThat(d.smbCount).isEqualTo(1)
        assertThat(d.maxSmbLimitedPct).isWithin(0.1).of(33.3)
    }

    @Test
    fun `rows of two days give two lines`() {
        val next = row(8, 100, 0.1).copy(timestampMs = row(8, 100, 0.1).timestampMs + 24 * 3_600_000L)
        assertThat(DoseContextHistory.daysFromRows(listOf(row(8, 100, 0.1), next), zone)).hasSize(2)
    }
}
