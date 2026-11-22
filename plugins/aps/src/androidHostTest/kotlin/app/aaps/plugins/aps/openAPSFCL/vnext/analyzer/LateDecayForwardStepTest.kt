package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LateDecayForwardStepTest {

    @Test
    fun `experiment off gives the normal step`() {
        assertThat(DFLearner.forwardStepScale(0.0)).isEqualTo(1.0)
    }

    @Test
    fun `step falls in a straight line`() {
        assertThat(DFLearner.forwardStepScale(0.375)).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `step is zero at 75 percent and above`() {
        assertThat(DFLearner.forwardStepScale(0.75)).isEqualTo(0.0)
        assertThat(DFLearner.forwardStepScale(1.5)).isEqualTo(0.0)
    }

    @Test
    fun `step never goes up when strength goes up`() {
        var last = Double.MAX_VALUE
        for (percent in 0..150 step 10) {
            val scale = DFLearner.forwardStepScale(percent / 100.0)
            assertThat(scale).isAtMost(last)
            last = scale
        }
    }
}
