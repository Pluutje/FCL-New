package app.aaps.plugins.aps.openAPSFCL.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.DateUtil
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
    private val dateUtil: DateUtil
) : ViewModel() {

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
                profileBasalRateUh = profileBasalRateUh
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
    val profileBasalRateUh: Double = 0.0
)
