package app.aaps.ui.compose.overview.alternative

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Data for [AlternativeOverviewScreen]: age of sensor and cannula, reservoir, live IOB and basal, the
 * last dose and the list of the last doses.
 *
 * This is a full copy of the idea behind `FclOverviewViewModel` (plugins:aps), on purpose: the screen
 * must not depend on FCLvNext or on any one APS plugin. The only difference is the source of the doses:
 * here they come from the AAPS boluses (SMB and manual) and not from the FCLvNext cycle log.
 *
 * Only shows data. It does not change any dosing decision.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Inject
class AlternativeOverviewViewModel(
    private val persistenceLayer: PersistenceLayer,
    private val activePlugin: ActivePlugin,
    private val profileFunction: ProfileFunction,
    private val iobCobCalculator: IobCobCalculator,
    val dateUtil: DateUtil,
    val profileUtil: ProfileUtil,
    val overviewDataCache: OverviewDataCache,
    val graphViewModelFactory: GraphViewModel.Factory,
    /** Keeps the height of the two graphs (IntNonKey.OverviewAlternativeBgHeight and ...IobHeight). */
    val preferences: Preferences
) : ViewModel() {

    private companion object {
        /** A manual bolus within this time of an SMB counts as one dose (they are added). */
        const val MANUAL_COINCIDENCE_TOLERANCE_MS = 15_000L
        const val DOSE_HISTORY_LIMIT = 20
        const val DOSE_HISTORY_LOOKBACK_MS = 3L * 24 * 60 * 60 * 1000L
        const val BG_LOOKUP_MAX_GAP_MS = 10 * 60_000L
    }

    private val _deviceState = MutableStateFlow(AlternativeDeviceState())
    val deviceState: StateFlow<AlternativeDeviceState> = _deviceState.asStateFlow()

    private val _doseHistory = MutableStateFlow<List<AlternativeDoseEntry>>(emptyList())
    val doseHistory: StateFlow<List<AlternativeDoseEntry>> = _doseHistory.asStateFlow()

    private val _doseHistoryLoading = MutableStateFlow(false)
    val doseHistoryLoading: StateFlow<Boolean> = _doseHistoryLoading.asStateFlow()

    init {
        refresh()
    }

    /** Reads everything again. The screen calls this about every 30 seconds. */
    fun refresh() {
        viewModelScope.launch {
            val sensorChange = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)
            val cannulaChange = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)
            val lastSmb = persistenceLayer.getNewestBolusOfType(BS.Type.SMB)
            val lastManual = persistenceLayer.getNewestBolusOfType(BS.Type.NORMAL)

            // The newest of the two. A manual bolus that falls together with an SMB (same moment)
            // is added to it, so the shown dose is what was really given then.
            val lastDose: Triple<Long, Double, AlternativeDoseSource>? = when {
                lastSmb != null && lastManual != null -> {
                    val diffMs = lastManual.timestamp - lastSmb.timestamp
                    when {
                        abs(diffMs) <= MANUAL_COINCIDENCE_TOLERANCE_MS ->
                            Triple(maxOf(lastManual.timestamp, lastSmb.timestamp), lastManual.amount + lastSmb.amount, AlternativeDoseSource.COMBINED)

                        diffMs > 0                                      -> Triple(lastManual.timestamp, lastManual.amount, AlternativeDoseSource.MANUAL)
                        else                                            -> Triple(lastSmb.timestamp, lastSmb.amount, AlternativeDoseSource.SMB)
                    }
                }

                lastSmb != null                       -> Triple(lastSmb.timestamp, lastSmb.amount, AlternativeDoseSource.SMB)
                lastManual != null                    -> Triple(lastManual.timestamp, lastManual.amount, AlternativeDoseSource.MANUAL)
                else                                  -> null
            }

            val now = dateUtil.now()
            val profile = profileFunction.getProfile()
            val reservoirU = profile?.let {
                activePlugin.activePump.reservoirLevel.value?.iU(it.insulinConcentration())
            } ?: 0.0

            // Same formula as the standard IOB chip.
            val bolusIob = iobCobCalculator.calculateIobFromBolus()
            val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended()

            // Same source as the standard TBR chip. The profile rate shows if the basal is raised or lowered.
            val basalData = profile?.let { iobCobCalculator.getBasalData(it, now) }

            _deviceState.value = AlternativeDeviceState(
                sensorAgeMinutes = sensorChange?.let { ((now - it.timestamp) / 60000L).toInt() },
                cannulaAgeMinutes = cannulaChange?.let { ((now - it.timestamp) / 60000L).toInt() },
                reservoirUnits = reservoirU,
                iobTotal = bolusIob.iob + basalIob.basaliob,
                basalRateUh = basalData?.tempBasalAbsolute ?: 0.0,
                profileBasalRateUh = basalData?.basal ?: 0.0,
                lastDoseUnits = lastDose?.second,
                lastDoseTimestamp = lastDose?.first,
                lastDoseSource = lastDose?.third
            )
        }
    }

    /**
     * The last [DOSE_HISTORY_LIMIT] doses (SMB and manual) with the BG at that moment. Loaded only when
     * the list is opened, because it needs more queries than [refresh]. A manual bolus next to an SMB
     * (within [MANUAL_COINCIDENCE_TOLERANCE_MS]) is added to it, as in [refresh].
     */
    fun loadDoseHistory() {
        viewModelScope.launch {
            _doseHistoryLoading.value = true
            try {
                val sinceMs = dateUtil.now() - DOSE_HISTORY_LOOKBACK_MS
                val boluses = persistenceLayer.getBolusesFromTime(sinceMs, false)
                val bgReadings = iobCobCalculator.ads.dataLock.withLock {
                    iobCobCalculator.ads.getBgReadingsDataTableCopy()
                }
                val atoms = boluses
                    .filter { it.type == BS.Type.SMB || it.type == BS.Type.NORMAL }
                    .map { bs ->
                        AlternativeDoseEntry(
                            timestampMs = bs.timestamp,
                            units = bs.amount,
                            source = if (bs.type == BS.Type.SMB) AlternativeDoseSource.SMB else AlternativeDoseSource.MANUAL,
                            bgMgdl = nearestBgMgdl(bgReadings, bs.timestamp)
                        )
                    }
                    .sortedByDescending { it.timestampMs }

                val merged = mutableListOf<AlternativeDoseEntry>()
                var i = 0
                while (i < atoms.size) {
                    val current = atoms[i]
                    val next = atoms.getOrNull(i + 1)
                    val isManualPair = next != null && (current.source == AlternativeDoseSource.MANUAL) != (next.source == AlternativeDoseSource.MANUAL)
                    if (next != null && isManualPair && abs(current.timestampMs - next.timestampMs) <= MANUAL_COINCIDENCE_TOLERANCE_MS) {
                        merged += AlternativeDoseEntry(
                            timestampMs = maxOf(current.timestampMs, next.timestampMs),
                            units = current.units + next.units,
                            source = AlternativeDoseSource.COMBINED,
                            bgMgdl = current.bgMgdl ?: next.bgMgdl
                        )
                        i += 2
                    } else {
                        merged += current
                        i += 1
                    }
                }
                _doseHistory.value = merged.take(DOSE_HISTORY_LIMIT)
            } finally {
                _doseHistoryLoading.value = false
            }
        }
    }

    /** Nearest BG reading (mg/dl) within [BG_LOOKUP_MAX_GAP_MS] of [targetTs], or null if there is none that close. */
    private fun nearestBgMgdl(bgReadings: List<GV>, targetTs: Long): Double? {
        val nearest = bgReadings.minByOrNull { abs(it.timestamp - targetTs) } ?: return null
        return nearest.value.takeIf { abs(nearest.timestamp - targetTs) <= BG_LOOKUP_MAX_GAP_MS }
    }
}

/**
 * @param sensorAgeMinutes null if no sensor change was ever recorded
 * @param cannulaAgeMinutes null if no cannula change was ever recorded
 * @param profileBasalRateUh the scheduled rate of the profile (no temp basal), to show raised or lowered
 */
data class AlternativeDeviceState(
    val sensorAgeMinutes: Int? = null,
    val cannulaAgeMinutes: Int? = null,
    val reservoirUnits: Double = 0.0,
    val iobTotal: Double = 0.0,
    val basalRateUh: Double = 0.0,
    val profileBasalRateUh: Double = 0.0,
    val lastDoseUnits: Double? = null,
    val lastDoseTimestamp: Long? = null,
    val lastDoseSource: AlternativeDoseSource? = null
)

/** Where a dose came from. COMBINED = a manual bolus and an SMB at the same moment, the amount is the sum. */
enum class AlternativeDoseSource { SMB, MANUAL, COMBINED }

/** One line of the list of last doses. [bgMgdl] is always mg/dl, or null if there was no reading close enough. */
data class AlternativeDoseEntry(
    val timestampMs: Long,
    val units: Double,
    val source: AlternativeDoseSource,
    val bgMgdl: Double?
)
