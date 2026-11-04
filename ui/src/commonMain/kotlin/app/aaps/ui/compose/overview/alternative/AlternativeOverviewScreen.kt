package app.aaps.ui.compose.overview.alternative

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.aaps.core.data.configuration.Constants
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.overview.graph.GraphConfig
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.IntNonKey
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.icons.IcTbrHigh
import app.aaps.core.ui.compose.icons.IcTbrLow
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.navigation.LocalPluginNavigationRequest
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.ui.compose.manageSheet.ManageSheetHost
import app.aaps.ui.compose.manageSheet.ManageViewModel
import app.aaps.ui.compose.overview.BgInfoSection
import app.aaps.ui.compose.overview.graphs.BgGraphCompose
import app.aaps.ui.compose.overview.graphs.GraphInspectOverlay
import app.aaps.ui.compose.overview.graphs.GraphInspectState
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.graphs.INTERACTION_GRACE_MS
import app.aaps.ui.compose.overview.graphs.MIN_GRAPH_ZOOM_MINUTES
import app.aaps.ui.compose.overview.graphs.NiceScale
import app.aaps.ui.compose.overview.graphs.SecondaryGraphCompose
import app.aaps.ui.compose.overview.graphs.graphInspectTouch
import app.aaps.ui.compose.overview.graphs.timestampToX
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

/** Lowest height the user can set for a graph. Lower than on the standard home screen (100 dp). */
private const val MIN_GRAPH_HEIGHT_DP = 50

/** Fixed x-axis window: 3 hours before "now", 2 hours after (room for the predictions). */
private const val WINDOW_HOURS_BACK = 3
private const val WINDOW_HOURS_FORWARD = 2
private const val WINDOW_TOTAL_MINUTES = (WINDOW_HOURS_BACK + WINDOW_HOURS_FORWARD) * 60.0

/** Height of the inspect pill compared to the top of the BG graph. Negative: above the graph, over the title line. */
private val INSPECT_PILL_OFFSET = (-30).dp

/**
 * The alternative home screen for every APS plugin that has no screen of its own (OpenAPS SMB, AMA,
 * AutoISF, ...). It is switched on in Settings, Appearance (`BooleanKey.OverviewAlternativeScreen`).
 *
 * It is a full copy of the idea behind FCLvNext's home screen on purpose, so a change in one does not
 * break the other. Differences: no Override button and band (an FCLvNext function), and the last dose
 * comes from the AAPS boluses.
 *
 * Layout: a card with the BG circle, IOB and basal; a card with sensor, pump and profile; a BG graph
 * and an IOB graph (one shared scroll and zoom, so they move together); and four buttons.
 */
