package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FclTempOverrideSettings (11/09/2026, de gebruiker) — lichte, kale opslag
 * (geen officiële AAPS-preference/Room, zelfde patroon als
 * FclNachtOvergangSettings.kt) voor de "Tijdelijke aanpassing" (Temp
 * Override): een door de gebruiker zelf te starten, tijdelijke op- of
 * afschaling van elke commit, met een vaste tijdsduur en een vloeiende
 * uitloop aan het eind.
 *
 * AANLEIDING: na het jojo-effect van 9-11/09/2026 (zie de kdoc bij
 * POST_HYPO_BRAKE_CATCHUP_RISE_AFTER_LOW in FCLvNext.kt) vroeg de gebruiker
 * om een manier om ZELF, situationeel, tijdelijk voorzichtiger (bij een
 * dreigende hypo die weggegeten wordt) of juist wat agressiever (bij een
 * uitgebreide maaltijd) te doseren, zonder de vaste instellingen (max bolus,
 * max IOB, agressiviteit) blijvend aan te passen — die vaste instellingen
 * hebben over de afgelopen maand een hypo-percentage van "max enkele
 * procenten" opgeleverd en mogen daarom niet zomaar verlaagd worden (zie
 * git-historie van dit gesprek, 11/09/2026).
 *
 * BEWUST GEEN officiële AAPS-key: zelfde reden als FclNachtOvergangSettings
 * (een nieuwe corekey toevoegen vereist code buiten deze plugin-map).
 *
 * WERKING (effectiveMultiplier / status hieronder):
 * - Direct na start() staat de multiplier vlak op targetPct/100.0.
 * - Dat blijft zo tot TAPER_START_FRACTION (75%) van de ingestelde duur is
 *   verstreken.
 * - Daarna loopt de multiplier in de resterende 25% vloeiend (kwadratische
 *   ease-out) terug naar 1.0 (neutraal) — zowel een verlaging (<100%) als
 *   een verhoging (>100%) landt zo zacht op het normale niveau, in plaats
 *   van abrupt te stoppen.
 * - Na het verstrijken van de volledige duur is de override automatisch
 *   uitgeschakeld (auto-expire): status()/effectiveMultiplier() zet active
 *   dan zelf terug naar false en geeft 1.0 terug, zodat er geen aparte
 *   "vergeten uit te zetten"-toestand kan blijven hangen.
 *
 * TOEPASSING: FCLvNext.kt vermenigvuldigt commandedDose met
 * effectiveMultiplier() op ÉÉN vaste plek — direct na het einde van het
 * fallback-omslag-veto-blok, vóór de reserve-pool-logica en vóór de eerste
 * hypoProtection()-aanroep (zie kdoc daar). Zo werkt de schaling
 * proportioneel op de volledige voorgestelde dosis, en blijven alle
 * veiligheidscontroles ná dat punt (hypoProtection x2, reserve, topGuard,
 * post-hypo-brake, IOB-plafond, piek-benadering-taper) onverkort op de
 * al-geschaalde waarde werken — de override kan dus nooit een guard
 * omzeilen, alleen het startpunt ervoor verschuiven.
 */
object FclTempOverrideSettings {

    private const val PREFS = "fcl_temp_override_settings"
    private const val KEY_PCT = "percentage"
    private const val KEY_DURATION_MIN = "duration_minutes"
    private const val KEY_ACTIVE = "active"
    private const val KEY_START_MS = "start_time_ms"

    /** Instelbereik percentage: 50..150%, in stappen van 10 (UI-conventie, niet hier afgedwongen). */
    const val MIN_PCT = 50
    const val MAX_PCT = 150
    const val STEP_PCT = 10
    const val DEFAULT_PCT = 100

    /** Instelbereik duur: 30..720 minuten (0,5..12 uur). */
    const val MIN_DURATION_MIN = 30
    const val MAX_DURATION_MIN = 720
    const val DEFAULT_DURATION_MIN = 120

    /** Op dit punt (fractie van de totale duur) begint de vloeiende terugloop naar 100%. */
    private const val TAPER_START_FRACTION = 0.75

    /** Eindstand voor deze cyclus, klaar om zowel in FCLvNext.kt als in de CSV-log gebruikt te worden. */
    data class Status(
        val active: Boolean,
        val targetPct: Int,
        val effectiveMul: Double,
        val remainingMinutes: Int
    )

    fun getPercentage(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PCT, DEFAULT_PCT)
            .coerceIn(MIN_PCT, MAX_PCT)

