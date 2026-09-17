package app.aaps.ui.compose.overview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aaps.core.data.model.ActiveSceneState
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.overview.graph.TbrState
import app.aaps.core.interfaces.pump.BolusProgressState
import app.aaps.core.ui.compose.CrashSafeContent
import app.aaps.core.ui.compose.OverviewOverrideContent
import app.aaps.core.ui.compose.isLandscape
import app.aaps.core.ui.compose.smallestScreenWidthDp
import app.aaps.core.ui.compose.TABLET_MIN_SW_DP
import app.aaps.core.ui.compose.navigation.LocalPluginNavigationRequest
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.pump.PumpActivityDialog
import app.aaps.core.ui.compose.pump.PumpActivityFab
import app.aaps.ui.compose.main.TempTargetChipState
import app.aaps.ui.compose.manageSheet.ManageViewModel
import app.aaps.ui.compose.notificationsSheet.NotificationBottomSheet
import app.aaps.ui.compose.notificationsSheet.NotificationFab
import app.aaps.ui.compose.overview.chips.ChipsViewModel
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.statusLights.StatusViewModel
import kotlinx.coroutines.delay

private val SPLIT_LAYOUT_MIN_WIDTH: Dp = 720.dp

@Composable
fun OverviewScreen(
    profileName: String,
    profilePsId: Long = 0,
    isProfileModified: Boolean,
    profileProgress: Float,
    tempTargetText: String,
    tempTargetState: TempTargetChipState,
    tempTargetProgress: Float,
    tempTargetReason: TT.Reason?,
    tempTargetRecordId: Long = 0,
    runningMode: RM.Mode,
    runningModeText: String,
    runningModeRemaining: String,
    runningModeProgress: Float,
    runningModeRecordId: Long = 0,
    tbrState: TbrState,
    smbEnabled: Boolean,
    isSimpleMode: Boolean,
    calcProgress: Int,
    graphViewModel: GraphViewModel,
    chipsViewModel: ChipsViewModel,
    manageViewModel: ManageViewModel,
    statusViewModel: StatusViewModel,
    statusLightsDef: PreferenceSubScreenDef,
    onNavigate: (NavigationRequest) -> Unit,
    onTbrChipClick: () -> Unit,
    onIobChipClick: () -> Unit,
    notifications: List<AapsNotification>,
    onDismissNotification: (AapsNotification) -> Unit,
    onNotificationActionClick: (AapsNotification) -> Unit,
    autoShowNotificationSheet: Boolean,
    onAutoShowConsumed: () -> Unit,
    activeSceneState: ActiveSceneState? = null,
    sceneExpired: Boolean = false,
    onEndScene: () -> Unit = {},
    onDismissScene: () -> Unit = {},
    endSceneEnabled: Boolean = true,
    // Disables the command chips' click (running mode / profile / temp target) on an unpaired client — same gate as nav/Manage.
    commandsAllowed: Boolean = true,
    formatDuration: (Long) -> String = { ms -> "${(ms / 60000L).toInt()}m" },
    paddingValues: PaddingValues,
    fabBottomOffset: Dp = 0.dp,
    bolusState: BolusProgressState? = null,
    pumpStatusText: String = "",
    queueStatusText: AnnotatedString? = null,
    isPumpCommunicating: Boolean = false,
    onStopBolus: () -> Unit = {},
    modifier: Modifier = Modifier,
    // 15/09/2026 (de gebruiker) — optional home-screen replacement contributed by the active APS
    // plugin (APS.overviewOverride, "alternatief hoofdscherm" toggle for FCLvNext). Typed `Any?`
    // for the same module-boundary reason as `PluginBase.getComposeContent()`: `core:interfaces`
    // (where APS lives) cannot depend on `core:ui` (where OverviewOverrideContent lives). Rendered
    // through CrashSafeContent so a bug in the override screen falls back to the standard content
    // below instead of crashing the app — see CrashSafeContent's kdoc for what that does and does
    // not guarantee.
    overviewOverride: Any? = null
) {
    val override = overviewOverride as? OverviewOverrideContent
    if (override != null) {
        CrashSafeContent(
            modifier = modifier.fillMaxSize(),
            // Same top/bottom insets the standard screen's own branches apply internally (see
            // OverviewScreenStacked/Tablet/Split) — without this, the override screen's content
            // renders from y=0 and gets covered by the floating top search bar. Applied only to
            // this slot (not to CrashSafeContent's own modifier above), so the fallback slot below
            // keeps applying paddingValues exactly the way it already does when there's no override
            // at all — it would otherwise get padded twice.
            //
            // 17/09/2026 (de gebruiker) — none of the override screen's buttons worked once it
            // became the real home screen: LocalPluginNavigationRequest.current defaults to a no-op
            // lambda everywhere except inside AppNavGraph's plugin-content route (see its kdoc),
            // which this home-screen path never goes through. `onNavigate` below IS the same real,
            // working navigation dispatcher the rest of this screen already uses (chip clicks,
            // settings gear, ...), so provide it as the CompositionLocal's value here too.
            content = {
                CompositionLocalProvider(LocalPluginNavigationRequest provides onNavigate) {
                    Box(Modifier.fillMaxSize().padding(paddingValues)) { override() }
                }
            },
            fallback = {
                StandardOverviewScreenContent(
                    profileName = profileName,
                    profilePsId = profilePsId,
                    isProfileModified = isProfileModified,
                    profileProgress = profileProgress,
                    tempTargetText = tempTargetText,
                    tempTargetState = tempTargetState,
                    tempTargetProgress = tempTargetProgress,
                    tempTargetReason = tempTargetReason,
                    tempTargetRecordId = tempTargetRecordId,
                    runningMode = runningMode,
                    runningModeText = runningModeText,
                    runningModeRemaining = runningModeRemaining,
                    runningModeProgress = runningModeProgress,
                    runningModeRecordId = runningModeRecordId,
                    tbrState = tbrState,
                    smbEnabled = smbEnabled,
                    isSimpleMode = isSimpleMode,
                    calcProgress = calcProgress,
                    graphViewModel = graphViewModel,
                    chipsViewModel = chipsViewModel,
                    manageViewModel = manageViewModel,
                    statusViewModel = statusViewModel,
                    statusLightsDef = statusLightsDef,
                    onNavigate = onNavigate,
                    onTbrChipClick = onTbrChipClick,
                    onIobChipClick = onIobChipClick,
                    notifications = notifications,
                    onDismissNotification = onDismissNotification,
                    onNotificationActionClick = onNotificationActionClick,
                    autoShowNotificationSheet = autoShowNotificationSheet,
                    onAutoShowConsumed = onAutoShowConsumed,
                    activeSceneState = activeSceneState,
                    sceneExpired = sceneExpired,
                    onEndScene = onEndScene,
                    onDismissScene = onDismissScene,
                    endSceneEnabled = endSceneEnabled,
                    commandsAllowed = commandsAllowed,
                    formatDuration = formatDuration,
                    paddingValues = paddingValues,
                    fabBottomOffset = fabBottomOffset,
                    bolusState = bolusState,
                    pumpStatusText = pumpStatusText,
                    queueStatusText = queueStatusText,
                    isPumpCommunicating = isPumpCommunicating,
                    onStopBolus = onStopBolus,
                    modifier = modifier
                )
            }
        )
    } else {
        StandardOverviewScreenContent(
            profileName = profileName,
            profilePsId = profilePsId,
            isProfileModified = isProfileModified,
            profileProgress = profileProgress,
            tempTargetText = tempTargetText,
            tempTargetState = tempTargetState,
            tempTargetProgress = tempTargetProgress,
            tempTargetReason = tempTargetReason,
            tempTargetRecordId = tempTargetRecordId,
            runningMode = runningMode,
            runningModeText = runningModeText,
            runningModeRemaining = runningModeRemaining,
            runningModeProgress = runningModeProgress,
            runningModeRecordId = runningModeRecordId,
            tbrState = tbrState,
            smbEnabled = smbEnabled,
            isSimpleMode = isSimpleMode,
            calcProgress = calcProgress,
            graphViewModel = graphViewModel,
            chipsViewModel = chipsViewModel,
            manageViewModel = manageViewModel,
            statusViewModel = statusViewModel,
            statusLightsDef = statusLightsDef,
            onNavigate = onNavigate,
            onTbrChipClick = onTbrChipClick,
            onIobChipClick = onIobChipClick,
            notifications = notifications,
            onDismissNotification = onDismissNotification,
            onNotificationActionClick = onNotificationActionClick,
            autoShowNotificationSheet = autoShowNotificationSheet,
            onAutoShowConsumed = onAutoShowConsumed,
            activeSceneState = activeSceneState,
            sceneExpired = sceneExpired,
            onEndScene = onEndScene,
            onDismissScene = onDismissScene,
            endSceneEnabled = endSceneEnabled,
            commandsAllowed = commandsAllowed,
            formatDuration = formatDuration,
            paddingValues = paddingValues,
            fabBottomOffset = fabBottomOffset,
            bolusState = bolusState,
            pumpStatusText = pumpStatusText,
            queueStatusText = queueStatusText,
            isPumpCommunicating = isPumpCommunicating,
            onStopBolus = onStopBolus,
            modifier = modifier
        )
    }
}

