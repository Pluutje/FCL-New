package app.aaps.plugins.aps.openAPSFCL.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLCycleLogRepository
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 14/09/2026 (de gebruiker) — laadt apparaat-leeftijd data (sensor, canule, reservoir) EN de
 * live IOB/basaal-waarden voor het nieuwe, optionele overzichtsscherm (zie FclOverviewScreen.kt).
 * Dit is puur weergave-data, het verandert geen enkele doseerbeslissing.
 *
 * BELANGRIJK (bugfix 14/09/2026, de gebruiker meldde een verkeerde IOB-waarde): de IOB/basaal-
 * getallen worden NIET van de grafiekreeks (`OverviewDataCache.iobGraphFlow`) afgelezen — dat is
 * de laatste GEPLOTTE datapunt, met een eigen (vertraagde/afgeronde) update-cadans, niet de live
 * waarde. In plaats daarvan wordt exact dezelfde berekening gebruikt als het standaard
 * IOB-chipje (zie `ChipsViewModel.iobUiState` in de ui-module): `calculateIobFromBolus().iob +
 * calculateIobFromTempBasalsIncludingConvertedExtended().basaliob`. Voor de basaalsnelheid wordt
 * `IobCobCalculator.getBasalData(...).tempBasalAbsolute` gebruikt, dezelfde bron als het
 * standaard TBR-chipje.
 *
 * Losse, kleine ViewModel (geen Metro `@ContributesIntoMap`/`@ViewModelKey`, gewoon via
 * `viewModelFactory { initializer { ... } }` gebouwd) — zelfde manier waarop `HistoryScreen`
 * zijn `GraphViewModel` bouwt, niet via `metroViewModel()`.
 */