@OptIn(FlowPreview::class)
@Composable
fun AlternativeOverviewScreen() {
    val viewModel: AlternativeOverviewViewModel = metroViewModel()
    val graphViewModel: GraphViewModel = viewModel(
        factory = remember {
            viewModelFactory {
                initializer { viewModel.graphViewModelFactory.create(viewModel.overviewDataCache, fullWindow = false) }
            }
        }
    )
    val overviewDataCache = viewModel.overviewDataCache
    val dateUtil = viewModel.dateUtil
    val profileUtil = viewModel.profileUtil
    val navigationRequest = LocalPluginNavigationRequest.current

    // The Manage sheet is the standard one (cancel TBR, cancel extended bolus, fill, pump, ...).
    // This screen has no snackbar host and an error here is rare, so the error is dropped.
    val manageViewModel: ManageViewModel = metroViewModel()
    val manageSheetState = ManageSheetHost(
        manageViewModel = manageViewModel,
        isSimpleMode = false,
        onNavigate = navigationRequest,
        onActionsError = { _, _ -> }
    )

    var showDoseHistory by remember { mutableStateOf(false) }

    // Height of the two graphs, kept in the preferences.
    var bgGraphHeight by remember { mutableIntStateOf(viewModel.preferences.get(IntNonKey.OverviewAlternativeBgHeight)) }
    var iobGraphHeight by remember { mutableIntStateOf(viewModel.preferences.get(IntNonKey.OverviewAlternativeIobHeight)) }

    val bgInfo by overviewDataCache.bgInfoFlow.collectAsState()
    val profile by overviewDataCache.profileFlow.collectAsState()
    val targetLine by overviewDataCache.targetLineFlow.collectAsState()
    val deviceState by viewModel.deviceState.collectAsState()
    val doseHistory by viewModel.doseHistory.collectAsState()
    val doseHistoryLoading by viewModel.doseHistoryLoading.collectAsState()

    // One scroll and zoom state for both graphs: separate cards, one time axis. Zoom.x() makes a new
    // instance every time, so it is remembered here (a new one would reset the zoom at each update).
    val fixedZoomWidth = remember { Zoom.x(WINDOW_TOTAL_MINUTES) }
    val minZoom = remember { Zoom.x(Constants.GRAPH_TIME_RANGE_HOURS * 60.0) }
    val maxZoom = remember { Zoom.x(MIN_GRAPH_ZOOM_MINUTES) }
    var viewportResetTrigger by remember { mutableIntStateOf(0) }
    val (sharedScrollState, sharedZoomState) = key(viewportResetTrigger) {
        rememberVicoScrollState(scrollEnabled = true, initialScroll = Scroll.Absolute.End) to
            rememberVicoZoomState(zoomEnabled = true, initialZoom = fixedZoomWidth, minZoom = minZoom, maxZoom = maxZoom)
    }

    val now by graphViewModel.nowTimestamp.collectAsStateWithLifecycle()
    val derivedTimeRange by graphViewModel.derivedTimeRange.collectAsStateWithLifecycle()

    // Long press on the BG graph and move: time, BG, IOB, COB, basal and boluses at that point.
    val inspect = remember { GraphInspectState() }
    val inspectBgReadings by graphViewModel.bgReadingsFlow.collectAsStateWithLifecycle()

    // The real visible x-range of the IOB graph, so the BG axis scale follows the part that is shown.
    var iobVisibleRange by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var iobVisibleRangeSettled by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { iobVisibleRange }
            .debounce(400)
            .collect { iobVisibleRangeSettled = it }
    }
    val bgVisibleTimeRange = derivedTimeRange?.first?.let { minTs ->
        iobVisibleRangeSettled?.let { (minXv, maxXv) ->
            (minTs + (minXv * 60_000).toLong()) to (minTs + (maxXv * 60_000).toLong())
        }
    }

    // `now` changes every 30 seconds: read IOB and basal again at the same pace as the standard chips.
    LaunchedEffect(now) { viewModel.refresh() }

    // After a reset, put "now" again at WINDOW_HOURS_FORWARD from the right edge (room for the predictions).
    LaunchedEffect(viewportResetTrigger, sharedScrollState) {
        if (viewportResetTrigger == 0) return@LaunchedEffect
        val (minTs, _) = derivedTimeRange ?: return@LaunchedEffect
        val nowX = timestampToX(now, minTs)
        sharedScrollState.animateScroll(Scroll.Absolute.x(nowX + WINDOW_HOURS_FORWARD * 60.0, bias = 1f))
    }

    // Jump back to "now -3h / now +2h" at every new BG value, unless the user is scrolling or zooming right now.
    val bgInfoState by graphViewModel.bgInfoState.collectAsStateWithLifecycle()
    var lastBgTimestamp by remember { mutableLongStateOf(0L) }
    LaunchedEffect(bgInfoState.bgInfo?.timestamp) {
        val newTimestamp = bgInfoState.bgInfo?.timestamp ?: return@LaunchedEffect
        if (lastBgTimestamp != 0L && newTimestamp > lastBgTimestamp) {
            val sinceInteraction = dateUtil.now() - graphViewModel.lastInteractionMs
            if (sinceInteraction < INTERACTION_GRACE_MS) {
                lastBgTimestamp = newTimestamp
                return@LaunchedEffect
            }
            viewportResetTrigger++
        }
        lastBgTimestamp = newTimestamp
    }

    // Tell the view model about manual scroll and zoom, so the reset above does not break a gesture.
    LaunchedEffect(sharedScrollState, sharedZoomState) {
        var initialValue = true
        snapshotFlow { sharedScrollState.value to sharedZoomState.value }
            .debounce(30)
            .collect {
                if (initialValue) initialValue = false else graphViewModel.onGraphInteraction()
            }
    }

    val visibleWindowFallback = remember(now) {
        (now - WINDOW_HOURS_BACK * 3600_000L) to (now + WINDOW_HOURS_FORWARD * 3600_000L)
    }
    val visibleWindow = bgVisibleTimeRange ?: visibleWindowFallback

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            // Extra room on top: the version text is drawn over the content at the top right.
            .padding(top = 28.dp, start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Main card: the BG circle with the trend arc, and IOB and basal next to it.
        DashCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                BgInfoSection(
                    bgInfo = bgInfo,
                    timeAgoText = timeAgoText(bgInfo?.timestamp, dateUtil),
                    timeAgoStyle = MaterialTheme.typography.labelSmall
                )
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Column(modifier = Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillChip(
                            modifier = Modifier.fillMaxWidth(),
                            label = "IOB",
                            value = "${formatDecimals(deviceState.iobTotal, 2)} E",
                            centered = true,
                            fillWidth = true
                        )
                        PillChip(
                            modifier = Modifier.fillMaxWidth(),
                            label = "Basaal",
                            value = "${formatDecimals(deviceState.basalRateUh, 2)} E/u",
                            centered = true,
                            fillWidth = true,
                            trailingIcon = basalTrendIcon(deviceState.basalRateUh, deviceState.profileBasalRateUh)
                        )
                    }
                }
            }
        }

        // Sensor, pump (cannula age and reservoir together) and profile. All three can be tapped.
        DashCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip(
                    modifier = Modifier.weight(1f),
                    label = "Sensor",
                    value = formatAge(deviceState.sensorAgeMinutes),
                    centered = true,
                    fillWidth = true,
                    onClick = { navigationRequest(NavigationRequest.Element(ElementType.CGM_OPEN_SCREEN)) }
                )
                PillChip(
                    modifier = Modifier.weight(1f),
                    label = "Pomp",
                    value = "${formatAge(deviceState.cannulaAgeMinutes)} · ${deviceState.reservoirUnits.roundToInt()} E",
                    centered = true,
                    fillWidth = true,
                    onClick = { navigationRequest(NavigationRequest.Element(ElementType.PUMP)) }
                )
                PillChip(
                    modifier = Modifier.weight(1f),
                    label = "Profiel",
                    value = profile?.profileName ?: "-",
                    centered = true,
                    fillWidth = true,
                    onClick = { navigationRequest(NavigationRequest.Element(ElementType.PROFILE_MANAGEMENT)) }
                )
            }
        }

        // BG graph. The predictions come from the shared BgGraphCompose (SeriesType.PREDICTIONS).
        GraphCard(
            title = "BG",
            height = bgGraphHeight,
            onHeightChange = { h ->
                bgGraphHeight = h
                viewModel.preferences.put(IntNonKey.OverviewAlternativeBgHeight, h)
            },
            trailingText = "Target: " + targetValueText(targetLine.targets.lastOrNull()?.value)
        ) { graphModifier ->
            Box(modifier = Modifier.graphInspectTouch(inspect)) {
                BgGraphCompose(
                    viewModel = graphViewModel,
                    bgOverlays = listOf(SeriesType.PREDICTIONS, SeriesType.ACTIVITY),
                    scrollState = sharedScrollState,
                    zoomState = sharedZoomState,
                    derivedTimeRange = derivedTimeRange,
                    nowTimestamp = now,
                    visibleTimeRange = visibleWindow,
                    yRangeOverride = ::bgYRange,
                    inRangeColorOverride = DashBgInRangeColor,
                    highColorOverride = DashBgHighColor,
                    inspect = inspect,
                    modifier = graphModifier
                )
                GraphInspectOverlay(
                    inspect = inspect,
                    graphViewModel = graphViewModel,
                    bgReadings = inspectBgReadings,
                    minTimestamp = derivedTimeRange?.first,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = INSPECT_PILL_OFFSET)
                        .padding(top = 2.dp, start = 36.dp, end = 36.dp)
                )
            }
        }

        // IOB and basal graph: same scroll and zoom as the BG graph above.
        GraphCard(
            title = "IOB / basaal",
            height = iobGraphHeight,
            onHeightChange = { h ->
                iobGraphHeight = h
                viewModel.preferences.put(IntNonKey.OverviewAlternativeIobHeight, h)
            },
            trailingText = lastDoseText(deviceState.lastDoseUnits, deviceState.lastDoseTimestamp, deviceState.lastDoseSource, dateUtil),
            onTrailingClick = if (deviceState.lastDoseTimestamp != null) {
                { viewModel.loadDoseHistory(); showDoseHistory = true }
            } else null
        ) { graphModifier ->
            SecondaryGraphCompose(
                viewModel = graphViewModel,
                seriesTypes = listOf(SeriesType.IOB),
                scrollState = sharedScrollState,
                zoomState = sharedZoomState,
                derivedTimeRange = derivedTimeRange,
                nowTimestamp = now,
                onVisibleRangeChanged = { iobVisibleRange = it },
                inspect = inspect,
                modifier = graphModifier
            )
        }

        // Shortcuts: four buttons of the same size.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                modifier = Modifier.weight(1f),
                label = "Insuline",
                onClick = { navigationRequest(NavigationRequest.Element(ElementType.INSULIN)) }
            )
            ActionButton(
                modifier = Modifier.weight(1f),
                label = "Koolh.",
                onClick = { navigationRequest(NavigationRequest.Element(ElementType.CARBS)) }
            )
            ActionButton(
                modifier = Modifier.weight(1f),
                label = "Doel",
                onClick = { navigationRequest(NavigationRequest.Element(ElementType.TEMP_TARGET_MANAGEMENT)) }
            )
            ActionButton(
                modifier = Modifier.weight(1f),
                label = "Manage",
                onClick = { manageSheetState.show() }
            )
        }
    }
    if (showDoseHistory) {
        DoseHistoryDialog(
            entries = doseHistory,
            loading = doseHistoryLoading,
            unitLabel = profileUtil.unitLabel,
            bgText = { mgdl -> profileUtil.fromMgdlToStringInUnits(mgdl) },
            dateUtil = dateUtil,
            onDismiss = { showDoseHistory = false }
        )
    }
}

