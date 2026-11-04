package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.night.FclNightAiAdvisorResponseParser
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AiAdvisorErrorTextTest {

    @Test
    fun `503 gives a short title and one hint without the raw json`() {
        val raw = """{ "error": { "code": 503, "message": "This model is currently experiencing high demand.", "status": "UNAVAILABLE" } }"""
        val text = FclAiAdvisorService.friendlyHttpError("Gemini", 503, raw)
        val lines = text.split("\n")
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).contains("503")
        assertThat(text).doesNotContain("{")
        assertThat(text).doesNotContain("UNAVAILABLE")
    }

    @Test
    fun `other codes have a title with the code`() {
        for (code in listOf(400, 401, 403, 404, 429, 500, 502, 418)) {
            assertThat(FclAiAdvisorService.friendlyHttpError("Gemini", code, "x").split("\n")[0]).contains("$code")
        }
    }

    @Test
    fun `lower advice stays when the BG is on target`() {
        assertThat(FclNightAiAdvisorResponseParser.lowerDampFactor(0.0, 0.2)).isEqualTo(1.0)
        assertThat(FclNightAiAdvisorResponseParser.lowerDampFactor(-1.0, 0.2)).isEqualTo(1.0)
    }

    @Test
    fun `lower advice is gone when the BG is clearly above target`() {
        assertThat(FclNightAiAdvisorResponseParser.lowerDampFactor(1.5, 0.3)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `negative IOB brings the lower advice back`() {
        assertThat(FclNightAiAdvisorResponseParser.lowerDampFactor(1.5, -0.8)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `lower damp factor falls smoothly with the BG`() {
        var previous = 1.0
        var above = 0.0
        while (above <= 1.5) {
            val f = FclNightAiAdvisorResponseParser.lowerDampFactor(above, 0.3)
            assertThat(f).isAtMost(previous + 1e-12)
            assertThat(previous - f).isLessThan(0.1)
            previous = f
            above += 0.02
        }
    }
}