class FclOverviewViewModel(
    private val persistenceLayer: PersistenceLayer,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val iobCobCalculator: IobCobCalculator,
    private val dateUtil: DateUtil,
    private val cycleLogRepository: FCLCycleLogRepository,
    // 22/09/2026 (de gebruiker) — alleen nodig voor loadDoseHistory() hieronder: FCLvNext's eigen
    // cyclus-log slaat de Bg altijd in mmol/L op (zie BGDataPoint.kt-kdoc), terwijl de AAPS-
    // bg-reeks (IobCobCalculator.ads) in mg/dl is — profileUtil rekent beide naar dezelfde
    // eenheid om (mg/dl) zodat elke rij in het lijstje met dezelfde formatter getoond kan worden.
    private val profileUtil: ProfileUtil
) : ViewModel() {

    companion object {
        /** Zie kdoc bij `refresh()` — hoelang FCLvNext's cyclus-log en de bijbehorende
         *  AAPS-SMB-rij nog als DEZELFDE afgifte gelden. */
        private const val FCL_AAPS_SAME_EVENT_TOLERANCE_MS = 60_000L

        /** Zie kdoc bij `refresh()` — hoelang een handmatige bolus nog als "toevallig
         *  samenvallend" (dus optellen) geldt i.p.v. als eigen, latere gebeurtenis. */
        private const val MANUAL_COINCIDENCE_TOLERANCE_MS = 15_000L

        /** Zie kdoc bij `loadDoseHistory()`. */
        private const val DOSE_HISTORY_LIMIT = 20
        private const val DOSE_HISTORY_FETCH_MARGIN = 40
        private const val DOSE_HISTORY_LOOKBACK_MS = 3L * 24 * 60 * 60 * 1000L
        private const val BG_LOOKUP_MAX_GAP_MS = 10 * 60_000L
    }

    private val _deviceState = MutableStateFlow(FclDeviceState())
    val deviceState: StateFlow<FclDeviceState> = _deviceState.asStateFlow()

    private val _doseHistory = MutableStateFlow<List<DoseHistoryEntry>>(emptyList())
    val doseHistory: StateFlow<List<DoseHistoryEntry>> = _doseHistory.asStateFlow()

    private val _doseHistoryLoading = MutableStateFlow(false)
    val doseHistoryLoading: StateFlow<Boolean> = _doseHistoryLoading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val sensorChange = withContext(Dispatchers.IO) {
                persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)
            }
            val cannulaChange = withContext(Dispatchers.IO) {
                persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
            }
            // 21/09/2026 (de gebruiker, 2e ronde) — EERST persistenceLayer.getNewestBolus()
            // gebruikt, maar de gebruiker wees erop dat dat de kleine, losse BS-bolus is die
            // FCLvNext zelf registreert nadat het een commit al heeft opgesplitst via
            // hybridPercentage (zie executeDelivery() in FCLvNext.kt) — grotendeels basaal-over-
            // cyclus, een klein deel bolus. Die BS-waarde alleen geeft dus een veel te kleine
            // indruk van wat er echt gestuurd is. In plaats daarvan nu de laatste cyclus met een
            // echte afgifte uit FCLvNext's EIGEN per-cyclus-log (deliveredTotal = basaal-over-
            // cyclus + SMB SAMEN, vóór de basaal/bolus-opsplitsing) — zie
            // FCLCycleLogDao.getLastDelivery().
            //
            // 21/09/2026 (de gebruiker, 3e ronde) — dat alleen was nog niet genoeg: de gebruiker
            // gaf zelf een HANDMATIGE correctie (2E om 15:38) die later was dan de laatste
            // FCLvNext-cyclus (0,69E om 14:19), maar bleef onzichtbaar. Nu drie mogelijke bronnen
            // vergelijken op tijdstip en de ECHT laatste tonen, met een compacte bron-tag:
            //  - FCL:  laatste FCLCycleLogEntity-rij met deliveredTotal>0 (het algoritme's eigen
            //          besluit, basaal+SMB samen, vóór opsplitsing).
            //  - AAPS: laatste BS-record met type=SMB (de daadwerkelijk UITGEVOERDE automatische
            //          microbolus — kan door de wachtrij soms een cyclus later landen dan het
            //          FCLvNext-logboek, vandaar apart vergeleken i.p.v. aangenomen gelijk).
            //  - Man:  laatste BS-record met type=NORMAL (een echte, door de gebruiker zelf
            //          ingevoerde bolus — niets met FCLvNext's besluitvorming te maken).
            val lastDelivery = withContext(Dispatchers.IO) {
                cycleLogRepository.getLastDelivery()
            }
            val lastAapsSmb = withContext(Dispatchers.IO) {
                persistenceLayer.getNewestBolusOfType(BS.Type.SMB)
            }
            val lastManualBolus = withContext(Dispatchers.IO) {
                persistenceLayer.getNewestBolusOfType(BS.Type.NORMAL)
            }

            // 22/09/2026 (de gebruiker, 4e ronde) — simpelweg "neem de hoogste timestamp van de
            // drie" (vorige versie) kon de verkeerde bron kiezen: FCLvNext's eigen cyclus-log
            // (deliveredTotal, bijv. 2,54E) en de kleine AAPS-SMB-rij die uit diezelfde opsplitsing
            // ontstaat (bijv. 0,10E, zie executeDelivery()/hybridPercentage in FCLvNext.kt) horen
            // vrijwel gelijktijdig in de database te landen — maar niet noodzakelijk op exact
            // dezelfde milliseconde, dus een kale max-op-tijdstip koos soms de AAPS-rij (het kleine
            // deel) in plaats van FCLvNext's TOTALE dosis. Nu expliciet: die twee horen bij DEZELFDE
            // afgifte als ze binnen FCL_AAPS_SAME_EVENT_TOLERANCE_MS van elkaar liggen, en dan
            // leidt altijd FCLvNext's totaal (niet het AAPS-deelbedrag). Alleen als de AAPS-SMB-rij
            // er duidelijk LATER is dan die tolerantie (bijv. een cyclus later, zoals de gebruiker
            // eerder beschreef) wordt die als eigen, losse AAPS-gebeurtenis behandeld.
            val algoEvent: Triple<Long, Double, LastDoseSource>? = when {
                lastDelivery != null && lastAapsSmb != null -> {
                    val fclTs = lastDelivery.timestampMs
                    val aapsTs = lastAapsSmb.timestamp
                    if (aapsTs - fclTs > FCL_AAPS_SAME_EVENT_TOLERANCE_MS) {
                        Triple(aapsTs, lastAapsSmb.amount, LastDoseSource.AAPS)
                    } else {
                        Triple(fclTs, lastDelivery.delivery.deliveredTotal, LastDoseSource.FCLVNEXT)
                    }
                }

                lastDelivery != null -> Triple(lastDelivery.timestampMs, lastDelivery.delivery.deliveredTotal, LastDoseSource.FCLVNEXT)
                lastAapsSmb != null  -> Triple(lastAapsSmb.timestamp, lastAapsSmb.amount, LastDoseSource.AAPS)
                else                 -> null
            }

            // Een handmatige bolus die toevallig binnen MANUAL_COINCIDENCE_TOLERANCE_MS van de
            // fcl/aaps-gebeurtenis valt, is een AANVULLING (optellen: de gebruiker gaf zelf iets
            // extra's op hetzelfde moment) — geen vervanging. Ligt de handmatige bolus duidelijk
            // LATER (een eigen, losstaande correctie), dan toont die alleen. Ligt hij duidelijk
            // vroeger, dan blijft de fcl/aaps-gebeurtenis leidend ("anders heeft fcl de lead").
            val lastDose: Triple<Long, Double, LastDoseSource>? = when {
                lastManualBolus != null && algoEvent != null -> {
                    val manualTs = lastManualBolus.timestamp
                    val diffMs = manualTs - algoEvent.first
                    when {
                        abs(diffMs) <= MANUAL_COINCIDENCE_TOLERANCE_MS ->
                            Triple(maxOf(manualTs, algoEvent.first), lastManualBolus.amount + algoEvent.second, LastDoseSource.COMBINED)

                        manualTs > algoEvent.first -> Triple(manualTs, lastManualBolus.amount, LastDoseSource.MANUAL)
                        else                        -> algoEvent
                    }
                }

                lastManualBolus != null -> Triple(lastManualBolus.timestamp, lastManualBolus.amount, LastDoseSource.MANUAL)
                else                     -> algoEvent
            }
            val now = dateUtil.now()
            val profile = profileFunction.getProfile()
            val reservoirU = profile?.let {
                activePlugin.activePump.reservoirLevel.value.iU(it.insulinConcentration())
            } ?: 0.0

            // Zelfde formule als ChipsViewModel.iobUiState (standaard IOB-chipje).
            val bolusIob = iobCobCalculator.calculateIobFromBolus()
            val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended()
            val iobTotal = bolusIob.iob + basalIob.basaliob

            // Zelfde bron als het standaard TBR-chipje (OverviewDataCacheImpl.updateTbrFromDatabase).
            // 15/09/2026 (de gebruiker) — ook de PROFIEL-basaalsnelheid (BasalData.basal, de
            // ongewijzigde geplande snelheid) opgehaald, zodat de UI kan tonen of de actuele
            // basaal verhoogd/verlaagd/vlak is t.o.v. het profiel (zie basalTrendSymbol in
            // FclOverviewScreen.kt).
            val basalData = profile?.let { iobCobCalculator.getBasalData(it, now) }
            val basalRateUh = basalData?.tempBasalAbsolute ?: 0.0
            val profileBasalRateUh = basalData?.basal ?: 0.0

            _deviceState.value = FclDeviceState(
                sensorAgeMinutes = sensorChange?.let { ((now - it.timestamp) / 60000L).toInt() },
                cannulaAgeMinutes = cannulaChange?.let { ((now - it.timestamp) / 60000L).toInt() },
                reservoirUnits = reservoirU,
                iobTotal = iobTotal,
                basalRateUh = basalRateUh,
                profileBasalRateUh = profileBasalRateUh,
                lastDoseUnits = lastDose?.second,
                lastDoseTimestamp = lastDose?.first,
                lastDoseSource = lastDose?.third
            )
        }
    }

    /**
     * 22/09/2026 (de gebruiker) — "laatste doseringen"-lijstje: klik op de laatste-dosis-tekst
     * op FclOverviewScreen.kt opent een popup met de laatste [DOSE_HISTORY_LIMIT] doses, elk met
     * bron-tag en de op dat moment geldende Bg. Bewust NIET meegenomen in refresh() (dat draait
     * elke 30s, zie LaunchedEffect(now) in FclOverviewScreen.kt) — dit is duidelijk zwaarder werk
     * (meerdere queries + een BG-zoekactie per rij) en wordt daarom alleen op aanvraag geladen,
     * als de popup daadwerkelijk geopend wordt.
     *
     * Zelfde samenvoeg-regels als de "laatste dosis"-tekst (zie refresh() hierboven), maar dan
     * over de hele lijst i.p.v. alleen het allerlaatste moment:
     *  - Een FCLCycleLogEntity-rij (deliveredTotal>0) en de AAPS-SMB-rij die uit diezelfde
     *    opsplitsing ontstaat (binnen FCL_AAPS_SAME_EVENT_TOLERANCE_MS) tellen als ÉÉN regel —
     *    FCLvNext's totaal, niet het AAPS-deelbedrag. Een AAPS-SMB-rij die daar NIET binnen valt
     *    is een eigen, losse gebeurtenis (bijv. een cyclus later gequeued, zie kdoc refresh()).
     *  - Een handmatige bolus die toevallig binnen MANUAL_COINCIDENCE_TOLERANCE_MS van zo'n
     *    gebeurtenis valt, wordt ERBIJ OPGETELD (LastDoseSource.COMBINED) i.p.v. als aparte regel
     *    getoond. Dit gebeurt alleen tussen chronologisch AANGRENZENDE items — een drievoudig
     *    samenvallen (FCL + AAPS + handmatig, alle drie binnen enkele seconden) is een zo zeldzame
     *    situatie dat die bewust niet apart behandeld wordt; in dat geval blijven er gewoon twee
     *    regels staan i.p.v. één.
     *
     * Bg-eenheid: FCLCycleLogEntity.glucoseIob.bg staat al in mmol/L (zie BGDataPoint.kt-kdoc),
     * de AAPS-Bg-reeks (IobCobCalculator.ads) in mg/dl — beide worden hier naar mg/dl herleid
     * ([DoseHistoryEntry.bgMgdl]) zodat de UI-laag ze allebei met dezelfde
     * `ProfileUtil.fromMgdlToStringWithUnits()`-aanroep kan tonen, ongeacht de eenheid-instelling
     * van de gebruiker.
     */
    fun loadDoseHistory() {
        viewModelScope.launch {
            _doseHistoryLoading.value = true
            try {
                val sinceMs = dateUtil.now() - DOSE_HISTORY_LOOKBACK_MS
                val fclRows = withContext(Dispatchers.IO) {
                    cycleLogRepository.getRecentDeliveries(DOSE_HISTORY_FETCH_MARGIN)
                }
                val boluses = withContext(Dispatchers.IO) {
                    persistenceLayer.getBolusesFromTime(sinceMs, false)
                }
                val smbBoluses = boluses.filter { it.type == BS.Type.SMB }
                val manualBoluses = boluses.filter { it.type == BS.Type.NORMAL }

                // AAPS-SMB-rijen die al bij een FCL-afgifte horen (zie kdoc hierboven) niet
                // nogmaals als eigen regel meenemen.
                val independentSmb = smbBoluses.filterNot { smb ->
                    fclRows.any { abs(it.timestampMs - smb.timestamp) <= FCL_AAPS_SAME_EVENT_TOLERANCE_MS }
                }

                val bgReadings = iobCobCalculator.ads.dataLock.withLock {
                    iobCobCalculator.ads.getBgReadingsDataTableCopy()
                }

                data class Atomic(val ts: Long, val units: Double, val source: LastDoseSource, val bgMgdl: Double?)

                val fclAtoms = fclRows.map { row ->
                    Atomic(
                        ts = row.timestampMs,
                        units = row.delivery.deliveredTotal,
                        source = LastDoseSource.FCLVNEXT,
                        bgMgdl = row.glucoseIob.bg.takeIf { it > 0.0 }
                            ?.let { profileUtil.fromMmolToUnits(it, GlucoseUnit.MGDL) }
                    )
                }
                val aapsAtoms = independentSmb.map { bs ->
                    Atomic(bs.timestamp, bs.amount, LastDoseSource.AAPS, nearestBgMgdl(bgReadings, bs.timestamp))
                }
                val manualAtoms = manualBoluses.map { bs ->
                    Atomic(bs.timestamp, bs.amount, LastDoseSource.MANUAL, nearestBgMgdl(bgReadings, bs.timestamp))
                }

                val sorted = (fclAtoms + aapsAtoms + manualAtoms).sortedByDescending { it.ts }
                val merged = mutableListOf<Atomic>()
                var i = 0
                while (i < sorted.size) {
                    val current = sorted[i]
                    val next = sorted.getOrNull(i + 1)
                    val isManualPair = next != null && (current.source == LastDoseSource.MANUAL) != (next.source == LastDoseSource.MANUAL)
                    if (next != null && isManualPair && abs(current.ts - next.ts) <= MANUAL_COINCIDENCE_TOLERANCE_MS) {
                        merged += Atomic(
                            ts = maxOf(current.ts, next.ts),
                            units = current.units + next.units,
                            source = LastDoseSource.COMBINED,
                            bgMgdl = current.bgMgdl ?: next.bgMgdl
                        )
                        i += 2
                    } else {
                        merged += current
                        i += 1
                    }
                }

                _doseHistory.value = merged.take(DOSE_HISTORY_LIMIT)
                    .map { DoseHistoryEntry(it.ts, it.units, it.source, it.bgMgdl) }
            } finally {
                _doseHistoryLoading.value = false
            }
        }
    }

    /** Dichtstbijzijnde Bg-meting (mg/dl) binnen [BG_LOOKUP_MAX_GAP_MS] van [targetTs], of null
     *  als de dichtstbijzijnde meting verder weg ligt dan dat (bijv. sensor was toen niet actief). */
    private fun nearestBgMgdl(bgReadings: List<GV>, targetTs: Long): Double? {
        val nearest = bgReadings.minByOrNull { abs(it.timestamp - targetTs) } ?: return null
        return nearest.value.takeIf { abs(nearest.timestamp - targetTs) <= BG_LOOKUP_MAX_GAP_MS }
    }
}

