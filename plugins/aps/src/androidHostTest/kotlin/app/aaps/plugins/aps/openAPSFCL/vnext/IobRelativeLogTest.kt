package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class IobRelativeLogTest {

    @Test
    fun `no reference gives NaN`() {
        assertThat(IobRelativeLog.equivalentRatio(3.0, 4.0, 1.0, 1.0, Double.NaN).isNaN()).isTrue()
        assertThat(IobRelativeLog.equivalentRatio(3.0, 4.0, 1.0, 1.0, 0.0).isNaN()).isTrue()
    }

    @Test
    fun `ratio is the relative cover times the calibration`() {
        // need = 4.0 + 0 (flat slope) = 4.0, iob per need = 0.5, reference 1.0 -> cover 0.5
        val r = IobRelativeLog.equivalentRatio(2.0, 4.0, 0.0, 0.0, 1.0)
        assertThat(r).isWithin(1e-9).of(0.5 * IobRelativeLog.CALIBRATION)
    }

    @Test
    fun `a higher reference gives a lower ratio`() {
        val low = IobRelativeLog.equivalentRatio(2.0, 4.0, 0.0, 0.0, 0.5)
        val high = IobRelativeLog.equivalentRatio(2.0, 4.0, 0.0, 0.0, 1.5)
        assertThat(high).isLessThan(low)
    }

    @Test
    fun `cycles with almost no IOB on both measures are not stored`() {
        assertThat(IobRelativeLog.worthStoring(0.02, 0.03)).isFalse()
        assertThat(IobRelativeLog.worthStoring(0.30, 0.03)).isTrue()
        assertThat(IobRelativeLog.worthStoring(0.02, 0.30)).isTrue()
        assertThat(IobRelativeLog.worthStoring(0.30, Double.NaN)).isFalse()
    }

    @Test
    fun `nearest finds the closest event inside the tolerance`() {
        val events = listOf(
            IobRelativeLog.Event(1_000L, 0.1, 1.0, 1.0),
            IobRelativeLog.Event(300_000L, 0.2, 0.9, 1.0)
        )
        assertThat(IobRelativeLog.nearest(events, 310_000L, 150_000L)?.ratioRel).isEqualTo(0.2)
        assertThat(IobRelativeLog.nearest(events, 700_000L, 150_000L)).isNull()
    }
}
