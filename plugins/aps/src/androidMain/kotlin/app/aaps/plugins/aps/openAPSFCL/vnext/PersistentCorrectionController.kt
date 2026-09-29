package app.aaps.plugins.aps.openAPSFCL.vnext

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Stateful persistent correction loop:
 * - Detect persistent-high + stable OR slowly-drifting plateau
 * - Fire micro-shot (<= 30% maxBolus, opgeschaald bij langdurig hoog)
 * - Start cooldown voor N cycli (1-2) na elke dosis
 *
 * AANPASSING: stableSlopeAbs verruimd zodat ook langzaam dalende BG
 * die persistent hoog blijft wordt herkend. Plus: naarmate de episode
 * langer duurt (episodeCycles groeit, zie kdoc bij dat veld), wordt de
 * dosis 20-30% groter — maar nooit meer dan maxBolusFraction * maxBolusU.
 */
class PersistentCorrectionController(
    private val cooldownCycles: Int = 2,
    private val maxBolusFraction: Double = 0.30,
) {
    private var cooldownLeft: Int = 0
    private var lastFireTs: Long = 0L
    private var persistentCounter: Int = 0

    // 29/09/2026 (de gebruiker — analyse CSV-log 607307, maaltijd 29/9 18:34-21:23):
    // eigen "plateau na piek"-hysterese, exacte kopie van topPlateauConfirm/
    // topPlateauHold in FCLvNext.kt (zie kdoc daar: "TOP PLATEAU CONFIRM
    // (hysterese)"). AANLEIDING: na een snelle stijging (18:34→20:33, 6,0→12,5)
    // bleef de BG bijna een uur vlak op 12,3-12,5 hangen zonder dat de gewone
    // candidacy-eis (slopeOk EN accelOk, beide op de trage curve-fit) ooit
    // bevestigde -- de curve-fit-versnelling ("accel") bleef tot 20:54-21:08
    // net buiten de ±stableAccelAbs-marge hangen, ook nadat de rauwe/snelle
    // recentSlope/recentDelta5m allang vlak stonden. Duplicatie i.p.v. hergebruik
    // van FCLvNext.kt's eigen topPlateauConfirm: zelfde afweging als bij
    // FclTrustedBuild.kt/TrustedBuild.kt en overrideStrengthFraction -- deze
    // klasse werkt op primitives en kent FCLvNext.kt's cyclus-volgorde niet (de
    // echte topPlateauConfirm wordt daar pas ná de persist-aanroep berekend).
    private var plateauConfirm: Int = 0
    private var plateauHold: Int = 0

    // 26/09/2026 (de gebruiker — analyse persist_escalation uit CSV-log 607307):
    // apart van persistentCounter, die met opzet ruizig is (zakt al bij 1
    // afwijkende cyclus, zie kdoc bij persistentCounter++ hieronder) en tijdens
    // elke cooldown bovendien STILSTAAT (de cooldown-early-return hierboven raakt
    // persistentCounter niet aan). Omdat escalationFactor tot 26/09/2026 direct op
    // persistentCounter leunde, kwam een aaneengesloten plateau van uren nooit
    // verder dan ~+10%: elke cooldown bevriest de opbouw, en elke ruis-cyclus erna
    // zet ~1 cyclus bevestiging weer terug — per saldo netto nauwelijks netto groei.
    // episodeCycles is de vervanger: hij groeit ELKE cyclus door (óók tijdens
    // cooldown) zolang persistentCounter minstens confirmCycles is (dus zolang de
    // episode ECHT bevestigd is/blijft), en reset alleen naar 0 wanneer
    // persistentCounter volledig tot 0 terugzakt (een echt, meerdere cycli durend
    // verdwijnen van de candidacy — een oprecht signaal dat het plateau voorbij
    // is). Bij tussenwaarden (aan het opbouwen of licht aan het afbouwen, maar nog
    // niet terug op 0) blijft episodeCycles gewoon staan — één ruis-cyclus kan het
    // dus niet meer terugzetten. Backtest (Python-reimplementatie, volledige
    // logweek 607307): exact dezelfde vuurmomenten/dosisbasis als voorheen (0
    // regressies, escalatie gaat nooit omlaag t.o.v. voorheen), en de laagste
    // Bg waarbij de escalatie hoger uitvalt dan voorheen is 6.7 mmol/L — bij de
    // 3 controlenachten met bg_mean 5.9-6.2 blijft de escalatie exact zoals
    // voorheen (1.0x, geen enkele wijziging).
    private var episodeCycles: Int = 0

    data class Result(
        val active: Boolean,
        val fired: Boolean,
        val doseU: Double,
        val cooldownLeft: Int,
        val reason: String,
        val persistentCounter: Int = 0,
        val escalationFactor: Double = 1.0,
        val episodeCycles: Int = 0
    )

    fun tickAndMaybeFire(
        tsMillis: Long,
        bgMmol: Double,
        targetMmol: Double,
        deltaToTarget: Double,
        slope: Double,
        accel: Double,
        consistency: Double,
        iob: Double,
        iobRatio: Double,
        maxBolusU: Double,

        // 29/09/2026 (de gebruiker) — voor de plateau-na-piek-hysterese hieronder,
        // zie kdoc bij plateauConfirm/plateauHold. Zelfde velden als elders in
        // FCLvNext.kt (ctx.recentSlope/ctx.recentDelta5m) -- snel/ruw signaal,
        // reageert sneller dan de trage curve-fit slope/accel hierboven.
        recentSlope: Double = 0.0,
        recentDelta5m: Double = 0.0,

        minDeltaToTarget: Double = 1.6,
        stableSlopeAbs: Double = 0.25,
        stableAccelAbs: Double = 0.06,
        minConsistency: Double = 0.45,
        confirmCycles: Int = 2,

        // 29/09/2026 (de gebruiker) — drempels voor de "vlak"-detectie van het
        // plateau-hysterese-mechanisme, exact gelijk aan plateauNow in FCLvNext.kt.
        plateauSlopeAbs: Double = 0.30,
        plateauDelta5mAbs: Double = 0.04,
        // "risingAgain"-drempels om de hysterese meteen te resetten zodra de
        // snelle indicator weer duidelijk stijgt -- ook exact gelijk aan
        // risingAgainNow in FCLvNext.kt.
        risingAgainDelta5m: Double = 0.06,
        risingAgainSlope: Double = 0.20,

        minDoseU: Double = 0.05,
        iobRatioHardStop: Double = 0.55,

        // 24/09/2026 (de gebruiker — analyse 23/9 appeltaart+ribeye-dag):
        // extern al berekende, ALTERNATIEVE candidacy-voorwaarde, OR'd bij de
        // ingebouwde vlak/dalend-definitie hieronder. Bestaat zodat een tweede
        // instantie van deze klasse (zie sustainedRiseCtrl in FCLvNext.kt) ook
        // een AANHOUDENDE STIJGING als "persistent hoog" kan herkennen — iets
        // wat de ingebouwde slopeOk-eis (slope <= stableSlopeAbs) bewust nooit
        // toelaat, want die is voor het vlak/dalend scenario getuned. De
        // dosis-/escalatieformule hieronder blijft ONGEWIJZIGD voor beide
        // paden — alleen de candidacy-poort wordt verruimd. Default false:
        // bestaande aanroepen (de "vlak/dalend"-persistCtrl) blijven exact
        // hetzelfde gedrag vertonen.
        overrideCandidate: Boolean = false
    ): Result {

        // Plateau-hysterese bijwerken — ELKE cyclus, ook tijdens cooldown (zelfde
        // reden als bij episodeCycles: een al vastgesteld plateau mag niet
        // verdwijnen alleen omdat de dosis-cooldown toevallig loopt). Zie kdoc
        // bij plateauConfirm/plateauHold hierboven.
        val plateauNow = recentSlope <= plateauSlopeAbs && abs(recentDelta5m) <= plateauDelta5mAbs
        val risingAgainNow = recentDelta5m >= risingAgainDelta5m || recentSlope >= risingAgainSlope
        if (risingAgainNow) {
            plateauConfirm = 0
            plateauHold = 0
        } else if (plateauNow) {
            plateauConfirm = (plateauConfirm + 1).coerceAtMost(5)
            plateauHold = 2
        } else {
            if (plateauHold > 0) plateauHold -= 1
            if (plateauHold == 0) plateauConfirm = 0
        }
        // Tijdens de 2-cycli hold-periode (net na een bevestigd plateau, maar deze
        // cyclus zelf niet meer "vlak") tellen we in ieder geval als bevestigd
        // (sterkte 2) mee -- anders zou de dynamische drempel hieronder een
        // plateau dat net een cyclus hikt weer helemaal ongeldig maken.
        val plateauStrength = if (plateauHold > 0) max(plateauConfirm, 2) else plateauConfirm

        // Cooldown countdown — episodeCycles blijft meegroeien tijdens cooldown
        // (zie kdoc bij het veld): een plateau dat al bevestigd was, blijft dat
        // ook tijdens de verplichte 1-2 cycli pauze na elke dosis.
        if (cooldownLeft > 0) {
            cooldownLeft -= 1
            if (persistentCounter >= confirmCycles) episodeCycles++
            return Result(
                active = true,
                fired = false,
                doseU = 0.0,
                cooldownLeft = cooldownLeft,
                reason = "PERSIST: cooldown ($cooldownLeft left)",
                persistentCounter = persistentCounter,
                episodeCycles = episodeCycles
            )
        }

        // Persistent hoog definitie:
        // - BG voldoende boven target
        // - slope stabiel OF langzaam dalend (tot -0.60 mmol/5min)
        //   Reden: bij persistente hyperglykemie daalt BG wel heel traag
        //   maar is hij structureel te hoog. -0.60 = ~12 mmol/uur daling,
        //   wat bij BG=12 nog steeds uren duurt voor target bereikt wordt.
        // - geen sterke versnelling (accel stabiel)
        val slopeOk = slope <= stableSlopeAbs && slope >= -0.60
        val slopeAndAccelOk = slopeOk && abs(accel) <= stableAccelAbs

        // 29/09/2026 (de gebruiker) — plateau-na-piek als TWEEDE, dynamische poort
        // naast slopeAndAccelOk. AANLEIDING: zie kdoc bij plateauConfirm hierboven.
        // Bij het 29/9-incident bleef niet alleen slope, maar vooral accel nog
        // 20+ minuten net buiten de marge terwijl BG allang vlak lag.
        //
        // DYNAMISCH (de gebruiker, 29/09/2026): hoe verder deltaToTarget boven
        // minDeltaToTarget uitkomt, hoe minder plateau-bevestiging er nodig is —
        // zelfde filosofie als deltaFactor/escalationFactor verderop (hoe hoger/
        // langduriger, hoe groter de correctie). Drie treden i.p.v. één harde
        // knip, getuned op de volledige week-CSV 607307:
        //   delta <  3,0 -> poort dicht (plateauStrength kan nooit 99 halen) —
        //                   zelfde veiligheidsvloer als de oude, harde 3,0-grens,
        //                   voorkomt correcties vlak bij target.
        //   delta >= 3,0 -> plateauStrength >= 3 (bijna volledig bevestigd)
        //   delta >= 4,5 -> plateauStrength >= 2 (net bevestigd volstaat)
        //   delta >= 6,0 -> plateauStrength >= 1 (eerste vlakke cyclus volstaat —
        //                   bij zo'n grote afwijking mag het niet nog eens
        //                   20+ minuten wachten op de trage curve-fit)
        // Backtest (volledige week 607307): lost het 29/9-incident volledig op
        // (4 correcties door het plateau heen i.p.v. 0), totale persist-dosis
        // over de week +21% (t.o.v. +39% bij een simpele vaste 3,0-grens zonder
        // trapsgewijze opbouw) en de laagste Bg waarbij dit extra vuurt is 7,8
        // mmol/L (delta 2,4) — geen enkele extra vuring dichter bij target. Op de
        // rustige dagen (24/9, 27/9) verandert er niets; op 26/9 en 28/9 komt er
        // 1,3-4,0U per dag bij, telkens bij Bg die nog duidelijk boven target hing.
        val requiredPlateauStrength = when {
            deltaToTarget >= 6.0 -> 1
            deltaToTarget >= 4.5 -> 2
            deltaToTarget >= 3.0 -> 3
            else -> Int.MAX_VALUE
        }
        val plateauOk = plateauStrength >= requiredPlateauStrength

        val persistentCandidate =
            (deltaToTarget >= minDeltaToTarget &&
                (slopeAndAccelOk || plateauOk) &&
                consistency >= minConsistency) ||
                overrideCandidate

        // 21/09/2026 (de gebruiker) — teller bij een afwijkende cyclus niet meer
        // hard naar 0 laten springen, maar met 1 laten zakken. Aanleiding: een
        // 2+ uur durend Bg-plateau (7:54-10:04) bleef vrijwel onbehandeld omdat
        // de ruwe slope/versnelling rond een vlakke Bg voortdurend een fractie
        // buiten de vrij strakke marges wiebelt — genoeg om de teller, vlak
        // vóór confirmCycles, telkens weer op 0 te zetten, waardoor bevestiging
        // in de praktijk bijna nooit werd gehaald ondanks een overduidelijk
        // aanhoudend te hoge, vlakke Bg. Met een geleidelijke afbouw wist één
        // afwijkende cyclus niet meer alle eerder opgebouwde bevestiging; een
        // ECHT voorbije periode (meerdere afwijkende cycli op rij, bijv. een
        // nieuwe stijging) telt nog steeds net zo snel af als hij opbouwde.
        if (persistentCandidate) {
            persistentCounter++
        } else {
            persistentCounter = (persistentCounter - 1).coerceAtLeast(0)
        }

        // episodeCycles: zie kdoc bij het veld. Groeit door zolang bevestigd
        // (>=confirmCycles), reset alleen bij een echte, volledige terugval naar 0.
        if (persistentCounter >= confirmCycles) {
            episodeCycles++
        } else if (persistentCounter == 0) {
            episodeCycles = 0
        }

        val persistentConfirmed = persistentCounter >= confirmCycles

        if (!persistentConfirmed) {
            return Result(
                active = false,
                fired = false,
                doseU = 0.0,
                cooldownLeft = 0,
                reason = "PERSIST: building (${persistentCounter}/${confirmCycles})",
                persistentCounter = persistentCounter,
                episodeCycles = episodeCycles
            )
        }

        // Dosis model:
        // Base: afhankelijk van deltaToTarget en iobRatio
        // Opschaling: naarmate counter groeit (BG al langer hoog),
        //   dosis 20-30% groter. Na 4 bevestigde cycli (= ~20 min) max opschaling.
        val deltaFactor = (deltaToTarget / 3.0).coerceIn(0.0, 1.0)
        val iobFactor = (1.0 - (iobRatio / iobRatioHardStop)).coerceIn(0.0, 1.0)
        val baseRaw = minDoseU + (maxBolusU * maxBolusFraction - minDoseU) *
            (0.65 * deltaFactor + 0.35 * iobFactor)

        // Opschaling: elke 2 extra cycli van de episode na confirmCycles +10%, max +30%
        // episodeCycles=2 (net bevestigd) → factor 1.0
        // episodeCycles=4 → factor 1.10
        // episodeCycles=6 → factor 1.20
        // episodeCycles=8+ → factor 1.30 (max)
        // 26/09/2026 (de gebruiker): was persistentCounter i.p.v. episodeCycles —
        // zie kdoc bij het veld voor waarom dat de opschaling structureel afkapte.
        val extraCycles = (episodeCycles - confirmCycles).coerceAtLeast(0)
        val escalationFactor = (1.0 + (extraCycles / 2) * 0.10).coerceAtMost(1.30)

        val dose = (baseRaw * escalationFactor)
            .coerceAtLeast(0.0)
            .coerceAtMost(maxBolusU * maxBolusFraction)

        if (dose < minDoseU) {
            return Result(
                active = true, fired = false, doseU = 0.0, cooldownLeft = 0,
                reason = "PERSIST: computed too small",
                persistentCounter = persistentCounter, episodeCycles = episodeCycles
            )
        }

        lastFireTs = tsMillis
        cooldownLeft = cooldownCycles

        return Result(
            active = true,
            fired = true,
            doseU = dose,
            cooldownLeft = cooldownLeft,
            reason = "PERSIST: fire dose=${"%.2f".format(dose)}U delta=${"%.2f".format(deltaToTarget)} " +
                "iobR=${"%.2f".format(iobRatio)} esc=${"%+.0f".format((escalationFactor - 1.0) * 100)}%",
            persistentCounter = persistentCounter,
            episodeCycles = episodeCycles,
            escalationFactor = escalationFactor
        )
    }
}
