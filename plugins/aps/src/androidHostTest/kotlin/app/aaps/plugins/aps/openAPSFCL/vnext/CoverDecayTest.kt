package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CoverDecayTest {

    @Test
    fun `expected rise uses the lower of the two slopes`() {
        assertThat(CoverDecay.aheadMmol(slope = 2.0, recentSlope = 4.0)).isWithin(1e-9).of(1.0)
        assertThat(CoverDecay.aheadMmol(slope = 4.0, recentSlope = 2.0)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `expected rise is zero when one slope is flat or falling`() {
        assertThat(CoverDecay.aheadMmol(slope = 3.0, recentSlope = -1.0)).isEqualTo(0.0)
        assertThat(CoverDecay.aheadMmol(slope = 0.0, recentSlope = 5.0)).isEqualTo(0.0)
    }

    @Test
    fun `expected rise stops at the upper slope limit`() {
        assertThat(CoverDecay.aheadMmol(slope = 12.0, recentSlope = 12.0))
            .isWithin(1e-9).of(CoverDecay.AHEAD_MAX_SLOPE * CoverDecay.AHEAD_HOURS)
    }

    @Test
    fun `need has a lower limit near target`() {
        assertThat(CoverDecay.needMmol(mmolAboveTarget = 0.0, slope = 0.0, recentSlope = 0.0))
            .isEqualTo(CoverDecay.MIN_NEED_MMOL)
        assertThat(CoverDecay.needMmol(mmolAboveTarget = -2.0, slope = 0.0, recentSlope = 0.0))
            .isEqualTo(CoverDecay.MIN_NEED_MMOL)
    }

    @Test
    fun `iob per need matches the log row of 7 November 09_17`() {
        // BG 2.3 above target, IOB 2.37, slope 2.27, recent slope 3.16.
        assertThat(CoverDecay.iobPerNeed(2.37, 2.3, 2.27, 3.16)).isWithin(1e-9).of(2.37 / (2.3 + 2.27 * 0.5))
        assertThat(CoverDecay.iobPerNeed(0.0, 2.3, 2.27, 3.16)).isEqualTo(0.0)
        assertThat(CoverDecay.iobPerNeed(-0.4, 2.3, 2.27, 3.16)).isEqualTo(0.0)
    }

    @Test
    fun `decay is one at low cover and at the floor at high cover`() {
        assertThat(CoverDecay.decay(0.0)).isEqualTo(1.0)
        assertThat(CoverDecay.decay(CoverDecay.START)).isEqualTo(1.0)
        assertThat(CoverDecay.decay(CoverDecay.START + CoverDecay.SPAN)).isWithin(1e-9).of(CoverDecay.FLOOR)
        assertThat(CoverDecay.decay(5.0)).isWithin(1e-9).of(CoverDecay.FLOOR)
    }

    @Test
    fun `decay never rises with more cover and has no big step`() {
        var previous = 1.0
        var x = 0.0
        while (x <= 2.0) {
            val d = CoverDecay.decay(x)
            assertThat(d).isAtMost(previous + 1e-12)
            assertThat(previous - d).isLessThan(0.03)
            previous = d
            x += 0.01
        }
    }

    @Test
    fun `weight is a fifth at full strength and grows with the strength`() {
        assertThat(CoverDecay.weight(1.0, 1.0)).isWithin(1e-9).of(CoverDecay.WEIGHT_AT_100)
        assertThat(CoverDecay.weight(0.5, 1.0)).isWithin(1e-9).of(0.5 * CoverDecay.WEIGHT_AT_100)
        assertThat(CoverDecay.weight(1.5, 1.0)).isWithin(1e-9).of(1.5 * CoverDecay.WEIGHT_AT_100)
        assertThat(CoverDecay.weight(9.0, 1.0)).isWithin(1e-9).of(1.5 * CoverDecay.WEIGHT_AT_100)
        assertThat(CoverDecay.weight(1.0, 0.5)).isWithin(1e-9).of(0.5 * CoverDecay.WEIGHT_AT_100)
        assertThat(CoverDecay.weight(1.0, 0.0)).isEqualTo(0.0)
    }

    @Test
    fun `rise weight is zero when the BG is flat or falls and one from the full slope`() {
        assertThat(CoverDecay.riseWeight(0.0, 3.0)).isEqualTo(0.0)
        assertThat(CoverDecay.riseWeight(3.0, -1.0)).isEqualTo(0.0)
        assertThat(CoverDecay.riseWeight(-1.6, -2.2)).isEqualTo(0.0)
        assertThat(CoverDecay.riseWeight(CoverDecay.RISE_FULL_SLOPE, 4.0)).isEqualTo(1.0)
        assertThat(CoverDecay.riseWeight(0.5, 0.5)).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `strength zero changes nothing`() {
        val r = CoverDecay.calculate(0.0, 1.0, 1.5, 3, 4.0, 3.0, 2.0, 2.0, 0.04)
        assertThat(r.blendedMul).isEqualTo(0.04)
        assertThat(r.weight).isEqualTo(0.0)
    }

    @Test
    fun `no reference, the first commit and the night change nothing`() {
        assertThat(CoverDecay.calculate(1.0, 1.0, Double.NaN, 3, 4.0, 3.0, 2.0, 2.0, 0.04).blendedMul).isEqualTo(0.04)
        assertThat(CoverDecay.calculate(1.0, 1.0, 0.0, 3, 4.0, 3.0, 2.0, 2.0, 0.04).blendedMul).isEqualTo(0.04)
        assertThat(CoverDecay.calculate(1.0, 1.0, 1.5, 1, 4.0, 3.0, 2.0, 2.0, 1.0).blendedMul).isEqualTo(1.0)
        assertThat(CoverDecay.calculate(1.0, 0.0, 1.5, 3, 4.0, 3.0, 2.0, 2.0, 0.04).blendedMul).isEqualTo(0.04)
    }

    @Test
    fun `full strength moves the decay a fifth of the way and half strength a tenth`() {
        // IOB 1.0, need 1.5 + 1.0 = 2.5, reference 1.5. The BG is 1.5 above target (below the "still needs" start) and
        // rises 2 mmol/L per hour.
        val rel = 1.0 / 2.5 / 1.5
        val full = CoverDecay.calculate(1.0, 1.0, 1.5, 3, 1.0, 1.5, 2.0, 2.0, 0.04)
        assertThat(full.relativeCover).isWithin(1e-9).of(rel)
        assertThat(full.blendedMul).isWithin(1e-9).of(0.04 + (CoverDecay.decay(rel) - 0.04) * 0.2)
        val half = CoverDecay.calculate(0.5, 1.0, 1.5, 3, 1.0, 1.5, 2.0, 2.0, 0.04)
        assertThat(half.blendedMul).isWithin(1e-9).of(0.04 + (CoverDecay.decay(rel) - 0.04) * 0.1)
    }

    @Test
    fun `a falling BG never gets a weaker decay`() {
        // Log row of 10 November 20_47: slope -1.59, recent slope -2.15, IOB 1.63, BG 1.9 above target, old decay 0.04.
        val r = CoverDecay.calculate(1.5, 1.0, 1.517, 6, 1.63, 1.9, -1.59, -2.15, 0.04)
        assertThat(r.decayMul).isGreaterThan(0.04)
        assertThat(r.blendedMul).isEqualTo(0.04)
    }

    @Test
    fun `high cover makes the decay stronger, also when the BG is flat`() {
        // IOB 6.0, need 3.0: relative cover = 2.0 / 1.5, far above the end of the ramp, so the decay is at the floor.
        val r = CoverDecay.calculate(1.0, 1.0, 1.5, 2, 6.0, 3.0, 0.0, 0.0, 0.55)
        assertThat(r.blendedMul).isWithin(1e-9).of(0.55 + (CoverDecay.FLOOR - 0.55) * 0.2)
    }

    @Test
    fun `needs weight is zero near target, with a flat BG, with a high cover and at night`() {
        assertThat(CoverDecay.needsWeight(1.5, 3.0, 3.0, 0.4, 1.0)).isEqualTo(0.0)
        assertThat(CoverDecay.needsWeight(5.0, 0.0, 0.0, 0.4, 1.0)).isEqualTo(0.0)
        assertThat(CoverDecay.needsWeight(5.0, 3.0, 3.0, 0.95, 1.0)).isEqualTo(0.0)
        assertThat(CoverDecay.needsWeight(5.0, 3.0, 3.0, 0.4, 0.0)).isEqualTo(0.0)
    }

    @Test
    fun `needs weight is one when the BG is far above target, rises and the cover is low`() {
        assertThat(CoverDecay.needsWeight(5.0, 3.0, 3.0, 0.4, 1.0)).isEqualTo(1.0)
    }

    @Test
    fun `needs weight has no step in the BG above target, the cover and the slope`() {
        var last = 0.0
        var x = 1.0
        while (x <= 5.0) { // BG above target rising
            val w = CoverDecay.needsWeight(x, 3.0, 3.0, 0.4, 1.0)
            assertThat(w).isAtLeast(last)
            assertThat(w - last).isLessThan(0.1)
            last = w
            x += 0.05
        }
        last = 2.0
        var c = 0.4
        while (c <= 1.0) { // cover going up
            val w = CoverDecay.needsWeight(5.0, 3.0, 3.0, c, 1.0)
            assertThat(w).isAtMost(last)
            last = w
            c += 0.01
        }
    }

    @Test
    fun `floor and omslag scale follow the strength and stop at 100 percent`() {
        assertThat(CoverDecay.floorMul(0.0, 1.0, 0.5)).isEqualTo(0.0)
        assertThat(CoverDecay.floorMul(1.0, 0.0, 0.5)).isEqualTo(0.0)
        assertThat(CoverDecay.floorMul(1.0, 1.0, 0.5)).isWithin(1e-9).of(CoverDecay.FLOOR_SHARE * CoverDecay.decay(0.5))
        assertThat(CoverDecay.floorMul(1.5, 1.0, 0.5)).isEqualTo(CoverDecay.floorMul(1.0, 1.0, 0.5))
        assertThat(CoverDecay.floorMul(0.4, 1.0, 0.5)).isWithin(1e-9).of(0.4 * CoverDecay.floorMul(1.0, 1.0, 0.5))
        assertThat(CoverDecay.omslagScale(0.0, 1.0)).isEqualTo(1.0)
        assertThat(CoverDecay.omslagScale(1.0, 0.0)).isEqualTo(1.0)
        assertThat(CoverDecay.omslagScale(1.0, 1.0)).isEqualTo(0.0)
        assertThat(CoverDecay.omslagScale(0.4, 0.5)).isWithin(1e-9).of(0.8)
    }

    @Test
    fun `15 November 15 38 a long slow rise gets a floor under the decay`() {
        // Log row: BG 10.3 (target 5.0 so 5.3 above), slope 4.40, recent slope 3.10, IOB 5.60, relative cover 0.55.
        // Reference chosen so that the cover is 0.55. Old decay 0.43.
        val iobPerNeed = CoverDecay.iobPerNeed(5.6, 5.3, 4.4, 3.1)
        val reference = iobPerNeed / 0.55
        val r = CoverDecay.calculate(0.4, 1.0, reference, 13, 5.6, 5.3, 4.4, 3.1, 0.19)
        assertThat(r.relativeCover).isWithin(1e-9).of(0.55)
        assertThat(r.needsWeight).isEqualTo(1.0)
        assertThat(r.blendedMul).isAtLeast(0.4 * CoverDecay.FLOOR_SHARE * CoverDecay.decay(0.55) - 1e-9)
        assertThat(r.blendedMul).isGreaterThan(0.19)
    }

    @Test
    fun `the floor never makes the decay stronger`() {
        // Old decay already above the floor: the blend stays as it was.
        val r = CoverDecay.calculate(1.0, 1.0, 1.5, 3, 1.0, 1.5, 2.0, 2.0, 0.9)
        assertThat(r.blendedMul).isAtLeast(0.9 - 1e-9)
    }

    @Test
    fun `insulin estimate follows the ratio of the old and the new decay`() {
        // Commit 1.43 U with decay 0.38, was 0.24: without the experiment 0.90 U, so 0.53 U extra.
        assertThat(CoverDecay.commitDeltaU(1.43, 0.24, 0.38, 1.43)).isWithin(1e-9).of(1.43 * (1.0 - 0.24 / 0.38))
        // Only half of the commit was given: half of the difference.
        assertThat(CoverDecay.commitDeltaU(1.0, 0.2, 0.4, 0.5)).isWithin(1e-9).of(0.25)
        // More was given than the commit (another layer added to it): the commit is the limit.
        assertThat(CoverDecay.commitDeltaU(1.0, 0.2, 0.4, 3.0)).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `insulin estimate is below zero when the decay got stronger and zero without a commit`() {
        assertThat(CoverDecay.commitDeltaU(1.0, 0.5, 0.25, 1.0)).isWithin(1e-9).of(-1.0)
        assertThat(CoverDecay.commitDeltaU(0.0, 0.2, 0.4, 0.5)).isEqualTo(0.0)
        assertThat(CoverDecay.commitDeltaU(1.0, 0.2, 0.4, 0.0)).isEqualTo(0.0)
    }

    @Test
    fun `the other brake fades out with the weight`() {
        assertThat(CoverDecay.fadeOtherBrake(0.5, 0.0)).isEqualTo(0.5)
        assertThat(CoverDecay.fadeOtherBrake(0.5, 1.0)).isEqualTo(1.0)
        assertThat(CoverDecay.fadeOtherBrake(0.5, 0.5)).isWithin(1e-9).of(0.75)
    }

    @Test
    fun `step brake is unchanged without strength or without need`() {
        assertThat(CoverDecay.stepBrakeRelease(0.4, 0.0, 1.0)).isEqualTo(0.4)
        assertThat(CoverDecay.stepBrakeRelease(0.4, 1.0, 0.0)).isEqualTo(0.4)
        assertThat(CoverDecay.stepBrakeRelease(1.0, 1.0, 1.0)).isEqualTo(1.0)
    }

    @Test
    fun `step brake fades out gradually and a rest of the cut stays`() {
        // 16/11 13:22: brake 0.40, needs weight 0.73, strength 40 percent
        val now = CoverDecay.stepBrakeRelease(0.4, 0.4, 0.73)
        assertThat(now).isGreaterThan(0.4)
        assertThat(now).isLessThan(0.7)
        // full strength and full need: a rest of the cut stays
        val full = CoverDecay.stepBrakeRelease(0.4, 1.0, 1.0)
        assertThat(full).isWithin(1e-9).of(0.4 + 0.6 * CoverDecay.STEP_RELEASE_SHARE)
        assertThat(full).isLessThan(1.0)
        // never stronger, and strength above 100 percent counts as 100
        assertThat(CoverDecay.stepBrakeRelease(0.4, 1.5, 1.0)).isEqualTo(full)
        var last = 0.4
        for (i in 0..10) {
            val b = CoverDecay.stepBrakeRelease(0.4, 1.0, i / 10.0)
            assertThat(b).isAtLeast(last)
            last = b
        }
    }
}
