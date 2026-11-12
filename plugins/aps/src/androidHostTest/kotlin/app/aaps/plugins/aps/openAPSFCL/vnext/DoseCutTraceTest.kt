package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DoseCutTraceTest {

    @Test
    fun `first mark only sets the starting point`() {
        val t = DoseCutTrace()
        t.mark("START", 1.0)
        assertThat(t.totalCutU()).isEqualTo(0.0)
        assertThat(t.topLayer()).isEmpty()
    }

    @Test
    fun `drops are counted for the layer and the biggest one is the top`() {
        val t = DoseCutTrace()
        t.mark("START", 2.0)
        t.mark("BRAKE_A", 1.5)
        t.mark("CAP_B", 0.5)
        assertThat(t.topLayer()).isEqualTo("CAP_B")
        assertThat(t.topCutU()).isWithin(1e-9).of(1.0)
        assertThat(t.totalCutU()).isWithin(1e-9).of(1.5)
    }

    @Test
    fun `a rise is not counted and moves the starting point`() {
        val t = DoseCutTrace()
        t.mark("START", 1.0)
        t.mark("COMMIT", 3.0)
        t.mark("CAP", 2.5)
        assertThat(t.totalCutU()).isWithin(1e-9).of(0.5)
        assertThat(t.topLayer()).isEqualTo("CAP")
    }

    @Test
    fun `the same layer adds up`() {
        val t = DoseCutTrace()
        t.mark("START", 2.0)
        t.mark("CAP", 1.5)
        t.mark("CAP", 1.0)
        assertThat(t.topCutU()).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `forget removes the cuts of a prefix and resets the starting point`() {
        val t = DoseCutTrace()
        t.mark("START", 2.0)
        t.mark("COMMIT_COVER_BRAKE", 1.0)
        t.mark("OTHER", 0.8)
        t.forget("COMMIT_", 2.0)
        assertThat(t.totalCutU()).isWithin(1e-9).of(0.2)
        t.mark("NEXT", 1.5)
        assertThat(t.totalCutU()).isWithin(1e-9).of(0.7)
    }

    @Test
    fun `a hard stop is the top layer and is worth storing`() {
        val t = DoseCutTrace()
        t.markHardStop("LONG_SLOPE_BLOCK")
        assertThat(t.topLayer()).isEqualTo("LONG_SLOPE_BLOCK")
        assertThat(t.worthStoring()).isTrue()
    }

    @Test
    fun `small cuts are not worth storing and reset clears everything`() {
        val t = DoseCutTrace()
        t.mark("START", 1.0)
        t.mark("CAP", 0.99)
        assertThat(t.worthStoring()).isFalse()
        t.markHardStop("X")
        t.reset()
        assertThat(t.topLayer()).isEmpty()
        assertThat(t.totalCutU()).isEqualTo(0.0)
    }
}
