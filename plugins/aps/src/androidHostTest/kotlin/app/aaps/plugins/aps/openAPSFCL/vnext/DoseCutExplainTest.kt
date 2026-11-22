package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DoseCutExplainTest {

    @Test
    fun `no sentence for an empty layer`() {
        assertThat(DoseCutExplain.sentence("", 1.0)).isNull()
    }

    @Test
    fun `no sentence when the cut is tiny or zero`() {
        assertThat(DoseCutExplain.sentence("IOB_STEP_BRAKE", 0.0)).isNull()
        assertThat(DoseCutExplain.sentence("IOB_STEP_BRAKE", 0.04)).isNull()
    }

    @Test
    fun `hard stops cut 0 and get no sentence`() {
        assertThat(DoseCutExplain.sentence("HARD_STOP_LONG_SLOPE", 0.0)).isNull()
        assertThat(DoseCutExplain.sentence("HARD_STOP_SHORT_TERM_DIP", 0.0)).isNull()
    }

    @Test
    fun `iob brakes say there is already much insulin`() {
        assertThat(DoseCutExplain.sentence("PEAK_SUPPRESSION", 0.3)).isEqualTo("Er werkt al veel insuline.")
    }

    @Test
    fun `unknown layer gets the safe default sentence`() {
        assertThat(DoseCutExplain.sentence("SOME_NEW_LAYER", 0.3)).isNotNull()
    }

    @Test
    fun `the groups do not overlap`() {
        // A layer in two groups would silently take the first sentence. Count the names to catch that.
        val names = DoseCutExplain.knownLayers()
        assertThat(names.size).isEqualTo(46)
    }
}
