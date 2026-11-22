package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FclAiAdvisorRangesTest {

    private val strength = FclAiAdvisorRanges.byKey.getValue(FclAiAdvisorRanges.KEY_STRENGTH)
    private val maxIob = FclAiAdvisorRanges.byKey.getValue(FclAiAdvisorRanges.KEY_MAX_IOB)
    private val maxSmb = FclAiAdvisorRanges.byKey.getValue(FclAiAdvisorRanges.KEY_MAX_SMB)

    @Test
    fun `a slider moves at most one step`() {
        assertThat(FclAiAdvisorRanges.limitStep(strength, 5.0, 9.0)).isEqualTo(6.0)
        assertThat(FclAiAdvisorRanges.limitStep(strength, 5.0, 1.0)).isEqualTo(4.0)
        assertThat(FclAiAdvisorRanges.limitStep(strength, 5.0, 6.0)).isEqualTo(6.0)
    }

    @Test
    fun `a slider stays inside 1 to 9`() {
        assertThat(FclAiAdvisorRanges.limitStep(strength, 9.0, 12.0)).isEqualTo(9.0)
        assertThat(FclAiAdvisorRanges.limitStep(strength, 1.0, -3.0)).isEqualTo(1.0)
    }

    @Test
    fun `max IOB moves at most 15 percent and is rounded toward the current value`() {
        // 4.0 + 15% = 4.6 -> rounded down to 4.5
        assertThat(FclAiAdvisorRanges.limitStep(maxIob, 4.0, 10.0)).isEqualTo(4.5)
        // 8.0 - 15% = 6.8 -> rounded up to 7.0
        assertThat(FclAiAdvisorRanges.limitStep(maxIob, 8.0, 1.0)).isEqualTo(7.0)
    }

    @Test
    fun `max SMB is rounded to 0_05 and an equal proposal gives the current value`() {
        // 1.25 U * 15% = 0.19 -> 1.44 -> rounded down to 1.40 (granularity 0.05)
        assertThat(FclAiAdvisorRanges.limitStep(maxSmb, 1.25, 3.0)).isWithin(1e-9).of(1.4)
        // A proposal equal to the current value stays the same
        assertThat(FclAiAdvisorRanges.limitStep(maxSmb, 1.25, 1.25)).isWithin(1e-9).of(1.25)
    }

    @Test
    fun `only the user settings are known and the limits need a tap`() {
        assertThat(FclAiAdvisorRanges.byKey.keys).containsExactly(
            "agg_strength", "agg_timing", "agg_persistence", "max_bolus_day", "fcl_vnext_MaxIOB"
        )
        assertThat(maxIob.needsManualApproval).isTrue()
        assertThat(maxSmb.needsManualApproval).isTrue()
        assertThat(strength.needsManualApproval).isFalse()
    }
}
