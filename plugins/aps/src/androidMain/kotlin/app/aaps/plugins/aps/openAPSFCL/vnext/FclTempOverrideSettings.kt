package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
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

        val startMs = getStartTimeMs(context)
        val durationMs = getDurationMinutes(context) * 60_000L
        val elapsedMs = nowMs - startMs

        if (elapsedMs >= durationMs || elapsedMs < 0L) {
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