/**
 * Card for a graph: a title, an optional text at the right of the title, and the graph itself.
 * [onTrailingClick] makes the text a small raised button (used for the last dose).
 * The pencil at the top right opens a sheet where the user sets the height of the graph ([onHeightChange]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GraphCard(
    title: String,
    height: Int,
    onHeightChange: (Int) -> Unit,
    trailingText: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    graph: @Composable (Modifier) -> Unit
) {
    var editingHeight by remember { mutableStateOf(false) }
    DashCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = DashOnSurface)
            if (trailingText != null) {
                val trailingShape = RoundedCornerShape(8.dp)
                val trailingModifier = if (onTrailingClick != null) {
                    Modifier
                        .shadow(elevation = 1.dp, shape = trailingShape, clip = false)
                        .background(color = DashChipBackground.copy(alpha = 0.55f), shape = trailingShape)
                        .border(width = 0.5.dp, color = DashChipBorder.copy(alpha = 0.5f), shape = trailingShape)
                        .clickable(onClick = onTrailingClick)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                } else {
                    Modifier
                }
                Text(
                    text = trailingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DashOnSurfaceMuted,
                    modifier = trailingModifier
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            graph(Modifier.fillMaxWidth().height(height.dp))
            IconButton(
                onClick = { editingHeight = true },
                modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = DashOnSurfaceMuted)
            ) {
                Icon(imageVector = Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
    if (editingHeight) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { editingHeight = false }, sheetState = sheetState) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                NumberInputRow(
                    labelRef = CoreUiStrings.graph_height,
                    value = height.toDouble(),
                    onValueChange = { onHeightChange(it.toInt()) },
                    valueRange = MIN_GRAPH_HEIGHT_DP.toDouble()..GraphConfig.MAX_GRAPH_HEIGHT_DP.toDouble(),
                    step = 10.0,
                    formatAsInt = true
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** Y axis of the BG graph: 2 to 12 by default; if the visible data goes above 10, the top is (that value + 2), rounded up. */
private fun bgYRange(dataMin: Double, dataMax: Double): NiceScale {
    val minY = 2.0
    val maxY = if (dataMax > 10.0) ceil(dataMax + 2.0) else 12.0
    return NiceScale(minY, maxY)
}