    fun getDurationMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_DURATION_MIN, DEFAULT_DURATION_MIN)
            .coerceIn(MIN_DURATION_MIN, MAX_DURATION_MIN)

    /** Ruwe opgeslagen active-vlag, ZONDER verval-check — gebruik status() voor UI/logica. */
    fun isActiveRaw(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVE, false)

    private fun getStartTimeMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_START_MS, 0L)

    /**
     * Start (of vervangt) een lopende override met een nieuw percentage en
     * duur. Overschrijft een eventueel al actieve override — er is maar één
     * override tegelijk, opnieuw starten zet de klok (en het percentage)
     * simpelweg opnieuw.
     */
    fun start(context: Context, percentage: Int, durationMinutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PCT, percentage.coerceIn(MIN_PCT, MAX_PCT))
            .putInt(KEY_DURATION_MIN, durationMinutes.coerceIn(MIN_DURATION_MIN, MAX_DURATION_MIN))
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_START_MS, System.currentTimeMillis())
            .apply()
    }

    /** Zet de override direct uit (handmatige Stop-knop). */
    fun stop(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, false)
            .apply()
    }

    /**
     * Berekent de eindstand voor "nu" (nowMs). Zet, bij het passeren van de
     * volledige duur, de opgeslagen active-vlag zelf terug naar false
     * (auto-expire) — een volgende aanroep (of de UI) ziet dan meteen de
     * juiste, uitgeschakelde staat.
     */
    fun status(context: Context, nowMs: Long): Status {
        val rawActive = isActiveRaw(context)
        val targetPct = getPercentage(context)
        if (!rawActive) return Status(active = false, targetPct = targetPct, effectiveMul = 1.0, remainingMinutes = -1)

        // 24/09/2026 (de gebruiker) — gepauzeerd: reken met de klok bevroren op het moment van
        // pauzeren, zodat zowel de resterende tijd als de multiplier exact blijven hangen totdat
        // resume() de opgeslagen starttijd zelf opschuift (zie kdoc bij pause()/resume()).
        val paused = isPaused(context)
        val effectiveNowMs = if (paused) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_PAUSE_STARTED_MS, nowMs)
        } else nowMs

        val startMs = getStartTimeMs(context)
        val durationMs = getDurationMinutes(context) * 60_000L
        val elapsedMs = effectiveNowMs - startMs

        if (!paused && (elapsedMs >= durationMs || elapsedMs < 0L)) {
            // Volledige duur verstreken (of een corrupte/toekomstige starttijd) -> auto-expire.
            stop(context)
            return Status(active = false, targetPct = targetPct, effectiveMul = 1.0, remainingMinutes = -1)
        }

        val targetFrac = targetPct / 100.0
        val flatEndMs = (durationMs * TAPER_START_FRACTION).toLong()

        val mul = if (elapsedMs <= flatEndMs) {
            targetFrac
        } else {
            val taperDurMs = durationMs - flatEndMs
            val taperFrac = if (taperDurMs <= 0L) 1.0
                else ((elapsedMs - flatEndMs).toDouble() / taperDurMs).coerceIn(0.0, 1.0)
            // Kwadratische ease-out: landt vloeiend (afgeleide 0) op 1.0 bij taperFrac=1.0,
            // in plaats van abrupt te stoppen.
            val eased = (1.0 - taperFrac) * (1.0 - taperFrac)
            1.0 + (targetFrac - 1.0) * eased
        }

        val remainingMinutes = ((durationMs - elapsedMs) / 60_000L).toInt().coerceAtLeast(0)
        return Status(active = true, targetPct = targetPct, effectiveMul = mul, remainingMinutes = remainingMinutes)
    }

    /** Korte helper voor de FCLvNext.kt-hook: alleen de multiplier, 1.0 als de override niet actief is. */
    fun effectiveMultiplier(context: Context, nowMs: Long): Double = status(context, nowMs).effectiveMul

    // ═══════════════════════════════════════════════════════════════════
    // PRESETS + EXTRA-INSULINE-PORTIES (24/09/2026, de gebruiker)
    // ═══════════════════════════════════════════════════════════════════
    // AANLEIDING: 22/23-9-analyse (appeltaart + ribeye-dag) — de gebruiker
    // moest die dag meerdere keren handmatig bijspuiten omdat "percentage
    // van de normale dosis" (hierboven) niet volstaat als je vooraf al WEET
    // dat er een vaste extra hoeveelheid nodig is (of, omgekeerd, minder —
    // bijv. vóór het sporten, waar een bewust HOGERE Bg met LAGERE IOB
    // juist gewenst is omdat hoge IOB tijdens inspanning bijna altijd tot
    // een hypo leidt).
    //
    // ONTWERPKEUZES (uit het gesprek, 24/09/2026):
    // - 3 vaste, hernoembare presets (slot 0/1/2). Elk preset combineert het
    //   BESTAANDE percentage+duur-mechanisme (hierboven, ongewijzigd) MET
    //   1-3 EXTRA-INSULINE-PORTIES, elk met een EIGEN, apart instelbare
    //   hoeveelheid (portionAmountsU, mag negatief zijn) EN een eigen,
    //   apart instelbare vertraging (portionDelaysMin) — beide los van het
    //   percentage/duur-mechanisme. Ronde 2 (na eerdere feedback): niet
    //   langer één totaalbedrag gelijk verdeeld over de porties — de
    //   gebruiker wil elke portie zelf volledig kunnen instellen.
    // - Toepassing: FCLvNext.kt telt een vervallen portie ADDITIEF op bij
    //   commandedDose, NA alle normale correcties (dezelfde late
    //   invoegplek als de sustainedRiseCtrl-top-up) — "boven op de
    //   standaard gedoseerde waarde, dus na alle correcties", letterlijk
    //   de eis van de gebruiker. De bestaande basisveiligheden (downtrend-
    //   gate, absolute veiligheidscap) blijven wel gewoon gelden.
    // - AFBOUW, EXPLICIET ASYMMETRISCH (na 2 correctierondes met de
    //   gebruiker vastgesteld):
    //     • Alleen bij een schema waarvan de SOM van de geplande porties
    //       POSITIEF is: als het algoritme zelf structureel onder een
    //       drempel blijft doseren (commitFraction laag) nadat er al een
    //       portie is gegeven, is de aanname achter de knop ("er komt een
    //       stijging aan") kennelijk niet uitgekomen — de nog niet
    //       afgeleverde porties vervallen dan.
    //     • Bij een percentage <100% OF een schema met een negatieve
    //       (of nul-)som: GEEN enkele slimme afbouw. De gebruiker koos dit
    //       bewust (bijv. vóór het sporten) en de bestaande
    //       hypo-bescherming van het algoritme zelf (los van deze
    //       override) blijft toch al gewoon actief. Loopt dus gewoon af
    //       zoals ingesteld, met alleen de bestaande, normale taper naar
    //       100% in het laatste kwart van de duur (hierboven, ongewijzigd)
    //       voor het percentage-deel.
    // - PAUZEREN (24/09/2026, ronde 2): de gebruiker wil naast Stop ook een
    //   Pauze-knop. Pauzeren bevriest letterlijk de klok — status()/
    //   effectiveMultiplier() rekenen tijdens een pauze met de bevroren
    //   verstreken tijd op het moment van pauzeren (dus zowel de resterende
    //   tijd als de op dat moment geldende multiplier blijven exact
    //   hangen), en nextDuePortionRequest() levert niks af zolang gepauzeerd.
    //   resume() schuift start_time_ms en active_extra_start_ms allebei op
    //   met de gepauzeerde duur, zodat na hervatten alles precies verder
    //   loopt alsof de pauze niet had plaatsgevonden.
    // - RETRY + EROSIE VAN HET OPENSTAANDE DEEL (24/09/2026, ronde 3 — na een
    //   controlevraag van de gebruiker over 2 losse gaten in het oude
    //   one-shot-afleverschema):
    //     • GAT 1 (negatieve portie zonder iets om van af te trekken): het
    //       oude schema telde een portie precies 1x op bij commandedDose en
    //       markeerde 'm meteen als "delivered", ongeacht wat er daarna nog
    //       met commandedDose gebeurde. Een negatieve portie op een moment
    //       zonder positieve bolus om van af te trekken kwam zo op
    //       commandedDose = 0.0 (coerceAtLeast(0.0)) terecht en verdween
    //       daarmee volledig en onherroepelijk.
    //     • GAT 2 (grote portie deels/geheel geblokkeerd): dezelfde
    //       coerceAtLeast(0.0) hierboven, de DOWN-TREND FINAL DOSE GATE, de
    //       absolute veiligheidscap (maxSMB * 1.5) en de Herstart-blokkade
    //       kunnen commandedDose NA het optellen van de portie alsnog
    //       (deels) terugzetten — ook dat werd nooit teruggekoppeld, dus een
    //       portie die net dat cyclus geblokkeerd werd, kwam nooit meer
    //       terug.
    //     • OPLOSSING: OverridePortion houdt nu remainingU bij (start op
    //       amountU, geen apart delivered-veld meer). nextDuePortionRequest()
    //       vraagt bij elke vervallen-cyclus het VOLLEDIGE remainingU
    //       opnieuw aan (dus met automatische retry zolang er nog iets open-
    //       staat) — FCLvNext.kt rekent na alle latere veiligheidsstappen
    //       (down-trend-gate, cap, herstart-blokkade), vlak vóór Execution,
    //       via een diff van commandedgDose uit hoeveel er DIT cyclus
    //       daadwerkelijk is blijven staan, en roept applyPortionDelivery()
    //       aan om remainingU met precies dat bedrag te verminderen. Het
    //       restant blijft gewoon open en wordt de eerstvolgende cyclus
    //       opnieuw geprobeerd.
    //     • EROSIE (expliciet gevraagd: "het openstaande deel wel af laten
    //       bouwen vergelijkbaar met het percentage en misschien zelfs nog
    //       wel eerder te laten beginnen"): zonder rem zou een structureel
    //       geblokkeerde portie tot de allerlaatste cyclus van de override
    //       "geprobeerd" blijven worden en dan mogelijk alsnog in één keer
    //       doorkomen zodra de blokkade wegvalt — precies het late-lump-sum-
    //       risico dat de gebruiker wil vermijden. Daarom erodeert
    //       erodeOutstandingPortions() (aangeroepen aan het begin van elke
    //       nextDuePortionRequest()-aanroep, dus elke cyclus, OOK als er
    //       niks vervallen is) het |remainingU|-plafond van elke portie naar
    //       |amountU| * portionTaperScale(nowMs) — dezelfde kwadratische
    //       ease-out-vorm als status()'s percentage-taper, maar met een
    //       eigen (standaard eerder beginnende) PORTION_TAPER_START_FRACTION.
    //       Vóór dat omslagpunt is de scale 1.0 (geen erosie, dus een
    //       succesvolle aflevering wordt nooit onnodig afgeremd) — de erosie
    //       raakt dus alleen wat structureel is blijven hangen, nooit een
    //       normale, ongehinderde aflevering. BEWUST SYMMETRISCH (anders dan
    //       de afbouw hierboven, die expliciet alleen-positief is): een grote
    //       late negatieve inhaalslag is voor de gebruiker net zo onwenselijk
    //       als een grote late positieve, dus erosie geldt voor beide tekens.

    private const val KEY_PRESETS_JSON = "presets_json"
    private const val KEY_ACTIVE_PRESET_ID = "active_preset_id"
    private const val KEY_ACTIVE_PORTIONS_JSON = "active_portions_json"
    private const val KEY_ACTIVE_EXTRA_START_MS = "active_extra_start_ms"
    private const val KEY_ACTIVE_EXTRA_IS_POSITIVE = "active_extra_is_positive"
    private const val KEY_LOW_COMMIT_STREAK_START_MS = "low_commit_streak_start_ms"
    private const val KEY_PAUSED = "paused"
    private const val KEY_PAUSE_STARTED_MS = "pause_started_ms"

    const val PRESET_COUNT = 3
    const val MAX_PORTION_COUNT = 3
    /** Instelbereik extra insuline per portie: -5..+5 E, in stappen van 0,05 E (slider-stap). */
    const val MIN_EXTRA_INSULIN_U = -5.0
    const val MAX_EXTRA_INSULIN_U = 5.0
    const val EXTRA_INSULIN_STEP_U = 0.05
    /** Vertraging per portie: 0 minuten tot de ingestelde duur van de override, in stappen van 5 minuten. */
    const val PORTION_DELAY_STEP_MIN = 5
    /** commitFraction onder deze waarde telt als "algoritme ziet zelf geen aanleiding". */
    const val LOW_COMMIT_THRESHOLD = 0.20
    /** Zo lang moet commitFraction onafgebroken laag blijven vóór de resterende positieve porties vervallen. */
    const val LOW_COMMIT_TAPER_MINUTES = 20
    /** Op dit punt (fractie van de OVERRIDE-duur) begint het |remainingU|-plafond van een portie te eroderen — bewust eerder dan TAPER_START_FRACTION (75%), zie kdoc hierboven. */
    private const val PORTION_TAPER_START_FRACTION = 0.50
    /** Onder dit bedrag (E) telt een portie als volledig afgehandeld — voorkomt oneindig kleine restjes door afrondingsverschillen. */
    private const val PORTION_EPS_U = 0.005

    /**
     * Eén geplande extra-insuline-portie binnen een actief schema. amountU is
     * het oorspronkelijk ingestelde bedrag (vast, voor de erosie-referentie
     * en de status-UI); remainingU is wat er nog daadwerkelijk open staat —
     * begint op amountU, wordt na elke (deels) gelukte aflevering verlaagd
     * via applyPortionDelivery() en kan ook zonder aflevering al krimpen door
     * erodeOutstandingPortions() (zie kdoc bovenaan dit blok). id identificeert
     * de portie binnen het actieve schema (stabiel voor de levensduur van dat
     * schema, ook als andere porties intussen al klaar zijn).
     */
    data class OverridePortion(
        val id: Int,
        val delayMinutes: Int,
        val amountU: Double,
        val remainingU: Double = amountU
    )

    /** Eén concreet verzoek om nu, dit cyclus, requestU bij commandedDose op te tellen — dit IS de (eventueel al door erodeOutstandingPortions() verlaagde) remainingU van de betreffende portie op het moment van de aanvraag, zie nextDuePortionRequest(). */
    data class PortionRequest(val portionId: Int, val requestU: Double)

    /**
     * Eén door de gebruiker benoemd en ingesteld preset (vast slot 0/1/2).
     * portionAmountsU/portionDelaysMin staan altijd op volle lengte
     * (MAX_PORTION_COUNT) zodat de editor-UI voor elke slot-positie een
     * stabiele slider-waarde heeft — alleen de eerste [portionCount]
     * elementen worden bij start daadwerkelijk gebruikt/afgeleverd.
     */
    data class OverridePreset(
        val id: Int,
        val name: String,
        val percentage: Int = DEFAULT_PCT,
        val durationMinutes: Int = DEFAULT_DURATION_MIN,
        val portionCount: Int = 1,
        val portionAmountsU: List<Double> = List(MAX_PORTION_COUNT) { 0.0 },
        val portionDelaysMin: List<Int> = List(MAX_PORTION_COUNT) { 0 }
    ) {
        /** Som van de daadwerkelijk actieve (eerste [portionCount]) porties — voor UI-samenvatting en het teken (+/-). */
        val totalExtraInsulinU: Double get() = portionAmountsU.take(portionCount).sum()
    }

    private fun defaultPreset(id: Int): OverridePreset =
        OverridePreset(id = id, name = "Preset ${id + 1}")

    /** Niet-ingesteld = percentage/duur nog op standaard EN alle actieve porties nog op 0 E. Zo'n preset-knop is niet klikbaar. */
    fun isConfigured(preset: OverridePreset): Boolean =
        preset.percentage != DEFAULT_PCT ||
            preset.durationMinutes != DEFAULT_DURATION_MIN ||
            preset.portionAmountsU.take(preset.portionCount).any { it != 0.0 }

    private fun OverridePreset.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("percentage", percentage)
        put("durationMinutes", durationMinutes)
        put("portionCount", portionCount)
        put("portionAmountsU", JSONArray(portionAmountsU))
        put("portionDelaysMin", JSONArray(portionDelaysMin))
    }

    private fun presetFromJson(obj: JSONObject, fallbackId: Int): OverridePreset {
        val id = obj.optInt("id", fallbackId)
        fun intList(key: String): List<Int> {
            val arr = obj.optJSONArray(key) ?: return List(MAX_PORTION_COUNT) { 0 }
            val vals = (0 until arr.length()).map { arr.optInt(it, 0) }
            return if (vals.size >= MAX_PORTION_COUNT) vals.take(MAX_PORTION_COUNT)
            else vals + List(MAX_PORTION_COUNT - vals.size) { 0 }
        }
        fun doubleList(key: String): List<Double> {
            val arr = obj.optJSONArray(key) ?: return List(MAX_PORTION_COUNT) { 0.0 }
            val vals = (0 until arr.length()).map { arr.optDouble(it, 0.0) }
            return if (vals.size >= MAX_PORTION_COUNT) vals.take(MAX_PORTION_COUNT)
            else vals + List(MAX_PORTION_COUNT - vals.size) { 0.0 }
        }
        return OverridePreset(
            id = id,
            name = obj.optString("name", "Preset ${id + 1}"),
            percentage = obj.optInt("percentage", DEFAULT_PCT).coerceIn(MIN_PCT, MAX_PCT),
            durationMinutes = obj.optInt("durationMinutes", DEFAULT_DURATION_MIN).coerceIn(MIN_DURATION_MIN, MAX_DURATION_MIN),
            portionCount = obj.optInt("portionCount", 1).coerceIn(1, MAX_PORTION_COUNT),
            portionAmountsU = doubleList("portionAmountsU").map { it.coerceIn(MIN_EXTRA_INSULIN_U, MAX_EXTRA_INSULIN_U) },
            portionDelaysMin = intList("portionDelaysMin").map { it.coerceAtLeast(0) }
        )
    }

    /**
     * Alle 3 presets, in slot-volgorde (0,1,2). Een nog nooit opgeslagen slot
     * krijgt een neutrale default ("Preset N", 100%, alle porties 0 E) — zo
     * hoeft de UI nergens een null-geval te behandelen.
     */
    fun getPresets(context: Context): List<OverridePreset> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PRESETS_JSON, null) ?: return (0 until PRESET_COUNT).map { defaultPreset(it) }
        val stored = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).associate { i ->
                val obj = arr.getJSONObject(i)
                val id = obj.optInt("id", i)
                id to presetFromJson(obj, i)
            }
        } catch (e: Exception) {
            emptyMap()
        }
        return (0 until PRESET_COUNT).map { id -> stored[id] ?: defaultPreset(id) }
    }

    /** Slaat één preset op (overschrijft het slot met hetzelfde id), laat de andere 2 ongemoeid. */
    fun savePreset(context: Context, preset: OverridePreset) {
        val updated = getPresets(context).map { if (it.id == preset.id) preset else it }
        val arr = JSONArray()
        updated.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRESETS_JSON, arr.toString())
            .apply()
    }

    /**
     * Start een override VANUIT een preset: het bestaande percentage/duur-
     * mechanisme (start() hierboven, ongewijzigd) PLUS, voor elke van de
     * eerste [OverridePreset.portionCount] porties met een amountU != 0,
     * een geplande portie op zijn eigen, apart ingestelde vertraging.
     * Porties op exact 0 E worden niet ingepland (niets om af te leveren).
     */
    fun startWithPreset(context: Context, preset: OverridePreset, nowMs: Long) {
        start(context, preset.percentage, preset.durationMinutes)

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val portions = (0 until preset.portionCount)
            .map { i ->
                OverridePortion(
                    id = i,
                    delayMinutes = preset.portionDelaysMin.getOrElse(i) { 0 },
                    amountU = preset.portionAmountsU.getOrElse(i) { 0.0 }
                )
            }
            .filter { it.amountU != 0.0 }

        if (portions.isEmpty()) {
            prefs.edit()
                .putInt(KEY_ACTIVE_PRESET_ID, preset.id)
                .remove(KEY_ACTIVE_PORTIONS_JSON)
                .remove(KEY_ACTIVE_EXTRA_START_MS)
                .remove(KEY_ACTIVE_EXTRA_IS_POSITIVE)
                .remove(KEY_LOW_COMMIT_STREAK_START_MS)
                .putBoolean(KEY_PAUSED, false)
                .apply()
            return
        }

        val arr = JSONArray()
        portions.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("delayMinutes", p.delayMinutes)
                    put("amountU", p.amountU)
                    put("remainingU", p.remainingU)
                }
            )
        }
        prefs.edit()
            .putInt(KEY_ACTIVE_PRESET_ID, preset.id)
            .putString(KEY_ACTIVE_PORTIONS_JSON, arr.toString())
            .putLong(KEY_ACTIVE_EXTRA_START_MS, nowMs)
            .putBoolean(KEY_ACTIVE_EXTRA_IS_POSITIVE, portions.sumOf { it.amountU } > 0.0)
            .remove(KEY_LOW_COMMIT_STREAK_START_MS)
            .putBoolean(KEY_PAUSED, false)
            .apply()
    }

    /** Naam van het preset waarmee de huidige override (indien via een preset gestart) begon, of null. */
    fun activePresetName(context: Context): String? {
        if (!isActiveRaw(context)) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_ACTIVE_PRESET_ID)) return null
        val id = prefs.getInt(KEY_ACTIVE_PRESET_ID, -1)
        return getPresets(context).firstOrNull { it.id == id }?.name
    }

    private fun getActivePortions(context: Context): List<OverridePortion> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_PORTIONS_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val amountU = obj.optDouble("amountU", 0.0)
                OverridePortion(
                    id = obj.optInt("id", i),
                    delayMinutes = obj.optInt("delayMinutes", 0),
                    amountU = amountU,
                    // 24/09/2026: bestaand opgeslagen schema van vóór de retry+erosie-wijziging heeft
                    // geen remainingU-veld (wel nog het oude delivered-veld) — val dan terug op
                    // "helemaal open" (delivered=false) of "helemaal klaar" (delivered=true), zodat een
                    // schema dat al liep tijdens de update niet ineens dubbel afgeleverd wordt.
                    remainingU = if (obj.has("remainingU")) obj.optDouble("remainingU", amountU)
                        else if (obj.optBoolean("delivered", false)) 0.0 else amountU
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Publieke, alleen-lezen kijk op het actieve portie-schema — voor de status-UI (welke zijn al gegeven, welke nog niet). */
    fun activePortionsSnapshot(context: Context): List<OverridePortion> = getActivePortions(context)

    /** Starttijd (ms) van het actieve portie-schema, of null als er geen actief schema is. Voor het tonen van geplande kloktijden in de status-UI. */
    fun activeExtraStartMs(context: Context): Long? {
        val ms = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_ACTIVE_EXTRA_START_MS, 0L)
        return if (ms > 0L) ms else null
    }

    private fun saveActivePortions(context: Context, portions: List<OverridePortion>) {
        val arr = JSONArray()
        portions.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("delayMinutes", p.delayMinutes)
                    put("amountU", p.amountU)
                    put("remainingU", p.remainingU)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_PORTIONS_JSON, arr.toString())
            .apply()
    }

    /**
     * Schaal (0.0..1.0) voor het |remainingU|-plafond van een portie op dit
     * moment — dezelfde kwadratische ease-out-vorm als status()'s
     * percentage-taper, maar met een eigen, bewust eerder beginnend
     * omslagpunt (PORTION_TAPER_START_FRACTION). 1.0 vóór dat omslagpunt
     * (geen erosie), vloeiend naar 0.0 aan het einde van de override-duur.
     * Rekent, net als status(), met de bevroren tijd tijdens een pauze.
     */
    private fun portionTaperScale(context: Context, nowMs: Long): Double {
        val paused = isPaused(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val effectiveNowMs = if (paused) prefs.getLong(KEY_PAUSE_STARTED_MS, nowMs) else nowMs
        val startMs = getStartTimeMs(context)
        val durationMs = getDurationMinutes(context) * 60_000L
        val elapsedMs = (effectiveNowMs - startMs).coerceIn(0L, durationMs)

        val flatEndMs = (durationMs * PORTION_TAPER_START_FRACTION).toLong()
        if (elapsedMs <= flatEndMs) return 1.0
        val taperDurMs = durationMs - flatEndMs
        val taperFrac = if (taperDurMs <= 0L) 1.0
            else ((elapsedMs - flatEndMs).toDouble() / taperDurMs).coerceIn(0.0, 1.0)
        return (1.0 - taperFrac) * (1.0 - taperFrac)
    }

    /**
     * Eroderen: verlaagt, voor ELKE portie in het actieve schema (niet
     * alleen een vervallen/vervallende), |remainingU| zo nodig naar
     * |amountU| * portionTaperScale(nowMs). Wordt bij elke aanroep van
     * nextDuePortionRequest() uitgevoerd (dus elke cyclus, ook als er niks
     * vervalt) zodat een structureel geblokkeerde portie richting het einde
     * van de override vanzelf uitdooft in plaats van in één keer alsnog
     * door te komen. Vóór PORTION_TAPER_START_FRACTION is de scale 1.0, dus
     * dan gebeurt hier niets.
     */
    private fun erodeOutstandingPortions(context: Context, nowMs: Long) {
        val portions = getActivePortions(context)
        if (portions.none { it.remainingU != 0.0 }) return
        val scale = portionTaperScale(context, nowMs)
        if (scale >= 1.0) return
        val eroded = portions.map { p ->
            val cap = kotlin.math.abs(p.amountU) * scale
            if (kotlin.math.abs(p.remainingU) <= cap) return@map p
            val newRemaining = if (p.remainingU >= 0.0) cap else -cap
            p.copy(remainingU = if (kotlin.math.abs(newRemaining) < PORTION_EPS_U) 0.0 else newRemaining)
        }
        if (eroded != portions) saveActivePortions(context, eroded)
    }

    /**
     * De volgende portie met nog openstaand remainingU waarvan de vertraging
     * al is verstreken (of null). Levert NIKS af zolang gepauzeerd
     * (isPaused). Erodeert eerst (zie erodeOutstandingPortions()) — dus ook
     * een cyclus zonder vervallen portie kan het |remainingU|-plafond al
     * verlagen. FCLvNext.kt roept dit elke cyclus aan; bij een hit wordt
     * requestU ADDITIEF bij commandedDose opgeteld (zie kdoc bovenaan dit
     * blok) en na alle latere veiligheidsstappen (down-trend-gate, cap,
     * herstart-blokkade) via applyPortionDelivery() afgerekend op wat er
     * DIT cyclus daadwerkelijk is blijven staan — dus met automatische
     * retry zolang remainingU nog niet op 0 staat.
     */
    fun nextDuePortionRequest(context: Context, nowMs: Long): PortionRequest? {
        if (!isActiveRaw(context)) return null
        if (isPaused(context)) return null
        val startMs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_ACTIVE_EXTRA_START_MS, 0L)
        if (startMs <= 0L) return null
        erodeOutstandingPortions(context, nowMs)
        val due = getActivePortions(context)
            .filter { it.remainingU != 0.0 }
            .filter { nowMs - startMs >= it.delayMinutes * 60_000L }
            .minByOrNull { it.delayMinutes } ?: return null
        return PortionRequest(portionId = due.id, requestU = due.remainingU)
    }

    /**
     * Rekent de portie met dit id af op wat er dit cyclus daadwerkelijk is
     * blijven staan (deliveredU — door de aanroeper in FCLvNext.kt bepaald
     * via een diff van commandedDose vlak vóór Execution, zie kdoc bovenaan
     * dit blok). Verlaagt |remainingU| met |deliveredU|, richting 0 maar
     * nooit voorbij (en nooit van teken wisselend). Een eventueel restant
     * (aangevraagd - geleverd) blijft gewoon staan voor de volgende cyclus.
     */
    fun applyPortionDelivery(context: Context, portionId: Int, deliveredU: Double) {
        val updated = getActivePortions(context).map { p ->
            if (p.id != portionId) return@map p
            val newRemaining = if (p.remainingU >= 0.0)
                (p.remainingU - deliveredU).coerceAtLeast(0.0)
            else
                (p.remainingU - deliveredU).coerceAtMost(0.0)
            p.copy(remainingU = if (kotlin.math.abs(newRemaining) < PORTION_EPS_U) 0.0 else newRemaining)
        }
        saveActivePortions(context, updated)
    }

    /** True als er nog minstens 1 portie met openstaand remainingU in het actieve schema staat. */
    fun hasPendingPortions(context: Context): Boolean =
        getActivePortions(context).any { it.remainingU != 0.0 }

    /** True als de SOM van het actieve schema (indien aanwezig) POSITIEF is — alleen dan geldt de afbouw hieronder. */
    fun isActiveExtraPositive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVE_EXTRA_IS_POSITIVE, false)

    /**
     * Alleen relevant/aan te roepen bij een POSITIEF actief schema (zie
     * isActiveExtraPositive) — NOOIT bij een percentage<100% of een
     * negatief/nul-schema, zie de kdoc bovenaan dit blok. Houdt bij hoe
     * lang commitFraction al onafgebroken onder LOW_COMMIT_THRESHOLD zit;
     * geeft true zodra dat LOW_COMMIT_TAPER_MINUTES aaneengesloten heeft
     * geduurd — de aanroeper laat dan de resterende porties vervallen via
     * cancelRemainingPortions().
     */
    fun recordCommitAndShouldTaper(context: Context, commitFraction: Double, nowMs: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (commitFraction >= LOW_COMMIT_THRESHOLD) {
            if (prefs.contains(KEY_LOW_COMMIT_STREAK_START_MS)) {
                prefs.edit().remove(KEY_LOW_COMMIT_STREAK_START_MS).apply()
            }
            return false
        }
        val streakStart = prefs.getLong(KEY_LOW_COMMIT_STREAK_START_MS, 0L)
        if (streakStart <= 0L) {
            prefs.edit().putLong(KEY_LOW_COMMIT_STREAK_START_MS, nowMs).apply()
            return false
        }
        return (nowMs - streakStart) >= LOW_COMMIT_TAPER_MINUTES * 60_000L
    }

    /** Laat alle nog openstaande porties vervallen (afbouw-trigger, alleen voor positieve schema's — zie kdoc bovenaan dit blok). */
    fun cancelRemainingPortions(context: Context) {
        val remaining = getActivePortions(context).map { if (it.remainingU != 0.0) it.copy(remainingU = 0.0) else it }
        saveActivePortions(context, remaining)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LOW_COMMIT_STREAK_START_MS)
            .apply()
    }

    // ── Pauzeren/hervatten (24/09/2026, de gebruiker) ──────────────────────
    // Zie kdoc bovenaan dit blok voor de precieze semantiek: pauzeren
    // bevriest de klok (percentage-multiplier ÉN resterende tijd blijven
    // hangen op hun waarde op het moment van pauzeren), hervatten schuift
    // de opgeslagen starttijden op met de gepauzeerde duur zodat alles
    // daarna weer gewoon doorloopt vanaf waar het was.

    fun isPaused(context: Context): Boolean =
        isActiveRaw(context) && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAUSED, false)

    fun pause(context: Context, nowMs: Long) {
        if (!isActiveRaw(context) || isPaused(context)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PAUSED, true)
            .putLong(KEY_PAUSE_STARTED_MS, nowMs)
            .apply()
    }

    fun resume(context: Context, nowMs: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PAUSED, false)) return
        val pauseStartedMs = prefs.getLong(KEY_PAUSE_STARTED_MS, nowMs)
        val pausedDurationMs = (nowMs - pauseStartedMs).coerceAtLeast(0L)

        val editor = prefs.edit().putBoolean(KEY_PAUSED, false).remove(KEY_PAUSE_STARTED_MS)
        val startMs = prefs.getLong(KEY_START_MS, 0L)
        if (startMs > 0L) editor.putLong(KEY_START_MS, startMs + pausedDurationMs)
        val extraStartMs = prefs.getLong(KEY_ACTIVE_EXTRA_START_MS, 0L)
        if (extraStartMs > 0L) editor.putLong(KEY_ACTIVE_EXTRA_START_MS, extraStartMs + pausedDurationMs)
        editor.apply()
    }

    // ── Treatments-sheet snelkoppeling (13/09/2026, de gebruiker) ──────────
    // Zelfde one-shot-vlagpatroon als FclUpdateNotificationHelper.requestNavigate()/
    // consumeNavigateRequest(): FclTempOverrideStatusProviderImpl.requestOpenSettings()
    // zet 'm (aangeroepen via ElementNavigator, vóór het openen van de plugin), en
    // FCLComposeContent.kt leest 'm één keer bij het opbouwen van het scherm om
    // meteen op het Settings-tabblad te starten mét opengeklapte sectie hierboven.
    private val openSettingsRequested = AtomicBoolean(false)

    fun requestOpenSettings() {
        openSettingsRequested.set(true)
    }

    /** Retourneert true (en reset meteen naar false) als er een open-verzoek klaarstond. */
    fun consumeOpenSettingsRequest(): Boolean = openSettingsRequested.getAndSet(false)
}
