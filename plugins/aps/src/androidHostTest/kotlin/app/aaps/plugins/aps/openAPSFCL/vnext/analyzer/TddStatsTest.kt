package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

class TddStatsTest {

    private val today = LocalDate.of(2026, 10, 16)

    /** One line per day for [from]..[to] days back, with a small steady wobble in TDD. */
    private fun days(from: Int, to: Int, tdd: Double, tir: Double, hours: Double = 24.0): List<DayAgg> =
        (from..to).map { back ->
            val wobble = if (back % 2 == 0) 1.0 else -1.0
            DayAgg(today.minusDays(back.toLong()), 8.0, 1.5, 30.0, tir, hours, tdd + wobble, tdd / 2, tdd / 2)
        }

    @Test
    fun `too few days gives no advice`() {
        val all = days(1, 5, 40.0, 80.0)
        assertThat(TddStats.advice(all, today).kind).isEqualTo(TddAdviceKind.NOT_ENOUGH_DATA)
    }

    @Test
    fun `stable TDD gives no advice needed`() {
        val all = days(1, 14, 40.0, 80.0) + days(15, 42, 41.0, 80.0)
        assertThat(TddStats.advice(all, today).kind).isEqualTo(TddAdviceKind.STABLE)
    }

    @Test
    fun `higher TDD with lower TIR is flagged`() {
        val all = days(1, 14, 50.0, 70.0) + days(15, 42, 40.0, 80.0)
        val advice = TddStats.advice(all, today)
        assertThat(advice.kind).isEqualTo(TddAdviceKind.UP_TIR_WORSE)
        assertThat(advice.changePct).isGreaterThan(15.0)
    }

    @Test
    fun `higher TDD with same TIR needs no change`() {
        val all = days(1, 14, 50.0, 80.0) + days(15, 42, 40.0, 80.0)
        assertThat(TddStats.advice(all, today).kind).isEqualTo(TddAdviceKind.UP_TIR_OK)
    }

    @Test
    fun `lower TDD with same TIR is reported`() {
        val all = days(1, 14, 30.0, 82.0) + days(15, 42, 40.0, 80.0)
        assertThat(TddStats.advice(all, today).kind).isEqualTo(TddAdviceKind.DOWN_TIR_OK)
    }

    @Test
    fun `days with little BG do not count for TIR`() {
        // TIR days are all short, so TIR is unknown and there is no advice.
        val all = days(1, 14, 50.0, 70.0, hours = 10.0) + days(15, 42, 40.0, 80.0, hours = 10.0)
        assertThat(TddStats.advice(all, today).kind).isEqualTo(TddAdviceKind.NOT_ENOUGH_DATA)
    }

    @Test
    fun `very different days give no advice`() {
        val noisy = (1..14).map { back ->
            val tdd = if (back % 2 == 0) 20.0 else 70.0
            DayAgg(today.minusDays(back.toLong()), 8.0, 1.5, 30.0, 70.0, 24.0, tdd, tdd / 2, tdd / 2)
        }
        val all = noisy + days(15, 42, 40.0, 80.0)
        assertThat(TddStats.advice(all, today).kind).isEqualTo(TddAdviceKind.TOO_NOISY)
    }

    @Test
    fun `days without TDD are skipped in the average`() {
        val withTdd = days(1, 3, 40.0, 80.0)
        val without = (4..6).map { DayAgg(today.minusDays(it.toLong()), 8.0, 1.5, 30.0, 80.0, 24.0) }
        val avg = TddStats.average(TddStats.window(withTdd + without, today, 1, 14))
        assertThat(avg!!.days).isEqualTo(3)
    }

    /** One day with TDD, TIR and TBR; [hours] of BG data. */
    private fun day(back: Int, tdd: Double, tir: Double, tbr: Double, hours: Double = 24.0): DayAgg =
        DayAgg(today.minusDays(back.toLong()), 8.0, 1.5, 30.0, tir, hours, tdd, tdd * 0.9, tdd * 0.1, tbr)

    @Test
    fun `week blocks are oldest first and need enough days`() {
        val all = days(1, 7, 40.0, 80.0) + days(8, 14, 38.0, 82.0) + days(15, 16, 30.0, 80.0)
        val blocks = TddStats.weekBlocks(all, today)
        assertThat(blocks).hasSize(TddStats.WEEK_BLOCKS)
        // newest block is last, the block with only 2 days is null
        assertThat(blocks.last()!!.tdd).isWithin(1.0).of(40.0)
        assertThat(blocks[TddStats.WEEK_BLOCKS - 2]!!.tirPct).isWithin(0.1).of(82.0)
        assertThat(blocks[TddStats.WEEK_BLOCKS - 3]).isNull()
    }

    @Test
    fun `week block start is the oldest day of the block`() {
        val blocks = TddStats.weekBlocks(days(1, 7, 40.0, 80.0), today)
        assertThat(blocks.last()!!.start).isEqualTo(today.minusDays(7))
    }