/** The big floating card: a real shadow with a blue glow, on a dark card color. */
@Composable
private fun DashCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = DashGlowColor.copy(alpha = 0.55f),
                spotColor = DashGlowColor.copy(alpha = 0.85f)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = DashCardBackground),
        border = BorderStroke(1.dp, DashCardStroke)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

/**
 * Small pill with a label and a value. The lighter fill color against the dark card gives the raised look.
 *
 * @param fillWidth true: the pill gets [modifier] as it is (for example `weight(1f)`); false: it fits its content.
 * @param trailingIcon an optional arrow at the right, over the full height of the pill (used for raised or lowered basal).
 */
@Composable
private fun PillChip(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    centered: Boolean = false,
    fillWidth: Boolean = false,
    trailingIcon: ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    val cardModifier = if (fillWidth) modifier else modifier.wrapContentWidth()
    val pillTextSize = 13.sp
    val chipContent: @Composable () -> Unit = {
        if (trailingIcon != null) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = pillTextSize,
                        color = DashOnSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = pillTextSize,
                        color = DashOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    tint = DashOnSurface,
                    modifier = Modifier.padding(start = 8.dp).height(20.dp).width(20.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = pillTextSize,
                    color = DashOnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = pillTextSize,
                    color = DashOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DashChipBackground),
            border = BorderStroke(1.dp, DashChipBorder),
            content = { chipContent() }
        )
    } else {
        Card(
            modifier = cardModifier,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DashChipBackground),
            border = BorderStroke(1.dp, DashChipBorder),
            content = { chipContent() }
        )
    }
}

