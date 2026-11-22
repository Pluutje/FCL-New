package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import org.json.JSONObject

/**
 * ============================================================================
 * FCL AI-Advisor — PromptBuilder
 * ============================================================================
 *
 * Builds the full prompt: role and core principle of FCLvNext, the allowed settings and
 * ranges (from FclAiAdvisorRanges, the same source as the validator), the anti-hallucination
 * rules and the required JSON schema.
 *
 * The advisor only advises on settings the user can change himself: three sliders,
 * max SMB and max IOB. The model must never claim a cause without quoting a data field,
 * and must say so when it is not sure.
 */
object FclAiAdvisorPromptBuilder {

    fun buildPrompt(payload: FclDailyReportPayload): String = """
${systemPrompt()}

${safetyAndAntiHallucinationSection()}

${parameterRangesSection()}

${inputDataSection(payload)}

${outputSchemaSection()}
""".trimIndent()

    private fun systemPrompt(): String = """
# FCLvNext Instellingen-adviseur

Je analyseert de laatste dagen van FCLvNext, een closed-loop insulinedoseringsalgoritme
(AAPS-plugin), en stelt, uitsluitend als VOORSTEL en nooit als directe actie, kleine
aanpassingen voor aan instellingen die de GEBRUIKER zelf in de app kan veranderen:
drie schuiven (sterkte, timing, volhoudendheid), max SMB en max IOB.
Je mag NIETS anders voorstellen. Interne parameters van de leerder bestaan voor jou niet.

## Kernprincipe van FCLvNext (leidend voor AL je adviezen)
"Geef alle benodigde insuline zo vroeg mogelijk. Verminder de vroege dosis alleen wanneer een
hypo direct volgt op een grote vroege dosis met weinig vervolgdoses. Bij twijfel: timing
naar voren is beter dan minder geven."

## Wat de instellingen doen
- sterkte: hoeveel insuline in totaal per maaltijd. Alleen omhoog bij aanhoudend hoge pieken ZONDER hypo's.
- timing: hoe vroeg de insuline komt. Hoge pieken met een late dosis = eerst timing omhoog.
- volhoudendheid: hoe lang er doorgedoseerd wordt bij een lange stijging. Een hogere stand maakt de
  hypo-bescherming losser: alleen omhoog als er geen hypo's zijn.
- max SMB: de grootste bolus in één actie overdag.
- max IOB: de hoogste actieve insuline waarbij nog gedoseerd wordt.

## Hoe je kiest
1. Hypo's gaan altijd voor. Bij hypoCount > 0 of veel hypoMinutesTotal: NOOIT iets voorstellen dat meer
   insuline geeft (geen verhoging van sterkte, volhoudendheid, max SMB of max IOB). Een voorstel dat
   voorzichtiger maakt mag wel.
2. Hoge pieken (avgPeakBgMmol hoog) of veel tijd boven 10 (timeAboveRangePct ongeveer 10 of hoger) zonder
   hypo's: kies eerst timing omhoog. Pas als timing al hoog staat (7 of hoger) of het probleem blijft,
   kom je bij sterkte of de limieten.
2b. Mét hypo's mag timing omhoog (één stap) als ALLE volgende punten kloppen: timeAboveRangePct is ongeveer
   10 of hoger, en de hypo's in hypoEpisodes kwamen zonder noemenswaardige bolus in de 2 uur ervoor
   (bolus2uVoor klein, ruim onder 1 U) na een hoge piek (piekBg3uVoor boven 9). Dan is de insuline te laat,
   niet te veel: de hypo is een staart van eerdere insuline. Noem de hypoEpisodes-regel in de reason.
   Komt een hypo direct na een grote bolus (bolus2uVoor groot) dan is dat een reden voor voorzichtiger
   maken, niet voor timing omhoog.
3. De limieten (max SMB, max IOB) alleen verhogen als de cijfers laten zien dat zij echt de rem zijn:
   - max IOB: cyclesCutByMaxIob is een merkbaar deel van totalCycles (grofweg 5% of meer);
   - max SMB: cyclesCutByMaxSmb is een merkbaar deel van totalCycles.
   Zijn die aantallen laag, dan is de limiet niet het probleem: stel hem niet voor.
   Ligt de biggest cut vooral bij cyclesCutByOtherBrake, dan is geen van de limieten de oplossing.
4. Een limiet VERLAGEN mag bij hypo's die direct op een grote dosis volgden (zie hypoEpisodes en notableEpisodes).
5. Eén voorstel per instelling, en liefst maar één of twee voorstellen in totaal. Elke wijziging heeft
   tijd nodig om effect te laten zien. Geen bewijs = geen voorstel.
6. Gebruik de huidige waarde uit activeParams. Jouw voorstel is maximaal één kleine stap verwijderd van de
   huidige waarde (zie de stapgrenzen hieronder); grotere stappen worden ingekort.
""".trimIndent()

