package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class IobRelaxTest {

    @Test
    fun `fade is zero with few meals and one with many`() {
        assertThat(IobRelax.fade(0)).isEqualTo(0.0)
        assertThat(IobRelax.fade(15)).isEqualTo(0.0)
        assertThat(IobRelax.fade(30)).isEqualTo(1.0)
        assertThat(IobRelax.fade(60)).isEqualTo(1.0)
    }

    @Test
    fun `fade is gradual and never goes down`() {
        var last = -1.0
        for (n in 10..35) {
            val f = IobRelax.fade(n)
            assertThat(f).isAtLeast(last)
            last = f
        }
        assertThat(IobRelax.fade(22)).isWithin(0.2).of(0.5)
    }

    @Test
    fun `weight is zero when off or without reference`() {
        assertThat(IobRelax.weight(0.0, 40, 1.0, true)).isEqualTo(0.0)
        assertThat(IobRelax.weight(1.0, 40, 1.0, false)).isEqualTo(0.0)
    }

    @Test
    fun `weight scales with strength, fade and day`() {
        assertThat(IobRelax.weight(1.0, 40, 1.0, true)).isEqualTo(1.0)
        assertThat(IobRelax.weight(0.5, 40, 1.0, true)).isEqualTo(0.5)
        assertThat(IobRelax.weight(1.5, 40, 1.0, true)).isEqualTo(1.0)
        assertThat(IobRelax.weight(1.0, 40, 0.0, true)).isEqualTo(0.0)
        assertThat(IobRelax.weight(1.0, 15, 1.0, true)).isEqualTo(0.0)
    }

    @Test
    fun `factor is only ever looser`() {
        // relative factor lower than the original: no change
        assertThat(IobRelax.blendFactor(0.6, 0.4, 1.0)).isEqualTo(0.6)
        // relative factor higher: moves up by the weight
        assertThat(IobRelax.blendFactor(0.4, 0.8, 0.5)).isWithin(1e-9).of(0.6)
        assertThat(IobRelax.blendFactor(0.4, 0.8, 1.0)).isWithin(1e-9).of(0.8)
        assertThat(IobRelax.blendFactor(0.4, 0.8, 0.0)).isEqualTo(0.4)
        assertThat(IobRelax.blendFactor(0.4, Double.NaN, 1.0)).isEqualTo(0.4)
    }

    @Test
    fun `ratio is only ever lower`() {
        assertThat(IobRelax.blendRatio(0.5, 0.7, 1.0)).isEqualTo(0.5)
        assertThat(IobRelax.blendRatio(0.8, 0.4, 0.5)).isWithin(1e-9).of(0.6)
        assertThat(IobRelax.blendRatio(0.8, 0.4, 1.0)).isWithin(1e-9).of(0.4)
        assertThat(IobRelax.blendRatio(0.8, 0.4, 0.0)).isEqualTo(0.8)
        assertThat(IobRelax.blendRatio(0.8, Double.NaN, 1.0)).isEqualTo(0.8)
    }
}
