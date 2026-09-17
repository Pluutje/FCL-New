package app.aaps.plugins.aps.openAPSFCL.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.plugins.aps.compose.OpenAPSScreen
import app.aaps.plugins.aps.compose.OpenAPSViewModel
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui.FclAnalyzerScreen
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui.FclStatisticsScreen

class FCLComposeContent(
    private val apsPlugin: APS,
    private val rxBus: RxBus,
    private val rh: ResourceHelper,
    private val dateUtil: DateUtil,
    private val preferences: Preferences,
    private val sp: SP
) : ComposablePluginContent {

    @Composable
    override fun Render(
        setToolbarConfig: (ToolbarConfig) -> Unit,
        onNavigateBack: () -> Unit,
        onSettings: (() -> Unit)?
    ) {
        // 05/07/2026: één keer, hier op het hoogste niveau, de one-shot
        // navigatievlag lezen (get-and-reset) — bepaalt zowel welke buitenste
        // tab initieel actief is (Analyzer i.p.v. Status) als, doorgegeven aan
        // FclAnalyzerScreen, welk tabblad DAARBINNEN opent (AI Advisor i.p.v.
        // dashboard). Nogmaals lezen verderop zou altijd false opleveren.
        val navigateToAiAdvisor = remember {
            app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiNotificationHelper.consumeNavigateRequest()
        }
        // 10/07/2026 — zelfde one-shot-patroon voor de Learner's
        // MANUAL-melding. Beide vlaggen worden hier één keer gelezen; ze
        // kunnen nooit allebei tegelijk true zijn (elke melding zet alleen
        // haar eigen vlag), dus geen prioriteitsconflict tussen de twee.
        val navigateToLearner = remember {
            app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.FclLearnerNotificationHelper.consumeNavigateRequest()
        }
        // 08/09/2026 (de gebruiker) — zelfde one-shot-patroon voor de
        // update-melding. Kan niet tegelijk met de twee vlaggen hierboven true
        // zijn (elke melding zet alleen haar eigen vlag).
        val navigateToUpdate = remember {
            app.aaps.plugins.aps.openAPSFCL.update.FclUpdateNotificationHelper.consumeNavigateRequest()
        }
        // 13/09/2026 (de gebruiker) — zelfde one-shot-patroon voor de Treatments-sheet
        // "Tijdelijke aanpassing"-snelkoppeling: FclTempOverrideStatusProviderImpl.requestOpenSettings()
        // zet de vlag (via ElementNavigator, commonMain), deze regel leest 'm hier één keer.
        val navigateToTempOverride = remember {
            app.aaps.plugins.aps.openAPSFCL.vnext.FclTempOverrideSettings.consumeOpenSettingsRequest()
        }
        var selectedTab by remember {
            mutableIntStateOf(
                when {
                    navigateToAiAdvisor || navigateToLearner -> 1
                    navigateToUpdate || navigateToTempOverride -> 3
                    else                                       -> 0
                }
            )
        }
        // 14/09/2026 (de gebruiker) — leest de preview-toggle voor het alternatieve
        // overzichtsscherm (zelfde eigen, niet-expert SharedPreferences-patroon als
        // aigfActive/expertModeActive in FCLSettingsScreen.kt). Alleen als dit aan staat, is
        // het 5e tabblad zichtbaar.
        val ctx = LocalContext.current
        var overviewScreenEnabled by remember {
            mutableStateOf(
                ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .getBoolean(PREF_OVERVIEW_SCREEN_ENABLED, false)
            )
        }
        val scope = rememberCoroutineScope()
        val viewModel = remember {
            OpenAPSViewModel(
                apsPlugin = apsPlugin,
                rxBus = rxBus,
                rh = rh,
                dateUtil = dateUtil,
                scope = scope
            )
        }
        val state = viewModel.uiState.collectAsStateWithLifecycle()

        Column(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Status") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Analyzer") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Statistics") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("Settings") }
                )
                // 15/09/2026 (de gebruiker) — het 5e tabblad ("Overzicht") is verwijderd: het
                // alternatieve overzichtsscherm is nu het ECHTE AAPS-hoofdscherm zelf (zie
                // OpenAPSFCLPlugin.overviewOverride), niet meer apart bereikbaar als tabblad hier.
                // overviewScreenEnabled hieronder bestaat verder alleen nog om de toggle-status in
                // FCLSettingsScreen.kt weer te geven/bij te werken.
            }

            when (selectedTab) {
                0 -> OpenAPSScreen(
                    state = state.value,
                    onRefresh = viewModel::onRefresh
                )
                1 -> FclAnalyzerScreen(
                    onDismiss = { selectedTab = 0 },
                    startOnAiAdvisor = navigateToAiAdvisor,
                    startOnLearner = navigateToLearner
                )
                2 -> FclStatisticsScreen()
                3 -> FCLSettingsScreen(
                    preferences = preferences,
                    sp = sp,
                    startExpandedOnUpdates = navigateToUpdate,
                    startExpandedOnTempOverride = navigateToTempOverride,
                    overviewScreenEnabled = overviewScreenEnabled,
                    onOverviewScreenEnabledChange = { overviewScreenEnabled = it },
                    rxBus = rxBus
                )
            }
        }
    }

}