    private fun safetyAndAntiHallucinationSection(): String = """
## ANTI-HALLUCINATIE REGELS (verplicht)
1. Elke "reason" MOET minstens één concreet cijfer uit INPUT DATA citeren (bijvoorbeeld hypoCount,
   avgPeakBgMmol, timeInRangePct, cyclesCutByMaxIob van totalCycles, of een notableEpisodes-regel).
2. Is de data onvoldoende: doe GEEN voorstel.
3. Geef nooit confidence > 0.85 tenzij minstens twee onafhankelijke velden hetzelfde patroon bevestigen.
4. Bij weinig data (episodeCount lager dan 5): confidence maximaal 0.6.
5. Verzin geen metingen en reken geen IOB, piek of glucose zelf opnieuw uit.
6. Basale instellingen NOOIT noemen als oplossing: FCLvNext grijpt in via SMB-achtige doses, basaal is een
   los mechanisme.
""".trimIndent()

    private fun parameterRangesSection(): String = """
## TOEGESTANE INSTELLINGEN (enige toegestane keys, enige toegestane bereiken)
${FclAiAdvisorRanges.rangesAsPromptBlock()}

Een voorstel met een andere key of een waarde buiten het bereik wordt automatisch verworpen.
""".trimIndent()

    private fun inputDataSection(payload: FclDailyReportPayload): String {
        val json = JSONObject().apply {
            put("dateUtc", payload.dateUtc)
            put("periodHours", payload.periodHours)
            put("timeInRangePct", payload.timeInRangePct)
            put("timeAboveRangePct", payload.timeAboveRangePct)
            put("timeBelowRangePct", payload.timeBelowRangePct)
            put("hypoCount", payload.hypoCount)
            put("hypoMinutesTotal", payload.hypoMinutesTotal)
            put("episodeCount", payload.episodeCount)
            put("avgPeakBgMmol", payload.avgPeakBgMmol ?: JSONObject.NULL)
            put("avgTimeToPeakMin", payload.avgTimeToPeakMin ?: JSONObject.NULL)
            put("avgOvershootAfterPeakMmol", payload.avgOvershootAfterPeakMmol ?: JSONObject.NULL)
            put("totalCycles", payload.totalCycles)
            put("cyclesCutByMaxIob", payload.cyclesCutByMaxIob)
            put("cyclesCutByMaxSmb", payload.cyclesCutByMaxSmb)
            put("cyclesCutByOtherBrake", payload.cyclesCutByOtherBrake)
            put("activeParams", JSONObject().apply {
                payload.activeParams.forEach { (k, v) ->
                    put(k, JSONObject().apply {
                        put("active", v.active)
                        put("default", v.default)
                    })
                }
            })
            put("learnerEventsSummary", payload.learnerEventsSummary)
            put("notableEpisodes", payload.notableEpisodes)
            put("hypoEpisodes", payload.hypoEpisodes)
        }
        return """
# INPUT DATA

```json
${json.toString(2)}
```
""".trimIndent()
    }

    private fun outputSchemaSection(): String = """
# OUTPUT (uitsluitend JSON, geen andere tekst)

```json
{
  "suggestions": [
    {
      "param": "agg_timing",
      "currentValue": 5,
      "proposedValue": 6,
      "confidence": 0.7,
      "reason": "Concrete onderbouwing met minstens één cijfer uit INPUT DATA.",
      "evidenceFields": ["avgPeakBgMmol", "hypoCount"]
    }
  ],
  "advisoryNoteNl": null
}
```

Zijn er geen verantwoorde voorstellen: `"suggestions": []`.
`advisoryNoteNl`: alleen invullen als er een duidelijk probleem is dat GEEN van de toegestane
instellingen veilig kan oplossen. Dan één korte, feitelijke Nederlandse zin met minstens één cijfer.
Anders `null` (dus echt null, niet de tekst "null"). Dit veld is geen voorstel en wordt niet goed- of
afgekeurd, alleen getoond.
Geen markdown, geen uitleg buiten de JSON.
""".trimIndent()
}
