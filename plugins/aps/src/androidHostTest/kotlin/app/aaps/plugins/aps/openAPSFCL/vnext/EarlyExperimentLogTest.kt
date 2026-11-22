package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class EarlyExperimentLogTest {

    @Test
    fun `alert hit is false when the experiment is off`() {
        assertThat(EarlyExperimentLog.alertHit(8.5, 0.0)).isFalse()
    }

    @Test
    fun `alert hit is true when the peak is between the new and the normal threshold`() {
        // At 100 percent the threshold is 8.0. A predicted peak of 8.5 only starts watching because of the experiment.
        assertThat(EarlyExperimentLog.alertHit(8.5, 1.0)).isTrue()
    }

    @Test
    fun `alert hit is false when the peak is above the normal threshold`() {
        assertThat(EarlyExperimentLog.alertHit(9.5, 1.0)).isFalse()
    }

    @Test
    fun `alert hit is false when the peak is below the new threshold`() {
        assertThat(EarlyExperimentLog.alertHit(7.0, 1.0)).isFalse()
    }
}