// Fixed colors. They are not taken from the theme on purpose: the card (1E2133) is darker than the pill
// (2F3C5C) in it, and that difference, with the shadow on the card, gives the raised look.
private val DashCardBackground = Color(0xFF1E2133)
private val DashCardStroke = Color(0xFF334067)
private val DashChipBackground = Color(0xFF2F3C5C)
private val DashChipBorder = Color(0xFF4C6AA6)
private val DashOnSurface = Color(0xFFE7ECFF)
private val DashOnSurfaceMuted = Color(0xFFB7C2E6)
private val DashGlowColor = Color(0xFF4C6AA6)

// Softer BG graph colors than the theme colors (pure green and yellow in dark mode). Only this screen.
private val DashBgInRangeColor = Color(0xFF4CAF50)
private val DashBgHighColor = Color(0xFFB9AF46)

/** A shortcut button: fixed height and the same rounded shape as the cards. */
@Composable
private fun ActionButton(
    modifier: Modifier = Modifier,
    label: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = DashChipBackground, contentColor = DashOnSurface),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

private fun timeAgoText(timestamp: Long?, dateUtil: DateUtil): String {
    if (timestamp == null) return "-"
    val minutes = ((dateUtil.now() - timestamp) / 60000L).toInt().coerceAtLeast(0)
    return if (minutes < 1) "nu" else "$minutes min geleden"
}

private fun formatAge(minutes: Int?): String {
    if (minutes == null) return "-"
    val hours = abs(minutes) / 60
    return if (hours < 24) "${hours}u" else "${hours / 24}d ${hours % 24}u"
}

