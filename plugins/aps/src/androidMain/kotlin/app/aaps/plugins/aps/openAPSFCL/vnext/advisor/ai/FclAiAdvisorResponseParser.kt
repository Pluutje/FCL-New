package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import org.json.JSONObject

/**
 * ============================================================================
 * FCL AI-Advisor — ResponseParser
 * ============================================================================
 *
 * Parseert de JSON van het model en valideert elk voorstel HARD tegen
 * FclAiAdvisorRanges. Een voorstel buiten bereik of met een onbekende
 * parameternaam wordt VERWORPEN (rejected=true + reden), niet geclampt —
 * een waarde buiten bereik duidt op een modelfout die zichtbaar moet blijven,
 * niet stilzwijgend gecorrigeerd.
 */
object FclAiAdvisorResponseParser {

    fun parse(rawResponse: String, payload: FclDailyReportPayload): AiAdvisorRunResult {
        val cleaned = stripMarkdownFences(rawResponse)
        val repaired = tryRepairTruncatedJson(cleaned)
        val json = try {
            JSONObject(repaired)
        } catch (t: Throwable) {
            // Geef de gebruiker een leesbare melding, niet een technische Java-stacktrace.
            val userMsg = when {
                cleaned.contains("\"suggestions\"") && cleaned.length < 200 ->
                    "Antwoord te kort\nHet model gaf een te kort antwoord. Probeer 'Nu vernieuwen' opnieuw."
                cleaned.startsWith("{") ->
                    "Antwoord onvolledig\nHet antwoord van de AI was halverwege afgekapt. Probeer 'Nu vernieuwen' opnieuw."
                cleaned.isBlank() ->
                    "Leeg antwoord\nControleer de API-sleutel en het model-veld."
                else ->
                    "Antwoord niet leesbaar\nProbeer opnieuw of controleer het model-veld."
            }
            return AiAdvisorRunResult(
                generatedAtUtc = payload.dateUtc,
                rawModelResponse = rawResponse,
                suggestions = emptyList(),
                parseError = userMsg
            )
        }

        val suggestionsArr = json.optJSONArray("suggestions")
            ?: return AiAdvisorRunResult(
                generatedAtUtc = payload.dateUtc,
                rawModelResponse = rawResponse,
                suggestions = emptyList(),
                parseError = "Geen 'suggestions'-array in antwoord"
            )

        val results = mutableListOf<AiParamSuggestion>()
        for (i in 0 until suggestionsArr.length()) {
            val obj = suggestionsArr.optJSONObject(i) ?: continue
            results += validateOne(obj, payload)
        }

        // Information only, not a suggestion, so no validation against the ranges. A JSON null
        // (optString would give the text "null") and a blank text both become null.
        val advisoryNote = if (json.isNull("advisoryNoteNl")) null
        else json.optString("advisoryNoteNl", "").trim()
            .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }

