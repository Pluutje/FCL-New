package app.aaps.plugins.aps.openAPSFCL.vnext

import app.aaps.plugins.aps.openAPSFCL.vnext.PostHypoEarlyRelease.Level
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PostHypoEarlyReleaseTest {

    @Test
    fun `off level never lets anything through`() {
        assertThat(PostHypoEarlyRelease.weight(Level.OFF, 9.0, 6.0, 0.1)).isEqualTo(0.0)
    }

    @Test
    fun `weight is the max of the level when everything is clear`() {
        assertThat(PostHypoEarlyRelease.weight(Level.LOW, 9.0, 6.0, 0.1)).isWithin(1e-9).of(0.20)
        assertThat(PostHypoEarlyRelease.weight(Level.MEDIUM, 9.0, 6.0, 0.1)).isWithin(1e-9).of(0.35)
        assertThat(PostHypoEarlyRelease.weight(Level.HIGH, 9.0, 6.0, 0.1)).isWithin(1e-9).of(0.55)
    }

    @Test
    fun `no release for a low BG, a slow rise or a high IOB`() {
        assertThat(PostHypoEarlyRelease.weight(Level.HIGH, 6.5, 6.0, 0.1)).isEqualTo(0.0)
        assertThat(PostHypoEarlyRelease.weight(Level.HIGH, 9.0, 2.0, 0.1)).isEqualTo(0.0)
        assertThat(PostHypoEarlyRelease.weight(Level.HIGH, 9.0, 6.0, 0.6)).isEqualTo(0.0)
    }

    @Test
    fun `weight grows smoothly with BG, slope and falls with IOB`() {
        var previous = 0.0
        var bg = 6.0
        while (bg <= 9.0) {
            val w = PostHypoEarlyRelease.weight(Level.HIGH, bg, 6.0, 0.1)
            assertThat(w).isAtLeast(previous)
            previous = w
            bg += 0.05
        }
        previous = 0.0
        var slope = 1.0
        while (slope <= 5.0) {
            val w = PostHypoEarlyRelease.weight(Level.HIGH, 9.0, slope, 0.1)
            assertThat(w).isAtLeast(previous)
            previous = w
            slope += 0.05
        }
        previous = 1.0
        var iob = 0.2
        while (iob <= 0.7) {
            val w = PostHypoEarlyRelease.weight(Level.HIGH, 9.0, 6.0, iob)
            assertThat(w).isAtMost(previous + 1e-12)
            previous = w
            iob += 0.01
        }
    }

    @Test
    fun `a higher level never lets less through`() {
        for (bg in listOf(7.0, 7.5, 8.0)) {
            val low = PostHypoEarlyRelease.weight(Level.LOW, bg, 3.0, 0.3)
            val medium = PostHypoEarlyRelease.weight(Level.MEDIUM, bg, 3.0, 0.3)
            val high = PostHypoEarlyRelease.weight(Level.HIGH, bg, 3.0, 0.3)
            assertThat(medium).isAtLeast(low)
            assertThat(high).isAtLeast(medium)
        }
    }

    @Test
    fun `PIN is needed for a higher level only`() {
        assertThat(PostHypoEarlyRelease.needsPin(Level.OFF, Level.LOW)).isTrue()
        assertThat(PostHypoEarlyRelease.needsPin(Level.LOW, Level.HIGH)).isTrue()
        assertThat(PostHypoEarlyRelease.needsPin(Level.HIGH, Level.LOW)).isFalse()
        assertThat(PostHypoEarlyRelease.needsPin(Level.MEDIUM, Level.OFF)).isFalse()
        assertThat(PostHypoEarlyRelease.needsPin(Level.LOW, Level.LOW)).isFalse()
    }

    @Test
    fun `PIN check`() {
        assertThat(PostHypoEarlyRelease.isPinCorrect("1962")).isTrue()
        assertThat(PostHypoEarlyRelease.isPinCorrect(" 1962 ")).isTrue()
        assertThat(PostHypoEarlyRelease.isPinCorrect("1963")).isFalse()
        assertThat(PostHypoEarlyRelease.isPinCorrect("")).isFalse()
    }

    @Test
    fun `an unknown level code is off`() {
        assertThat(Level.fromCode(99)).isEqualTo(Level.OFF)
        assertThat(Level.fromCode(2)).isEqualTo(Level.MEDIUM)
    }

    @Test
    fun `moments are grouped per meal and each meal has its own total`() {
        val hour = 60 * 60_000L
        val events = listOf(
            PostHypoEarlyRelease.Event(1 * hour, 0.3, 0.06),
            PostHypoEarlyRelease.Event(1 * hour + 300_000L, 0.4, 0.05),
            PostHypoEarlyRelease.Event(5 * hour, 0.5, 0.20),
            PostHypoEarlyRelease.Event(5 * hour + 300_000L, 0.5, 0.10)
        )
        val meals = PostHypoEarlyRelease.meals(events)
        assertThat(meals).hasSize(2)
        assertThat(meals[0].events).hasSize(2)
        assertThat(meals[0].extraU).isWithin(1e-9).of(0.11)
        assertThat(meals[1].extraU).isWithin(1e-9).of(0.30)
        assertThat(meals[1].startMs).isEqualTo(5 * hour)
        assertThat(PostHypoEarlyRelease.meals(emptyList())).isEmpty()
    }

    private fun point(timeMs: Long, bg: Double, deliveredU: Double = 0.0) = PostHypoEarlyRelease.BgPoint(timeMs, bg, deliveredU)

    @Test
    fun `peak is after the first dose and the lowest is after the last dose`() {
        val min = 60_000L
        val events = listOf(
            PostHypoEarlyRelease.Event(0L, 0.3, 0.1),
            PostHypoEarlyRelease.Event(20 * min, 0.3, 0.1)
        )
        val points = listOf(
            point(-45 * min, 2.8),            // before the first dose (earlier insulin): not counted
            point(-30 * min, 3.5, 1.0),       // the first dose of the meal: the BG at that moment is not counted either
            point(0L, 4.1, 0.2),              // between the first and the last dose: counts for the peak, not for the lowest
            point(15 * min, 4.0),
            point(30 * min, 11.2),
            point(90 * min, 5.0),
            point(150 * min, 4.4),
            point(210 * min, 9.9)             // more than 3 h after the last moment: after the window
        )
        val meals = PostHypoEarlyRelease.meals(events)
        assertThat(PostHypoEarlyRelease.firstDoseMs(meals[0], points)).isEqualTo(-30 * min)
        val range = PostHypoEarlyRelease.bgRanges(meals, points, 10 * 60 * min)[0]!!
        assertThat(range.peak).isEqualTo(11.2)
        assertThat(range.peakMs).isEqualTo(30 * min)
        assertThat(range.low).isEqualTo(4.4)          // not the 4.0 before the last dose at 20 min
        assertThat(range.lowMs).isEqualTo(150 * min)
        assertThat(range.complete).isTrue()
    }

    @Test
    fun `no lowest value yet when there is no BG after the last dose`() {
        val min = 60_000L
        val meals = PostHypoEarlyRelease.meals(listOf(PostHypoEarlyRelease.Event(0L, 0.3, 0.1)))
        val points = listOf(point(-5 * min, 6.0, 0.5), point(0L, 7.0))
        // The BG at the time of the last dose itself is not "after" it: the peak is known, the lowest is not.
        val range = PostHypoEarlyRelease.bgRanges(meals, points, 10 * min)[0]!!
        assertThat(range.peak).isEqualTo(7.0)
        assertThat(range.low).isNull()
        val later = PostHypoEarlyRelease.bgRanges(meals, points + point(5 * min, 7.6), 10 * min)[0]!!
        assertThat(later.peak).isEqualTo(7.6)
        assertThat(later.low).isEqualTo(7.6)
        // First dose at -5 min, last dose at 0 min, the only BG after the first dose is after the last dose.
        val onlyBefore = PostHypoEarlyRelease.bgRanges(meals, listOf(point(-5 * min, 6.0, 0.5), point(-2 * min, 6.5)), 10 * min)[0]!!
        assertThat(onlyBefore.peak).isEqualTo(6.5)
        assertThat(onlyBefore.low).isNull()
        assertThat(onlyBefore.lowMs).isNull()
    }

    @Test
    fun `still rising is the latest BG compared with 15 minutes before`() {
        val min = 60_000L
        val rising = listOf(point(0L, 6.0), point(5 * min, 6.2), point(10 * min, 6.5), point(15 * min, 6.9), point(20 * min, 7.2))
        assertThat(PostHypoEarlyRelease.isRising(rising, 20 * min)).isTrue()      // 7.2 against 6.2 (15 min before)
        val flat = listOf(point(0L, 6.0), point(5 * min, 6.1), point(10 * min, 6.0), point(15 * min, 6.1), point(20 * min, 6.2))
        assertThat(PostHypoEarlyRelease.isRising(flat, 20 * min)).isFalse()
        val falling = listOf(point(0L, 8.0), point(15 * min, 7.5), point(30 * min, 7.0))
        assertThat(PostHypoEarlyRelease.isRising(falling, 30 * min)).isFalse()
        assertThat(PostHypoEarlyRelease.isRising(emptyList(), 20 * min)).isFalse()
        assertThat(PostHypoEarlyRelease.isRising(listOf(point(0L, 6.0)), 5 * min)).isFalse()   // not enough history
    }

    @Test
    fun `a meal that is still running and rising is marked as rising, a finished one never`() {
        val min = 60_000L
        val meals = PostHypoEarlyRelease.meals(listOf(PostHypoEarlyRelease.Event(0L, 0.3, 0.1)))
        val points = listOf(point(-5 * min, 6.0, 0.5), point(5 * min, 6.5), point(10 * min, 7.0), point(20 * min, 7.6))
        val running = PostHypoEarlyRelease.bgRanges(meals, points, 20 * min)[0]!!
        assertThat(running.complete).isFalse()
        assertThat(running.rising).isTrue()
        val finished = PostHypoEarlyRelease.bgRanges(meals, points, 10 * 60 * min)[0]!!
        assertThat(finished.complete).isTrue()
        assertThat(finished.rising).isFalse()
    }

    @Test
    fun `first dose falls back to the first moment without a dose in the last hour`() {
        val min = 60_000L
        val meal = PostHypoEarlyRelease.Meal(listOf(PostHypoEarlyRelease.Event(100 * min, 0.3, 0.1)))
        val points = listOf(
            point(10 * min, 6.0, 1.0),        // more than an hour before: not the first dose of this meal
            point(60 * min, 6.0, 0.1)         // too small to be a dose
        )
        assertThat(PostHypoEarlyRelease.firstDoseMs(meal, points)).isEqualTo(100 * min)
    }

    @Test
    fun `BG range is still running while the window is open and stops at the next meal`() {
        val min = 60_000L
        val hour = 60 * min
        val meals = PostHypoEarlyRelease.meals(listOf(
            PostHypoEarlyRelease.Event(0L, 0.3, 0.1),
            PostHypoEarlyRelease.Event(2 * hour + 5 * min, 0.3, 0.1)   // second meal, 2 h 5 min later
        ))
        val points = listOf(point(0L, 7.0), point(hour, 8.0), point(2 * hour, 6.0), point(3 * hour, 12.0))
        val ranges = PostHypoEarlyRelease.bgRanges(meals, points, 4 * hour)
        // First meal: the window ends where the second meal starts, so the 12.0 at 3 h is not part of it.
        assertThat(ranges[0]!!.peak).isEqualTo(8.0)
        assertThat(ranges[0]!!.complete).isTrue()
        // Second meal: 3 h after its moment is not over yet at 4 h.
        assertThat(ranges[1]!!.peak).isEqualTo(12.0)
        assertThat(ranges[1]!!.complete).isFalse()
        assertThat(PostHypoEarlyRelease.bgRanges(meals, emptyList(), 4 * hour)).containsExactly(null, null)
    }

    @Test
    fun `a gap of exactly 90 minutes stays in one meal and more starts a new one`() {
        assertThat(PostHypoEarlyRelease.MEAL_GAP_MS).isEqualTo(90 * 60_000L)
        val gap = PostHypoEarlyRelease.MEAL_GAP_MS
        val same = PostHypoEarlyRelease.meals(listOf(
            PostHypoEarlyRelease.Event(0L, 0.3, 0.1), PostHypoEarlyRelease.Event(gap, 0.3, 0.1)
        ))
        assertThat(same).hasSize(1)
        val split = PostHypoEarlyRelease.meals(listOf(
            PostHypoEarlyRelease.Event(0L, 0.3, 0.1), PostHypoEarlyRelease.Event(gap + 1, 0.3, 0.1)
        ))
        assertThat(split).hasSize(2)
    }

    @Test
    fun `standard dose plus extra is the delivered dose`() {
        val pending = PostHypoEarlyRelease.Pending(5_000L, 0.18, 0.28)
        val e = PostHypoEarlyRelease.finish(pending, 5_000L, 0.62)!!
        assertThat(e.extraU).isWithin(1e-9).of(0.28)
        assertThat(e.standardU).isWithin(1e-9).of(0.34)
        assertThat(e.totalU).isWithin(1e-9).of(0.62)
        assertThat(PostHypoEarlyRelease.Event(1_000L, 0.18, 0.28).totalU.isNaN()).isTrue()
    }

    @Test
    fun `extra is never more than the delivered dose`() {
        val e = PostHypoEarlyRelease.finish(PostHypoEarlyRelease.Pending(5_000L, 0.3, 0.50), 5_000L, 0.30)!!
        assertThat(e.extraU).isWithin(1e-9).of(0.30)
        assertThat(e.standardU).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `no event for another cycle, no delivery or a tiny extra`() {
        val pending = PostHypoEarlyRelease.Pending(5_000L, 0.2, 0.28)
        assertThat(PostHypoEarlyRelease.finish(pending, 9_000L, 0.62)).isNull()
        assertThat(PostHypoEarlyRelease.finish(pending, 5_000L, 0.0)).isNull()
        assertThat(PostHypoEarlyRelease.finish(null, 5_000L, 0.62)).isNull()
        assertThat(PostHypoEarlyRelease.finish(PostHypoEarlyRelease.Pending(5_000L, 0.2, 0.01), 5_000L, 0.62)).isNull()
    }

    @Test
    fun `nearest event within the tolerance`() {
        val a = PostHypoEarlyRelease.Event(1_000L, 0.5, 0.3)
        val b = PostHypoEarlyRelease.Event(400_000L, 0.5, 0.3)
        assertThat(PostHypoEarlyRelease.nearest(listOf(a, b), 10_000L, 150_000L)).isEqualTo(a)
        assertThat(PostHypoEarlyRelease.nearest(listOf(a, b), 200_000L, 50_000L)).isNull()
    }
}
