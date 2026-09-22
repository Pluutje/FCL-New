package app.aaps.plugins.aps.openAPSFCL.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
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
    private val cycleLogRepository: FCLCycleLogRepository
) : ViewModel() {

    companion object {
        /** Zie kdoc bij `refresh()` — hoelang FCLvNext's cyclus-log en de bijbehorende
         *  AAPS-SMB-rij nog als DEZELFDE afgifte gelden. */
        private const val FCL_AAPS_SAME_EVENT_TOLERANCE_MS = 60_000L

        /** Zie kdoc bij `refresh()` — hoelang een handmatige bolus nog als "toevallig
         *  samenvallend" (dus optellen) geldt i.p.v. als eigen, latere gebeurtenis. */
        private const val MANUAL_COINCIDENCE_TOLERANCE_MS = 15_000L
    }

    private val _deviceState = MutableStateFlow(FclDeviceState())
    val deviceState: StateFlow<FclDeviceState> = _deviceState.asStateFlow()

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