/** The standard overview (home) screen content — unchanged, just factored out of [OverviewScreen] so it can also serve as [CrashSafeContent]'s fallback slot. */
@Composable
private fun StandardOverviewScreenContent(
    profileName: String,
    profilePsId: Long = 0,
    isProfileModified: Boolean,
    profileProgress: Float,
    tempTargetText: String,
    tempTargetState: TempTargetChipState,
    tempTargetProgress: Float,
    tempTargetReason: TT.Reason?,
    tempTargetRecordId: Long = 0,
    runningMode: RM.Mode,
    runningModeText: String,
    runningModeRemaining: String,
    runningModeProgress: Float,
    runningModeRecordId: Long = 0,
    tbrState: TbrState,
    smbEnabled: Boolean,
    isSimpleMode: Boolean,
    calcProgress: Int,
    graphViewModel: GraphViewModel,
    chipsViewModel: ChipsViewModel,
    manageViewModel: ManageViewModel,
    statusViewModel: StatusViewModel,
    statusLightsDef: PreferenceSubScreenDef,
    onNavigate: (NavigationRequest) -> Unit,
    onTbrChipClick: () -> Unit,
    onIobChipClick: () -> Unit,
    notifications: List<AapsNotification>,
    onDismissNotification: (AapsNotification) -> Unit,
    onNotificationActionClick: (AapsNotification) -> Unit,
    autoShowNotificationSheet: Boolean,
    onAutoShowConsumed: () -> Unit,
    activeSceneState: ActiveSceneState? = null,
    sceneExpired: Boolean = false,
    onEndScene: () -> Unit = {},
    onDismissScene: () -> Unit = {},
    endSceneEnabled: Boolean = true,
    commandsAllowed: Boolean = true,
    formatDuration: (Long) -> String = { ms -> "${(ms / 60000L).toInt()}m" },
    paddingValues: PaddingValues,
    fabBottomOffset: Dp = 0.dp,
    bolusState: BolusProgressState? = null,
    pumpStatusText: String = "",
    queueStatusText: AnnotatedString? = null,
    isPumpCommunicating: Boolean = false,
    onStopBolus: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showNotificationSheet by remember { mutableStateOf(false) }
    var showPumpActivityDialog by remember { mutableStateOf(false) }
    val showPumpFab = isPumpCommunicating || (bolusState != null && bolusState.isSMB)

    LaunchedEffect(showPumpFab) {
        if (!showPumpFab && showPumpActivityDialog) {
            delay(3_000)
            showPumpActivityDialog = false
        }
    }

    LaunchedEffect(autoShowNotificationSheet) {
        if (autoShowNotificationSheet) {
            showNotificationSheet = true
            onAutoShowConsumed()
        }
    }

    val runningModeSceneManaged = activeSceneState?.scopedRecords?.rmId
        ?.let { it == runningModeRecordId && it > 0 } == true
    val tempTargetSceneManaged = activeSceneState?.scopedRecords?.ttId
        ?.let { it == tempTargetRecordId && it > 0 } == true
    val profileSceneManaged = activeSceneState?.scopedRecords?.psId
        ?.let { it == profilePsId && it > 0 } == true

    val isLandscape = isLandscape()
    val isTablet = smallestScreenWidthDp() >= TABLET_MIN_SW_DP && isLandscape

    Box(modifier = modifier.fillMaxSize()) {
        if (isTablet) {
            OverviewScreenTablet(
                profileName = profileName,
                isProfileModified = isProfileModified,
                profileProgress = profileProgress,
                profileSceneManaged = profileSceneManaged,
                tempTargetText = tempTargetText,
                tempTargetState = tempTargetState,
                tempTargetProgress = tempTargetProgress,
                tempTargetReason = tempTargetReason,
                tempTargetSceneManaged = tempTargetSceneManaged,
                runningMode = runningMode,
                runningModeText = runningModeText,
                runningModeRemaining = runningModeRemaining,
                runningModeProgress = runningModeProgress,
                runningModeSceneManaged = runningModeSceneManaged,
                tbrState = tbrState,
                smbEnabled = smbEnabled,
                isSimpleMode = isSimpleMode,
                graphViewModel = graphViewModel,
                chipsViewModel = chipsViewModel,
                manageViewModel = manageViewModel,
                statusViewModel = statusViewModel,
                statusLightsDef = statusLightsDef,
                onNavigate = onNavigate,
                onTbrChipClick = onTbrChipClick,
                onIobChipClick = onIobChipClick,
                paddingValues = paddingValues,
                activeSceneState = activeSceneState,
                sceneExpired = sceneExpired,
                onEndScene = onEndScene,
                onDismissScene = onDismissScene,
                endSceneEnabled = endSceneEnabled,
                commandsAllowed = commandsAllowed,
                formatDuration = formatDuration
            )
        } else BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            if (isLandscape && maxWidth >= SPLIT_LAYOUT_MIN_WIDTH) {
                OverviewScreenSplit(
                    profileName = profileName,
                    isProfileModified = isProfileModified,
                    profileProgress = profileProgress,
                    profileSceneManaged = profileSceneManaged,
                    tempTargetText = tempTargetText,
                    tempTargetState = tempTargetState,
                    tempTargetProgress = tempTargetProgress,
                    tempTargetReason = tempTargetReason,
                    tempTargetSceneManaged = tempTargetSceneManaged,
                    runningMode = runningMode,
                    runningModeText = runningModeText,
                    runningModeRemaining = runningModeRemaining,
                    runningModeProgress = runningModeProgress,
                    runningModeSceneManaged = runningModeSceneManaged,
                    tbrState = tbrState,
                    smbEnabled = smbEnabled,
                    isSimpleMode = isSimpleMode,
                    graphViewModel = graphViewModel,
                    chipsViewModel = chipsViewModel,
                    manageViewModel = manageViewModel,
                    statusViewModel = statusViewModel,
                    statusLightsDef = statusLightsDef,
                    onNavigate = onNavigate,
                    onTbrChipClick = onTbrChipClick,
                    onIobChipClick = onIobChipClick,
                    paddingValues = paddingValues,
                    activeSceneState = activeSceneState,
                    sceneExpired = sceneExpired,
                    onEndScene = onEndScene,
                    onDismissScene = onDismissScene,
                    endSceneEnabled = endSceneEnabled,
                    commandsAllowed = commandsAllowed,
                    formatDuration = formatDuration
                )
            } else {
                OverviewScreenStacked(
                    profileName = profileName,
                    isProfileModified = isProfileModified,
                    profileProgress = profileProgress,
                    profileSceneManaged = profileSceneManaged,
                    tempTargetText = tempTargetText,
                    tempTargetState = tempTargetState,
                    tempTargetProgress = tempTargetProgress,
                    tempTargetReason = tempTargetReason,
                    tempTargetSceneManaged = tempTargetSceneManaged,
                    runningMode = runningMode,
                    runningModeText = runningModeText,
                    runningModeRemaining = runningModeRemaining,
                    runningModeProgress = runningModeProgress,
                    runningModeSceneManaged = runningModeSceneManaged,
                    tbrState = tbrState,
                    smbEnabled = smbEnabled,
                    isSimpleMode = isSimpleMode,
                    graphViewModel = graphViewModel,
                    chipsViewModel = chipsViewModel,
                    manageViewModel = manageViewModel,
                    statusViewModel = statusViewModel,
                    statusLightsDef = statusLightsDef,
                    onNavigate = onNavigate,
                    onTbrChipClick = onTbrChipClick,
                    onIobChipClick = onIobChipClick,
                    paddingValues = paddingValues,
                    activeSceneState = activeSceneState,
                    sceneExpired = sceneExpired,
                    onEndScene = onEndScene,
                    onDismissScene = onDismissScene,
                    endSceneEnabled = endSceneEnabled,
                    commandsAllowed = commandsAllowed,
                    formatDuration = formatDuration
                )
            }
        }

        // Calculation progress (IOB / graph data). Overlaid on top of content so it never reflows
        // the layout — previously a flow child of the content Column which caused the screen to jump.
        AnimatedVisibility(
            visible = calcProgress < 100,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(paddingValues)
                .fillMaxWidth()
        ) {
            LinearProgressIndicator(
                progress = { calcProgress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
            )
        }

        PumpActivityFab(
            visible = showPumpFab,
            bolusState = bolusState,
            onClick = { showPumpActivityDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(paddingValues)
                .padding(end = 16.dp, bottom = 128.dp + fabBottomOffset)
        )

        NotificationFab(
            notificationCount = notifications.size,
            highestLevel = notifications.minByOrNull { it.level.ordinal }?.level,
            onClick = { showNotificationSheet = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(paddingValues)
                .padding(end = 16.dp, bottom = 72.dp + fabBottomOffset)
        )
    }

    if (showPumpActivityDialog) {
        PumpActivityDialog(
            bolusState = bolusState,
            pumpStatus = pumpStatusText,
            queueStatus = queueStatusText,
            isModal = false,
            onStop = onStopBolus,
            onDismiss = { showPumpActivityDialog = false }
        )
    }

    if (showNotificationSheet && notifications.isNotEmpty()) {
        NotificationBottomSheet(
            notifications = notifications,
            onDismissSheet = { showNotificationSheet = false },
            onDismissNotification = onDismissNotification,
            onNotificationActionClick = onNotificationActionClick
        )
    }
}