        return AiAdvisorRunResult(
            generatedAtUtc = payload.dateUtc,
            rawModelResponse = rawResponse,
            suggestions = results,
            parseError = null,
            advisoryNoteNl = advisoryNote
        )
    }

    private fun validateOne(obj: JSONObject, payload: FclDailyReportPayload): AiParamSuggestion {
        val param = obj.optString("param", "")
        val proposedValue = obj.optDouble("proposedValue", Double.NaN)
        val confidence = obj.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
        val reason = obj.optString("reason", "")
        val evidence = obj.optJSONArray("evidenceFields")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
        } ?: emptyList()

        // The current value always comes from our own data, never from the model.
        val currentValue = payload.activeParams[param]?.active ?: obj.optDouble("currentValue", Double.NaN)

        val spec = FclAiAdvisorRanges.byKey[param]

        fun rejected(reasonNl: String) = AiParamSuggestion(
            param = param, currentValue = currentValue, proposedValue = proposedValue,
            confidence = confidence, reasonNl = reason, evidenceFields = evidence,
            rejected = true, rejectionReasonNl = reasonNl
        )

        if (spec == null) return rejected("Onbekende instelling '$param' — niet in toegestane lijst")
        if (proposedValue.isNaN()) return rejected("Ontbrekende of ongeldige proposedValue")
        // Confidence floor: below this a suggestion is never shown as a card.
        if (confidence < FclAiAdvisorRanges.MIN_SUGGESTION_CONFIDENCE)
            return rejected(
                "Confidence (${(confidence * 100).toInt()}%) onder de drempel van " +
                    "${(FclAiAdvisorRanges.MIN_SUGGESTION_CONFIDENCE * 100).toInt()}%"
            )
        if (!FclAiAdvisorRanges.isInRange(spec, proposedValue))
            return rejected("Waarde $proposedValue buiten absoluut bereik [${spec.min}–${spec.max}]")
        if (currentValue.isNaN())
            return rejected("Geen bekende huidige waarde om tegen te valideren")
        if (reason.isBlank())
            return rejected("Geen onderbouwing opgegeven")
        if (evidence.isEmpty())
            return rejected("Geen evidenceFields opgegeven — onderbouwing niet te verifiëren")

        // Hard rule in code (the prompt says the same): with hypos in the period, nothing that
        // gives more insulin. Timing does not add insulin in total, so it is not on this list.
        val givesMoreInsulin = param != FclAiAdvisorRanges.KEY_TIMING && proposedValue > currentValue
        if (givesMoreInsulin && payload.hypoCount > 0)
            return rejected("Er waren ${payload.hypoCount} hypo's in de periode — geen voorstel dat meer insuline geeft")

        // Step too big: cut it to the largest allowed step in the same direction.
        val limited = FclAiAdvisorRanges.limitStep(spec, currentValue, proposedValue)
        if (kotlin.math.abs(limited - currentValue) < 1e-9)
            return rejected("Voorgestelde stap is te klein of niet toegestaan")
        val note = if (kotlin.math.abs(limited - proposedValue) > 1e-9)
            " [Stap ingekort van ${formatValue(spec, proposedValue)} naar ${formatValue(spec, limited)}]"
        else ""

        return AiParamSuggestion(
            param = param, currentValue = currentValue, proposedValue = limited,
            confidence = confidence, reasonNl = reason + note, evidenceFields = evidence,
            rejected = false, rejectionReasonNl = null
        )
    }

    private fun formatValue(spec: FclAiAdvisorRanges.ParamSpec, value: Double): String =
        if (spec.type == FclAiAdvisorRanges.ValueType.INT) value.toInt().toString() else "%.2f".format(value)

    private fun stripMarkdownFences(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.substringAfter("\n").substringBeforeLast("```").trim()
        }
        return t
    }

    /**
     * Probeert een afgeknipte JSON-string herstelbaar te maken door ontbrekende
     * sluitende haakjes toe te voegen. Dit is een best-effort repair voor het geval
     * het model zijn antwoord halverwege heeft afgekapt (bijv. bij Gemini met een te
     * klein MAX_TOKENS-budget). Geeft de originele string terug als reparatie niet
     * helpt — de JSONObject-parser geeft dan een normale foutmelding.
     *
     * Aanpak: tel open { en [ die nog niet gesloten zijn en voeg de benodigde sluiters toe.
     * Ongeldige trailing komma's (,]) worden ook opgeruimd.
     */
    private fun tryRepairTruncatedJson(s: String): String {
        if (s.isBlank()) return s
        val sb = StringBuilder(s.trimEnd().trimEnd(','))
        val stack = ArrayDeque<Char>()
        var inString = false
        var escape = false
        for (c in sb) {
            when {
                escape           -> escape = false
                c == '\\'        -> if (inString) escape = true
                c == '"'         -> inString = !inString
                inString         -> { /* skip */ }
                c == '{' || c == '[' -> stack.addLast(c)
                c == '}' && stack.lastOrNull() == '{' -> stack.removeLast()
                c == ']' && stack.lastOrNull() == '[' -> stack.removeLast()
            }
        }
        // Sluit eventueel een open string
        if (inString) sb.append('"')
        // Sluit resterende open objecten/arrays in omgekeerde volgorde
        while (stack.isNotEmpty()) {
            sb.append(if (stack.removeLast() == '{') '}' else ']')
        }
        return sb.toString()
    }
}