/** Arrow for the basal pill: raised or lowered compared to the profile rate. A 2% margin hides rounding. */
private fun basalTrendIcon(actualUh: Double, profileUh: Double): ImageVector? {
    if (profileUh <= 0.0) return null
    val ratio = actualUh / profileUh
    return when {
        ratio > 1.02 -> IcTbrHigh
        ratio < 0.98 -> IcTbrLow
        else         -> null
    }
}

private fun targetValueText(value: Double?): String = if (value == null) "-" else formatDecimals(value, 1)

/** [value] with a fixed number of [decimals] (0 or more), without `String.format`, which common code does not have. */
private fun formatDecimals(value: Double, decimals: Int): String {
    var factor = 1
    repeat(decimals) { factor *= 10 }
    val scaled = (abs(value) * factor).roundToInt()
    val sign = if (value < 0 && scaled != 0) "-" else ""
    if (decimals == 0) return "$sign$scaled"
    val whole = scaled / factor
    val fraction = (scaled % factor).toString().padStart(decimals, '0')
    return "$sign$whole.$fraction"
}

private fun doseSourceTag(source: AlternativeDoseSource?): String = when (source) {
    AlternativeDoseSource.SMB      -> "smb:"
    AlternativeDoseSource.MANUAL   -> "man:"
    AlternativeDoseSource.COMBINED -> "som:"
    null                           -> "?"
}

/** Last dose with the time and a short source tag, in the style of "Target: 5.4" on the BG card. Null if there is no dose yet. */
private fun lastDoseText(units: Double?, timestamp: Long?, source: AlternativeDoseSource?, dateUtil: DateUtil): String? {
    if (units == null || timestamp == null) return null
    return "${doseSourceTag(source)} ${formatDecimals(units, 2)} E · ${dateUtil.timeString(timestamp)}"
}

/** Popup with the last doses: time, dose with source tag, and the BG at that moment ("-" if there was no reading close enough). */
@Composable
private fun DoseHistoryDialog(
    entries: List<AlternativeDoseEntry>,
    loading: Boolean,
    unitLabel: String,
    bgText: (Double) -> String,
    dateUtil: DateUtil,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Laatste doseringen") },
        text = {
            when {
                loading           -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Bezig met laden…")
                    }
                }

                entries.isEmpty() -> Text("Nog geen doseringen gevonden.")
                else              -> {
                    // Fixed column widths keep the table compact, also on a small screen.
                    val timeWidth = 50.dp
                    val tagWidth = 44.dp
                    val valueWidth = 38.dp
                    val bgWidth = 56.dp
                    val gap = 8.dp
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("Tijd", style = MaterialTheme.typography.labelMedium, color = DashOnSurfaceMuted, modifier = Modifier.width(timeWidth))
                            Text(
                                "Dosis",
                                style = MaterialTheme.typography.labelMedium,
                                color = DashOnSurfaceMuted,
                                modifier = Modifier.width(tagWidth + valueWidth + 16.dp)
                            )
                            Spacer(modifier = Modifier.width(gap))
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(bgWidth)) {
                                Text("Bg", style = MaterialTheme.typography.labelMedium, color = DashOnSurfaceMuted)
                                Text(unitLabel, style = MaterialTheme.typography.labelSmall, color = DashOnSurfaceMuted)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                            items(entries) { entry ->
                                Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(dateUtil.timeString(entry.timestampMs), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(timeWidth))
                                    Text(doseSourceTag(entry.source), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(tagWidth))
                                    Text(
                                        formatDecimals(entry.units, 2),
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.width(valueWidth)
                                    )
                                    Text(" E", style = MaterialTheme.typography.bodyMedium)
                                    Spacer(modifier = Modifier.width(gap))
                                    Text(
                                        entry.bgMgdl?.let(bgText) ?: "-",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.width(bgWidth),
                                        textAlign = TextAlign.End,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Sluiten") }
        }
    )
}