    @Test
    fun `a weekday with clearly higher TDD and higher TAR gets the first hint`() {
        // Saturdays: TDD 46, TIR 60, TBR 1 (TAR 39). Other days: TDD 40, TIR 80, TBR 1 (TAR 19).
        val all = (1..70).map { back ->
            val date = today.minusDays(back.toLong())
            if (date.dayOfWeek == DayOfWeek.SATURDAY) day(back, 46.0 + (back % 2), 60.0, 1.0)
            else day(back, 40.0 + (back % 2), 80.0, 1.0)
        }
        val overview = TddStats.weekdayOverview(all, today)
        val saturday = overview.stats.first { it.dow == DayOfWeek.SATURDAY }
        assertThat(saturday.hint).isEqualTo(WeekdayHintKind.UP_TAR_HIGHER)
        assertThat(saturday.diffPoints).isGreaterThan(TddStats.TAR_LIMIT)
        assertThat(overview.stats.count { it.hint != null }).isEqualTo(1)
    }

    @Test
    fun `a weekday with lower TDD and higher TBR gets the lower hint`() {
        val all = (1..70).map { back ->
            val date = today.minusDays(back.toLong())
            if (date.dayOfWeek == DayOfWeek.SUNDAY) day(back, 34.0 + (back % 2), 80.0, 4.0)
            else day(back, 40.0 + (back % 2), 80.0, 1.0)
        }
        val sunday = TddStats.weekdayOverview(all, today).stats.first { it.dow == DayOfWeek.SUNDAY }
        assertThat(sunday.hint).isEqualTo(WeekdayHintKind.DOWN_TBR_HIGHER)
    }

    @Test
    fun `a small weekday difference gives no hint`() {
        val all = (1..70).map { back ->
            val date = today.minusDays(back.toLong())
            if (date.dayOfWeek == DayOfWeek.SATURDAY) day(back, 42.0 + (back % 2), 78.0, 1.0)
            else day(back, 40.0 + (back % 2), 80.0, 1.0)
        }
        assertThat(TddStats.weekdayOverview(all, today).stats.count { it.hint != null }).isEqualTo(0)
    }

    @Test
    fun `without TBR data there is no weekday hint`() {
        // days() has no TBR (NaN), like lines stored before TBR was added
        val all = days(1, 70, 40.0, 80.0)
        assertThat(TddStats.weekdayOverview(all, today).stats.count { it.hint != null }).isEqualTo(0)
    }

    private fun doseDay(back: Int, sterkte: Int, fclU: Double = 30.0, realU: Double = 40.0) = DoseDay(
        date = today.minusDays(back.toLong()), cycles = 280, fclU = fclU, realU = realU, externalU = 0.0,
        sterktePct = sterkte, timingPct = 100, volhoudendheidPct = 100, nachtFactorPct = 100,
        aapsMultiplierPct = 100, distributionStyle = "A", nightStyle = "B",
        aigfPct = 100.0, aigfBPct = 100.0, activityPct = 100.0, maxSmbLimitedPct = 0.0,
        iobLimitedPct = 0.0, avgIsf = 50.0, avgIob = 1.0, smbCount = 10, smbU = 5.0
    )

    @Test
    fun `fcl share is commanded over delivered`() {
        assertThat(doseDay(1, 100, 30.0, 40.0).fclShare).isWithin(0.001).of(0.75)
        assertThat(doseDay(1, 100, 30.0, 0.5).fclShare.isNaN()).isTrue()
    }

    @Test
    fun `a kept change of a setting shows before and after`() {
        val all = days(1, 40, 40.0, 80.0).map { it.copy(tbrPct = 2.0) }
        // sterkte 100 until 15 days back, 110 after that
        val dose = (1..40).map { back -> doseDay(back, if (back > 15) 100 else 110) }
        val changes = TddStats.changeEffects(all, dose, today)
        val c = changes.single { it.kind == ParamKind.STERKTE }
        assertThat(c.date).isEqualTo(today.minusDays(15))
        assertThat(c.from).isEqualTo("100")
        assertThat(c.to).isEqualTo("110")
        assertThat(c.before).isNotNull()
        assertThat(c.after).isNotNull()
        assertThat(c.after!!.fclSharePct).isWithin(0.1).of(75.0)
    }

    @Test
    fun `a one day flicker is not a change`() {
        val all = days(1, 40, 40.0, 80.0)
        val dose = (1..40).map { back -> doseDay(back, if (back == 15) 110 else 100) }
        assertThat(TddStats.changeEffects(all, dose, today)).isEmpty()
    }

    @Test
    fun `a very recent change is too short for an after view`() {
        val all = days(1, 40, 40.0, 80.0)
        val dose = (1..40).map { back -> doseDay(back, if (back > 2) 100 else 110) }
        val c = TddStats.changeEffects(all, dose, today).single()
        assertThat(c.before).isNotNull()
        assertThat(c.after).isNull()
    }
}
