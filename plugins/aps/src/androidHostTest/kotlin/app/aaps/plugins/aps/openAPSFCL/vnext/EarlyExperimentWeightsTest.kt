package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class EarlyExperimentWeightsTest {

    @Test
    fun `strength 0 gives no bonus`() {
        assertThat(EarlyExperimentWeights.extraDoseBonus(0.0, 1.0, true, 3)).isEqualTo(0.0)
    }

    @Test
    fun `first stage or unconfirmed meal gives no bonus`() {
        assertThat(EarlyExperimentWeights.extraDoseBonus(1.0, 1.0, true, 1)).isEqualTo(0.0)
        assertThat(EarlyExperimentWeights.extraDoseBonus(1.0, 1.0, false, 3)).isEqualTo(0.0)
    }

    @Test
    fun `full sustain at 100 percent gives 15 percent`() {
        assertThat(EarlyExperimentWeights.extraDoseBonus(1.0, 1.0, true, 2)).isWithin(1e-9).of(0.15)
    }

    @Test
    fun `150 percent gives 22 and a half percent`() {
        assertThat(EarlyExperimentWeights.extraDoseBonus(1.5, 0.95, true, 2)).isWithin(1e-9).of(0.225)
    }

    @Test
    fun `bonus fades in with the sustain score`() {
        val low = EarlyExperimentWeights.extraDoseBonus(1.0, 0.75, true, 2)
        val mid = EarlyExperimentWeights.extraDoseBonus(1.0, 0.85, true, 2)
        val high = EarlyExperimentWeights.extraDoseBonus(1.0, 0.95, true, 2)
        assertThat(low).isEqualTo(0.0)
        assertThat(mid).isGreaterThan(0.0)
        assertThat(mid).isLessThan(high)
    }

    @Test
    fun `threshold is 9 at strength 0 and drops with strength`() {
        assertThat(EarlyExperimentWeights.peakStartThreshold(0.0)).isEqualTo(9.0)
        assertThat(EarlyExperimentWeights.peakStartThreshold(1.0)).isWithin(1e-9).of(8.0)
        assertThat(EarlyExperimentWeights.peakStartThreshold(1.5)).isWithin(1e-9).of(7.5)
    }

    @Test
    fun `threshold never goes below the tested minimum`() {
        assertThat(EarlyExperimentWeights.peakStartThreshold(3.0)).isEqualTo(7.5)
    }

    @Test
    fun `old threshold is moved to the same strength`() {
        assertThat(EarlyExperimentWeights.percentFromOldThreshold(9.0)).isEqualTo(0)
        assertThat(EarlyExperimentWeights.percentFromOldThreshold(8.6)).isEqualTo(40)
        assertThat(EarlyExperimentWeights.percentFromOldThreshold(8.0)).isEqualTo(100)
        assertThat(EarlyExperimentWeights.percentFromOldThreshold(7.5)).isEqualTo(150)
    }
}