/**
 * @param sensorAgeMinutes null als er nog nooit een sensorwissel is geregistreerd
 * @param cannulaAgeMinutes null als er nog nooit een canulewissel is geregistreerd
 */
data class FclDeviceState(
    val sensorAgeMinutes: Int? = null,
    val cannulaAgeMinutes: Int? = null,
    val reservoirUnits: Double = 0.0,
    val iobTotal: Double = 0.0,
    val basalRateUh: Double = 0.0,
    /** Profile's scheduled base rate (no temp basal) — used to show verlaagd/verhoogd/vlak. */
    val profileBasalRateUh: Double = 0.0,
    /**
     * 22/09/2026 (de gebruiker) — laatste dosis, ongeacht bron: FCLvNext's eigen cyclus-log
     * (TOTALE dosis, basaal-over-cyclus + SMB samen) en de bijbehorende AAPS-SMB-rij tellen als
     * ÉÉN gebeurtenis (FCLvNext's totaal leidt); een handmatige bolus (BS type=NORMAL) die daar
     * toevallig binnen enkele seconden mee samenvalt wordt OPGETELD; anders wint de laatste,
     * losstaande gebeurtenis op tijdstip. Zie [LastDoseSource] en de kdoc bij
     * `FclOverviewViewModel.refresh()`. Null als er nog nooit een dosis is geregistreerd.
     */
    val lastDoseUnits: Double? = null,
    val lastDoseTimestamp: Long? = null,
    val lastDoseSource: LastDoseSource? = null
)

/** Compacte bron-tag voor de "laatste dosis"-tekst op FclOverviewScreen.kt.
 *  COMBINED (22/09/2026) = een handmatige bolus die toevallig binnen enkele seconden samenviel
 *  met een fcl/aaps-dosis — [FclDeviceState.lastDoseUnits] is dan de SOM van beide. */
enum class LastDoseSource { FCLVNEXT, AAPS, MANUAL, COMBINED }

/**
 * 22/09/2026 (de gebruiker) — één regel in het "laatste doseringen"-lijstje (popup bij een klik
 * op de laatste-dosis-tekst). Zie kdoc bij `FclOverviewViewModel.loadDoseHistory()`.
 *
 * @param bgMgdl de op dat moment geldende Bg in mg/dl (altijd mg/dl, ongeacht bron — zie kdoc bij
 *   `loadDoseHistory()` voor waarom), of null als er geen meting dicht genoeg in de tijd was.
 */
data class DoseHistoryEntry(
    val timestampMs: Long,
    val units: Double,
    val source: LastDoseSource,
    val bgMgdl: Double?
)
