package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OverrideIobScaleTest {

    @Test
    fun `no change without an override or at 100 percent or lower`() {
        assertThat(OverrideIobScale.scale(1.0)).isEqualTo(1.0)
        assertThat(OverrideIobScale.scale(0.5)).isEqualTo(1.0)
        assertThat(OverrideIobScale.scale(Double.NaN)).isEqualTo(1.0)
    }

    @Test
    fun `half of the override percentage above 100 goes to the max IOB`() {
        assertThat(OverrideIobScale.scale(1.5)).isWithin(1e-9).of(1.25)
        assertThat(OverrideIobScale.scale(1.2)).isWithin(1e-9).of(1.1)
    }

    @Test
    fun `the scale follows the fade of the override and never goes down by itself`() {
        var last = 1.0
        for (i in 0..10) {
            val mul = 1.0 + 0.5 * i / 10.0
            val s = OverrideIobScale.scale(mul)
            assertThat(s).isAtLeast(last)
            last = s
        }
    }
}
