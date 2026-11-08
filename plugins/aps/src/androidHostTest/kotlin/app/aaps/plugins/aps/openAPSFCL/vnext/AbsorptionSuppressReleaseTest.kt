package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AbsorptionSuppressReleaseTest {

    @Test
    fun `near a peak at moderate height there is no release`() {
        assertThat(AbsorptionSuppressRelease.calculate(2.0, 0.5, 0.0)).isEqualTo(0.0)
    }

    @Test
    fun `high IOB ratio gives no release even when high`() {
        assertThat(AbsorptionSuppressRelease.calculate(10.0, 0.6, 0.0)).isEqualTo(0.0)
    }

    @Test
    fun `falling BG gives no release`() {
        assertThat(AbsorptionSuppressRelease.calculate(10.0, 0.1, -0.2)).isEqualTo(0.0)
    }

    @Test
    fun `high flat BG with low IOB ratio gives full release`() {
        assertThat(AbsorptionSuppressRelease.calculate(10.0, 0.15, 0.0)).isEqualTo(1.0)
    }

    @Test
    fun `21 07 case gives a large release`() {
        // BG 15.3, target 5.5, IOB ratio 0.30, flat
        val r = AbsorptionSuppressRelease.calculate(9.8, 0.30, 0.0)
        assertThat(r).isGreaterThan(0.6)
    }

    @Test
    fun `release is smooth in the IOB ratio`() {
        val a = AbsorptionSuppressRelease.calculate(9.0, 0.25, 0.0)
        val b = AbsorptionSuppressRelease.calculate(9.0, 0.35, 0.0)
        val c = AbsorptionSuppressRelease.calculate(9.0, 0.45, 0.0)
        assertThat(a).isGreaterThan(b)
        assertThat(b).isGreaterThan(c)
    }

    @Test
    fun `dose factor goes from suppression factor to one`() {
        assertThat(AbsorptionSuppressRelease.doseFactor(0.1, 0.0)).isEqualTo(0.1)
        assertThat(AbsorptionSuppressRelease.doseFactor(0.1, 1.0)).isEqualTo(1.0)
        assertThat(AbsorptionSuppressRelease.doseFactor(0.1, 0.5)).isWithin(1e-9).of(0.55)
    }
}
