package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CommitDecayReleaseTest {

    @Test
    fun `small dose counts little and a full commit counts one`() {
        assertThat(CommitDecayRelease.commitWeight(0.4, 3.58)).isLessThan(0.2)
        assertThat(CommitDecayRelease.commitWeight(1.79, 3.58)).isWithin(1e-9).of(1.0)
        assertThat(CommitDecayRelease.commitWeight(3.0, 3.58)).isEqualTo(1.0)
    }

    @Test
    fun `no dose or no maxSmb gives zero weight`() {
        assertThat(CommitDecayRelease.commitWeight(0.0, 3.58)).isEqualTo(0.0)
        assertThat(CommitDecayRelease.commitWeight(1.0, 0.0)).isEqualTo(0.0)
    }

    @Test
    fun `weight grows with the dose`() {
        val a = CommitDecayRelease.commitWeight(0.5, 3.58)
        val b = CommitDecayRelease.commitWeight(1.0, 3.58)
        assertThat(b).isGreaterThan(a)
    }

    @Test
    fun `strong early rise with low IOB gives full release`() {
        assertThat(CommitDecayRelease.release(0.30, 3.0, 0.05)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `weak acceleration gives no release`() {
        assertThat(CommitDecayRelease.release(0.13, 3.0, 0.05)).isEqualTo(0.0)
    }

    @Test
    fun `small delta to target gives no release`() {
        assertThat(CommitDecayRelease.release(0.30, 0.7, 0.05)).isEqualTo(0.0)
    }

    @Test
    fun `high IOB ratio gives no release`() {
        assertThat(CommitDecayRelease.release(0.30, 3.0, 0.40)).isEqualTo(0.0)
    }

    @Test
    fun `release fades in gradually with acceleration`() {
        val low = CommitDecayRelease.release(0.20, 3.0, 0.05)
        val high = CommitDecayRelease.release(0.26, 3.0, 0.05)
        assertThat(low).isGreaterThan(0.0)
        assertThat(low).isLessThan(high)
        assertThat(high).isLessThan(1.0)
    }

    @Test
    fun `blend never makes the decay stronger`() {
        assertThat(CommitDecayRelease.blend(0.5, 0.2, 1.0)).isEqualTo(0.5)
        assertThat(CommitDecayRelease.blend(0.3, 0.9, 0.0)).isEqualTo(0.3)
        assertThat(CommitDecayRelease.blend(0.3, 0.9, 0.5)).isWithin(1e-9).of(0.6)
        assertThat(CommitDecayRelease.blend(0.3, 0.9, 1.0)).isWithin(1e-9).of(0.9)
    }
}
