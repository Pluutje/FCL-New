package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CommitGateTest {

    @Test
    fun `no commit weight at or below the start of the smooth start`() {
        assertThat(CommitGate.deltaWeight(-1.0)).isEqualTo(0.0)
        assertThat(CommitGate.deltaWeight(0.0)).isEqualTo(0.0)
        assertThat(CommitGate.deltaWeight(CommitGate.DELTA_RAMP_START)).isEqualTo(0.0)
    }

    @Test
    fun `full commit weight at or above the end of the smooth start`() {
        assertThat(CommitGate.deltaWeight(CommitGate.DELTA_RAMP_END)).isEqualTo(1.0)
        assertThat(CommitGate.deltaWeight(2.5)).isEqualTo(1.0)
    }

    @Test
    fun `the weight grows smoothly in between`() {
        // 0.5 to 0.8 mmol/L: halfway (0.65) is exactly half, and the values at 0.6 and 0.7 are known.
        assertThat(CommitGate.deltaWeight(0.65)).isWithin(1e-9).of(0.5)
        assertThat(CommitGate.deltaWeight(0.6)).isWithin(0.001).of(0.259)
        assertThat(CommitGate.deltaWeight(0.7)).isWithin(0.001).of(0.741)
    }

    @Test
    fun `the weight never goes down when the BG is higher`() {
        var previous = 0.0
        var delta = 0.0
        while (delta <= 1.2) {
            val w = CommitGate.deltaWeight(delta)
            assertThat(w).isAtLeast(previous)
            previous = w
            delta += 0.01
        }
    }

    @Test
    fun `fast lane and acceleration limits are unchanged`() {
        assertThat(CommitGate.fastLane(0.08, 0.0)).isTrue()
        assertThat(CommitGate.fastLane(0.0, 0.6)).isTrue()
        assertThat(CommitGate.fastLane(0.07, 0.59)).isFalse()
        assertThat(CommitGate.accelOk(0.14, 0.0)).isTrue()
        assertThat(CommitGate.accelOk(0.10, 0.12)).isTrue()
        assertThat(CommitGate.accelOk(0.10, 0.11)).isFalse()
    }
}
