package app.aaps.plugins.aps.openAPSFCL.compose

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.aaps.core.data.configuration.Constants
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.overview.graph.GraphConfig
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.DateUtil
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
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.graphs.INTERACTION_GRACE_MS
import app.aaps.ui.compose.overview.graphs.MIN_GRAPH_ZOOM_MINUTES
import app.aaps.ui.compose.overview.graphs.NiceScale
import app.aaps.ui.compose.overview.graphs.SecondaryGraphCompose
import app.aaps.ui.compose.overview.graphs.timestampToX
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

/**
 * SharedPreferences file for this screen's own settings (toggle + graph heights). Not `private`:
 * [PREFS_NAME] and [PREF_OVERVIEW_SCREEN_ENABLED] are also read from `FCLComposeContent.kt` (the
 * phase-1 tab) and `OpenAPSFCLPlugin.overviewOverride` (the full-home-screen replacement) — one
 * shared name/key pair instead of three separate copies of the same string literals.
 */
const val PREFS_NAME = "fcl_overview_screen_settings"

/** Boolean toggle: "alternatief hoofdscherm" — read by FCLComposeContent.kt and OpenAPSFCLPlugin.kt. */
const val PREF_OVERVIEW_SCREEN_ENABLED = "overview_screen_enabled"
private const val PREF_BG_HEIGHT = "bg_graph_height_dp"
private const val PREF_IOB_HEIGHT = "iob_graph_height_dp"
private const val DEFAULT_BG_HEIGHT_DP = 200

/**
 * 14/09/2026 (de gebruiker) — ondergrens voor de hoogte-stepper van de grafiekkaarten, expres
 * losstaand van [GraphConfig.DEFAULT_GRAPH_HEIGHT_DP] (100, gebruikt door het standaard scherm):
 * hier mag de gebruiker verder omlaag (tot 50dp).
 */
private const val MIN_GRAPH_HEIGHT_DP = 50

/** Fixed x-axis window: 3h before "now", 2h after (room for predictions) — always re-applied on update. */
private const val WINDOW_HOURS_BACK = 3
private const val WINDOW_HOURS_FORWARD = 2
private val WINDOW_TOTAL_MINUTES = (WINDOW_HOURS_BACK + WINDOW_HOURS_FORWARD) * 60.0

/**
 * 14/09/2026 (de gebruiker) — nieuw, optioneel overzichtsscherm met een moderner, ronder
 * uiterlijk, gebaseerd op de echte kleuren/afmetingen uit het open-source AIMI-dashboard
 * (github.com/MTR93600/OpenApsAIMI, dev_OAPSAIMI branch, `colors_dashboard.xml`/
 * `styles_dashboard.xml`/`dimens_dashboard.xml`): een donkere kaart-achtergrond (#1E2133) met
 * een dunne rand (#334067) en een echte schaduw/gloed, en daarbinnen duidelijk LICHTERE pil-vakjes
 * (#2F3C5C met #4C6AA6-rand) voor ieder los gegeven. Dat kleurcontrast — niet een neumorphic
 * schaduweffect — is wat AIMI's "verhoogde" uiterlijk geeft; zie kdoc bij de kleurconstanten
 * onderaan dit bestand.
 *
 * Fase 1: alleen bereikbaar via een eigen tabblad binnen FCLvNext's eigen instellingenscherm,
 * achter een toggle (zie FCLSettingsScreen.kt). Raakt het standaard AAPS-hoofdscherm op geen
 * enkele manier aan — puur nieuwe code, alleen afhankelijk van bestaande, live databronnen
 * (OverviewDataCache) en de bestaande grafiekcomponenten uit de ui-module (die predictie/zoom/
 * swipe al ingebouwd hebben).
 *
 * BG-grafiek en IOB/basaal-grafiek staan als TWEE losse kaarten (op verzoek van de gebruiker),
 * maar delen dezelfde [VicoScrollState]/[VicoZoomState]-instanties, dus scrollen/zoomen op de ene
 * beweegt de andere automatisch mee (ze horen qua tijd-as bij elkaar). Het zichtbare venster
 * springt bij iedere nieuwe BG-waarde automatisch terug naar "nu -3u / nu +2u" (tenzij de
 * gebruiker net aan het scrollen/zoomen is — zelfde `INTERACTION_GRACE_MS`-patroon als
 * `GraphsSection.kt`). De BG-grafiek gebruikt een eigen vaste Y-as-regel (2-12, ophogen bij
 * pieken) via de nieuwe `yRangeOverride`-parameter op `BgGraphCompose` (zie kdoc daar) — een
 * kleine, terugwaarts-compatibele toevoeging aan het gedeelde bestand, met `null` als standaard
 * zodat het echte AAPS-hoofdscherm ongewijzigd blijft.
 *
 * Fase 2 (nog niet gebouwd, zie overleg met de gebruiker over crash-fallback) zou dit scherm
 * pas echt als vervanging van het standaard hoofdscherm aanbieden.
 */
@OptIn(FlowPreview::class)
@Composable
fun FclOverviewScreen(
    overviewDataCache: OverviewDataCache,
    graphViewModelFactory: GraphViewModel.Factory,
    persistenceLayer: PersistenceLayer,
    activePlugin: ActivePlugin,
    profileFunction: ProfileFunction,
    iobCobCalculator: IobCobCalculator,
    dateUtil: DateUtil,
    cycleLogRepository: app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLCycleLogRepository,
    onOpenFclSettings: () -> Unit
) {
    val graphViewModel: GraphViewModel = viewModel(
        factory = remember {
            viewModelFactory { initializer { graphViewModelFactory.create(overviewDataCache, fullWindow = false) } }
        }
    )
    val deviceViewModel: FclOverviewViewModel = viewModel(
        factory = remember {
            viewModelFactory {
                initializer {
                    FclOverviewViewModel(persistenceLayer, activePlugin, profileFunction, iobCobCalculator, dateUtil, cycleLogRepository)
                }
            }
        }
    )
    val navigationRequest = LocalPluginNavigationRequest.current
    val ctx = LocalContext.current

    // 15/09/2026 (de gebruiker) — "Manage"-knop hieronder: hergebruikt de bestaande, al geteste
    // ManageSheetHost/ManageBottomSheet (TBR annuleren, extended bolus annuleren, batterij/fill,
    // pomp, geautoriseerde clients, ...) uit de ui-module. ManageViewModel is Metro-injectable
    // (@ContributesIntoMap/@ViewModelKey), dus via metroViewModel() net als elk ander scherm —
    // geen handmatige constructor-DI nodig. Fouten (bv. een mislukte TBR-annulering) gaan naar
    // een Toast — dit scherm heeft geen eigen SnackbarHost, en dit is een zeldzaam, niet-kritiek
    // pad (zie CLAUDE.md's snackbar-regels: Toast is de toegestane lichte fallback).
    val manageViewModel: ManageViewModel = metroViewModel()
    val manageSheetState = ManageSheetHost(
        manageViewModel = manageViewModel,
        isSimpleMode = false,
        onNavigate = navigationRequest,
        onActionsError = { comment, title -> Toast.makeText(ctx, "$title: $comment", Toast.LENGTH_LONG).show() }
    )

    // 17/09/2026 (de gebruiker) — de "Override"-knop hieronder moet ALLEEN de
    // tijdelijke-aanpassing-kaart tonen, direct uitgeklapt, in een eigen bottom sheet — geen
    // navigatie naar het volledige FCLvNext-instellingenscherm meer. Zo blijft de functie van de
    // knop duidelijk (en niet, per ongeluk, een snelkoppeling naar andere FCLvNext-instellingen),
    // ook wanneer hier later meer override-functies/preset-knoppen bijkomen.
    var showOverrideSheet by remember { mutableStateOf(false) }

    val bgInfo by overviewDataCache.bgInfoFlow.collectAsState()
    val profile by overviewDataCache.profileFlow.collectAsState()
    val targetLine by overviewDataCache.targetLineFlow.collectAsState()
    val deviceState by deviceViewModel.deviceState.collectAsState()

    // Eigen SharedPreferences-bestand voor grafiekhoogtes (zelfde bestand als de preview-toggle
    // in FCLComposeContent.kt, andere keys) — blijft bewaard tussen sessies, geen nieuwe
    // afhankelijkheid nodig.
    val prefs = remember { ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var bgGraphHeight by remember { mutableIntStateOf(prefs.getInt(PREF_BG_HEIGHT, DEFAULT_BG_HEIGHT_DP)) }
    var iobGraphHeight by remember { mutableIntStateOf(prefs.getInt(PREF_IOB_HEIGHT, GraphConfig.DEFAULT_GRAPH_HEIGHT_DP)) }

    // ── Gedeelde scroll/zoom-status voor BEIDE grafieken (zie kdoc hierboven: los qua kaart,
    // gekoppeld qua tijd-as). Zoom.x(...) maakt elke keer een nieuwe instantie — daarom hier
    // gememoriseerd zodat de identiteit stabiel blijft (anders wordt VicoZoomState bij elke
    // recompositie teruggezet, zie kdoc in GraphsSection.kt).
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

    // 21/09/2026 (de gebruiker) — de Y-as-schaal van de BG-grafiek (fclBgYRange hieronder) bleek
    // niet meer mee te schalen zodra de gebruiker terugscrolde naar oudere data: hij was gekoppeld
    // aan een VAST "nu ±venster" (visibleWindowFallback hieronder), dus zodra het echte, geschoven
    // kijkvenster daar niet meer mee overeenkwam, viel de dataMax-berekening terug op grotendeels
    // buiten-venster-gefilterde (lege) data en dus op chartConfig.highMark als fallback — vandaar
    // de vaste 12-mmol-plafond ongeacht de zichtbare piek. Zelfde patroon als GraphsSection.kt
    // (bgVisibleTimeRange/iobVisibleRange): het ECHTE zichtbare x-bereik van de IOB-grafiek
    // (onVisibleRangeChanged hieronder) volgen, gedebounced tegen chart-rebuild-storms tijdens
    // slepen, en pas omzetten naar absolute timestamps zodra minTs bekend is.
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

    // 17/09/2026 (de gebruiker) — deviceViewModel.refresh() liep hiervoor alleen bij het EERSTE
    // opbouwen van dit scherm (init{} in FclOverviewViewModel), dus IOB/basaal ververste nooit meer
    // zolang dit scherm het echte hoofdscherm is en dus nooit meer weg-en-opnieuw-opgebouwd wordt
    // (in de oude tabblad-situatie leek dit toevallig wel te werken, omdat elke tabwissel het
    // scherm — en daarmee de ViewModel — opnieuw opbouwde). now verandert elke 30s
    // (graphViewModel.nowTimestamp), zelfde herhaal-ritme als het standaard IOB/TBR-chipje.
    LaunchedEffect(now) { deviceViewModel.refresh() }

    // Na een reset (nieuwe scroll/zoom-instanties) opnieuw verankeren zodat "nu" op
    // WINDOW_HOURS_FORWARD uur van de rechterrand staat (ruimte voor voorspellingen) — zelfde
    // positioneringstruc als GraphsSection.kt. Alleen bij trigger > 0 (niet bij de eerste
    // compositie) en NIET herhaald bij elke derivedTimeRange-wijziging — anders zou dit continu
    // een handmatige scroll van de gebruiker overschrijven i.p.v. alleen bij een echte reset.
    LaunchedEffect(viewportResetTrigger, sharedScrollState) {
        if (viewportResetTrigger == 0) return@LaunchedEffect
        val (minTs, _) = derivedTimeRange ?: return@LaunchedEffect
        val nowX = timestampToX(now, minTs)
        sharedScrollState.animateScroll(Scroll.Absolute.x(nowX + WINDOW_HOURS_FORWARD * 60.0, bias = 1f))
    }

    // Springt terug naar "nu -3u / nu +2u" bij elke nieuwe BG-waarde, tenzij de gebruiker net aan
    // het scrollen/zoomen is (INTERACTION_GRACE_MS-genadeperiode, zelfde patroon als GraphsSection.kt).
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

    // Registreert handmatige scroll/zoom-interactie bij de ViewModel, zodat de reset hierboven
    // een actieve gebaar-interactie niet onderbreekt.
    LaunchedEffect(sharedScrollState, sharedZoomState) {
        var initialValue = true
        snapshotFlow { sharedScrollState.value to sharedZoomState.value }
            .debounce(30)
            .collect {
                if (initialValue) initialValue = false else graphViewModel.onGraphInteraction()
            }
    }

    // Zichtbaar venster voor de Y-as-berekening van de BG-grafiek (zie fclBgYRange hieronder).
    // 21/09/2026 (de gebruiker) — was een VAST "nu ±venster" (bgVisibleTimeRange hierboven was er
    // nog niet), wat de schaal liet vastlopen op de standaard 12-mmol-plafond zodra de gebruiker
    // terugscrolde naar data buiten dat venster. Nu bgVisibleTimeRange (het ECHTE, live bijgehouden
    // zichtbare bereik) als eerste keus, met het oude vaste venster alleen nog als terugval vóór
    // het allereerste scroll/zoom-rebuild-event (nog geen SecondaryGraphCompose-callback gehad).
    val visibleWindowFallback = remember(now) {
        (now - WINDOW_HOURS_BACK * 3600_000L) to (now + WINDOW_HOURS_FORWARD * 3600_000L)
    }
    val visibleWindow = bgVisibleTimeRange ?: visibleWindowFallback

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            // 22/09/2026 (de gebruiker) — extra ruimte boven de bovenste kaart: MainScreen.kt
            // tekent de versietekst (VersionOverlay.kt, bijv. "4.0C-v9-v127 (56ee)") ZWEVEND
            // rechtsboven over de hele scherminhoud heen (Modifier.align(Alignment.TopEnd) op
            // Box-niveau, dus buiten deze Column om) — zonder extra marge hier liep die tekst
            // over de rand van de bovenste DashCard. Bewust hier opgelost (alleen dit scherm) en
            // NIET in VersionOverlay.kt/MainScreen.kt zelf, want dat is gedeelde UI voor de hele
            // app en zou elk ander scherm ook raken.
            .padding(top = 28.dp, start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        // ── Hoofdkaart: klein en simpel — dezelfde BG-cirkel met trend-boog als het standaard
        // hoofdscherm (BgInfoSection, hergebruikt uit de ui-module i.p.v. zelf nagebouwd), met
        // alleen IOB en basaal ernaast (die horen qua actuele status bij de Bg). Sensor/pomp/
        // profiel staan bewust NIET meer hier — zie de aparte, kleinere kaart eronder. Target
        // staat op de BG-grafiekkaart hieronder (zie GraphCard's trailingText).
        DashCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                BgInfoSection(
                    bgInfo = bgInfo,
                    timeAgoText = timeAgoText(bgInfo?.timestamp, dateUtil),
                    // 15/09/2026 (de gebruiker) — kleiner dan de standaard 17sp: op dit compacte
                    // scherm past de tekst anders net niet mooi binnen de cirkel.
                    timeAgoStyle = MaterialTheme.typography.labelSmall
                )
                // IOB/Basaal rechts uitgelijnd tegen de kaartrand (buitenste Column vult de
                // resterende rij-breedte, End-uitgelijnd), en onderling even breed (binnenste
                // Column krijgt de breedte van zijn breedste kind via IntrinsicSize.Max, beide
                // pillen vullen die breedte i.p.v. allebei hun eigen, mogelijk verschillende,
                // natuurlijke breedte te houden).
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                ) {
                    Column(
                        modifier = Modifier.width(IntrinsicSize.Max),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PillChip(
                            modifier = Modifier.fillMaxWidth(),
                            label = "IOB",
                            value = "${roundTo(deviceState.iobTotal, 2)} E",
                            centered = true,
                            fillWidth = true
                        )
                        PillChip(
                            modifier = Modifier.fillMaxWidth(),
                            label = "Basaal",
                            value = "${roundTo(deviceState.basalRateUh, 2)} E/u",
                            centered = true,
                            fillWidth = true,
                            trailingIcon = basalTrendIcon(deviceState.basalRateUh, deviceState.profileBasalRateUh)
                        )
                    }
                }
            }
        }

        // ── Aparte, kleinere kaart: sensor, pomp (canule-leeftijd + reservoir samen, net als
        // het standaard scherm doet) en profiel — horen niet bij de actuele Bg, staan er los
        // onder. Alle drie klikbaar: sensor opent de xDrip Bg-tabel, pomp opent het pompscherm,
        // profiel opent de profiel-bewerkpagina (zelfde elementen als de standaard "Manage"-
        // sheet / zoekfunctie gebruikt).
        DashCard {
            // 17/09/2026 (de gebruiker) — alle drie even breed (elk 1/3 van de rij via
            // Modifier.weight(1f), fillWidth=true zodat de Card die breedte ook echt gebruikt
            // i.p.v. om zijn eigen inhoud te passen) en gecentreerd, net als de IOB/Basaal-pillen.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip(
                    modifier = Modifier.weight(1f),
                    label = "Sensor",
                    value = formatAge(deviceState.sensorAgeMinutes),
                    centered = true,
                    fillWidth = true,
                    // 17/09/2026 (de gebruiker) — opent de eigen "Bg-tabel" van de actieve BG-bron
                    // (ontvangen Bg-waarden), niet het externe xDrip-apparaat-scherm (dat is wat
                    // ElementType.CGM_XDRIP doet — bewust een ander ElementType hier).
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

        // ── BG-grafiek: eigen kaart, gedeelde scroll/zoom-status (zie hierboven). Voorspellingen
        // zitten al in de hergebruikte BgGraphCompose via SeriesType.PREDICTIONS. Vaste Y-as-regel
        // via yRangeOverride (zie fclBgYRange onderaan dit bestand).
        GraphCard(
            title = "BG",
            height = bgGraphHeight,
            onHeightChange = { h -> bgGraphHeight = h; prefs.edit().putInt(PREF_BG_HEIGHT, h).apply() },
            trailingText = "Target: " + targetValueText(targetLine.targets.lastOrNull()?.value)
        ) { graphModifier ->
            BgGraphCompose(
                viewModel = graphViewModel,
                bgOverlays = listOf(SeriesType.PREDICTIONS, SeriesType.ACTIVITY),
                scrollState = sharedScrollState,
                zoomState = sharedZoomState,
                derivedTimeRange = derivedTimeRange,
                nowTimestamp = now,
                visibleTimeRange = visibleWindow,
                yRangeOverride = ::fclBgYRange,
                inRangeColorOverride = DashBgInRangeColor,
                highColorOverride = DashBgHighColor,
                modifier = graphModifier
            )
        }

        // ── IOB/basaal-grafiek: eigen kaart, maar dezelfde scroll/zoom-status als de BG-grafiek
        // hierboven — zoomen/schuiven op de ene beweegt de andere automatisch mee.
        GraphCard(
            title = "IOB / basaal",
            height = iobGraphHeight,
            onHeightChange = { h -> iobGraphHeight = h; prefs.edit().putInt(PREF_IOB_HEIGHT, h).apply() },
            // 21/09/2026 (de gebruiker) — laatste dosis (ongeacht bron) + tijdstip + compacte
            // bron-tag, zelfde stijl als "Target: 5.4" op de BG-kaart (zie GraphCard's
            // trailingText: bodyMedium/DashOnSurfaceMuted).
            trailingText = lastDoseText(deviceState.lastDoseUnits, deviceState.lastDoseTimestamp, deviceState.lastDoseSource, dateUtil)
        ) { graphModifier ->
            SecondaryGraphCompose(
                viewModel = graphViewModel,
                seriesTypes = listOf(SeriesType.IOB),
                scrollState = sharedScrollState,
                zoomState = sharedZoomState,
                derivedTimeRange = derivedTimeRange,
                nowTimestamp = now,
                // 21/09/2026 (de gebruiker) — voedt bgVisibleTimeRange hierboven, zie kdoc daar.
                onVisibleRangeChanged = { iobVisibleRange = it },
                modifier = graphModifier
            )
        }

        // ── Snelkoppelingen — vier knoppen, allemaal dezelfde stijl/hoogte/breedte (zie
        // ActionButton onderaan). Insuline opent het eenvoudige insuline-invoerscherm
        // (ElementType.INSULIN), niet de volledige bolus-wizard. Manage opent de standaard
        // "Manage"-sheet (zie manageSheetState hierboven).
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                modifier = Modifier.weight(1f),
                label = "Insuline",
                onClick = { navigationRequest(NavigationRequest.Element(ElementType.INSULIN)) }
            )
            ActionButton(
                modifier = Modifier.weight(1f),
                label = "Override",
                onClick = { showOverrideSheet = true }
            )
            ActionButton(
                modifier = Modifier.weight(1f),
                label = "FCLvNext",
                onClick = onOpenFclSettings
            )
            ActionButton(
                modifier = Modifier.weight(1f),
                label = "Manage",
                onClick = { manageSheetState.show() }
            )
        }
    }
    if (showOverrideSheet) {
        OverrideBottomSheet(onDismiss = { showOverrideSheet = false })
    }
}

/**
 * Eigen, kleine bottom sheet die ALLEEN [TempOverrideCard] toont, direct uitgeklapt — zie kdoc
 * bij `showOverrideSheet` hierboven voor de reden (17/09/2026, de gebruiker): de "Override"-knop
 * moet een op zichzelf staande functie blijven, geen snelkoppeling naar het volledige
 * FCLvNext-instellingenscherm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverrideBottomSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            TempOverrideCard(startExpanded = true)
        }
    }
}

/**
 * Kleine kaart-wrapper voor een grafiek: label, potlood-knopje rechtsboven (net als AAPS'
 * `GraphEditButton` in GraphsSection.kt) dat een bottom sheet opent met een hoogte-stepper
 * ([MIN_GRAPH_HEIGHT_DP]..[GraphConfig.MAX_GRAPH_HEIGHT_DP] — de ondergrens ligt hier lager dan
 * op het standaard scherm, op uitdrukkelijk verzoek van de gebruiker).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GraphCard(
    title: String,
    height: Int,
    onHeightChange: (Int) -> Unit,
    // 14/09/2026 (de gebruiker) — optionele tekst op DEZELFDE regel als de titel, rechts
    // uitgelijnd (gebruikt door de BG-kaart voor "Target: 5.4"). Beide groter dan het oude
    // kleine grafieklabel, zodat ze goed leesbaar zijn.
    trailingText: String? = null,
    graph: @Composable (Modifier) -> Unit
) {
    var editingHeight by remember { mutableStateOf(false) }
    DashCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = DashOnSurface)
            // 21/09/2026 (de gebruiker) — kleiner dan de titel en veel minder fel (DashOnSurfaceMuted
            // i.p.v. DashOnSurface): stond eerst even fel/groot als "BG"/"IOB / basaal" zelf, wat te
            // dominant oogde voor bijkomende info als "Target: 5.4" of de laatste bolus.
            if (trailingText != null) {
                Text(text = trailingText, style = MaterialTheme.typography.bodyMedium, color = DashOnSurfaceMuted)
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
        GraphHeightBottomSheet(
            height = height,
            onHeightChange = onHeightChange,
            onDismiss = { editingHeight = false }
        )
    }
}

/** Bottom sheet met alleen een hoogte-stepper (geen serie-keuze nodig — deze grafieken zijn vast). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GraphHeightBottomSheet(height: Int, onHeightChange: (Int) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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

/**
 * 14/09/2026 (de gebruiker) — vaste Y-as-regel voor de BG-grafiek op dit scherm: standaard 2-12
 * (mmol/l-as, of de equivalente as-eenheid van de grafiek), en als de zichtbare data boven de 10
 * uitkomt, het maximum uitbreiden naar (die waarde + 2), naar boven afgerond. Ontvangt de RUWE
 * zichtbare min/max (zie `yRangeOverride`-kdoc in BgGraphCompose.kt) — dus al beperkt tot het
 * huidige zichtbare tijdvenster (`visibleWindow` hierboven), niet geclampt tegen de
 * lowMark/highMark-instellingen.
 */
private fun fclBgYRange(dataMin: Double, dataMax: Double): NiceScale {
    val minY = 2.0
    val maxY = if (dataMax > 10.0) ceil(dataMax + 2.0) else 12.0
    return NiceScale(minY, maxY, (maxY - minY) / 4.0)
}

/**
 * De grote, "zwevende" hoofdkaart — echte schaduw/gloed (net als AIMI's `dashboard_card_elevation`)
 * i.p.v. Material3's automatische, in dit donkere thema nauwelijks zichtbare tonale tint. Hogere
 * elevation (10dp, was 6dp) + een gekleurde ambient/spot-schaduw (DashGlowColor, afgeleid van de
 * kaartrand) geven een echte "gloed" i.p.v. een neutrale grijze schaduw. Zie kdoc bij de
 * kleurconstanten onderaan dit bestand.
 */
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
 * Klein pil-vakje. AIMI geeft dit geen eigen schaduw — het "verhoogde" gevoel komt uitsluitend
 * van een merkbaar LICHTERE vulkleur (DashChipBackground) tegen de donkerdere kaart eromheen
 * (DashCardBackground), plus een dunne rand.
 *
 * @param fillWidth 17/09/2026 (de gebruiker) — als true krijgt de Card gewoon `modifier` zelf
 * (bv. `Modifier.weight(1f)` in een Row voor gelijke breedtes zoals Sensor/Pomp/Profiel, of
 * `Modifier.fillMaxWidth()` in een `IntrinsicSize.Max`-kolom zoals IOB/Basaal). Standaard false:
 * dan past de Card om zijn eigen inhoud (`wrapContentWidth`), zoals bij losstaande pillen zonder
 * breedte-eis.
 * @param trailingIcon 17/09/2026 (de gebruiker) — optioneel pijl-icoon rechts van label+waarde,
 * over de volle hoogte van de pil (gebruikt door de Basaal-pil: alleen tonen bij een hogere/lagere
 * basaal dan het profiel, geen icoon bij een vlakke basaal — zie `basalTrendIcon`).
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
    // 19/09/2026 (de gebruiker) — vaste 13sp voor alle pil-teksten (label én waarde, op elke
    // pil hier: IOB/Basaal en Sensor/Pomp/Profiel), plus maxLines=1 + ellipsis. Zonder dit
    // wrapte een lange profielnaam (bv. "FCL u200 lyumjev openapsSMB") naar meerdere regels,
    // waardoor die kaart véél hoger werd dan de andere twee ernaast in de Row (geen gedeelde
    // hoogte-afdwinging tussen de drie weight(1f)-kaarten) — en bij 2-cijferige uren ("1d 21u")
    // paste de waarde soms net niet meer op de oude, grotere bodyMedium-breedte. maxLines=1
    // garandeert nu dat alle pillen in een rij altijd exact even hoog blijven, ongeacht de
    // inhoud; ellipsis is de vangnet-afkapping voor het (zeldzame) geval dat het echt niet past.
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
                    modifier = Modifier.padding(start = 8.dp).size(20.dp)
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

// ── Vaste kleuren, rechtstreeks overgenomen van AIMI's eigen dashboard-resources (14/09/2026,
// de gebruiker; bron: github.com/MTR93600/OpenApsAIMI, dev_OAPSAIMI,
// plugins/main/src/main/res/values/{colors_dashboard.xml,styles_dashboard.xml}) ──
// AIMI gebruikt zelf OOK vaste, niet-thema-afgeleide kleuren voor dit scherm (niet
// MaterialTheme-rollen) — dat is dus geen verkeerde afwijking van onze kant, maar precies hoe
// het origineel het doet. De "kaart" (dashboard_card_background, 1E2133) is donkerder dan de
// "pil" erin (dashboard_chip_background, 2F3C5C) — dat kleurverschil, plus een echte schaduw op
// de buitenste kaart (dashboard_card_elevation), is wat het "verhoogde" uiterlijk geeft. Geen
// neumorphic dubbele schaduw. DashGlowColor (afgeleid van de kaartrand, #4C6AA6) geeft de
// schaduw een lichte blauwe tint i.p.v. een neutrale grijze schaduw — dat is de "gloed".
private val DashCardBackground = Color(0xFF1E2133)
private val DashCardStroke = Color(0xFF334067)
private val DashChipBackground = Color(0xFF2F3C5C)
private val DashChipBorder = Color(0xFF4C6AA6)
private val DashOnSurface = Color(0xFFE7ECFF)
private val DashOnSurfaceMuted = Color(0xFFB7C2E6)
private val DashGlowColor = Color(0xFF4C6AA6)

// 21/09/2026 (de gebruiker) — minder fluorescerende BG-grafiekkleuren voor dit scherm dan de
// pure theme-kleuren (bgInRange 0x00FF00, bgHigh 0xFFFF00 in donkere modus) — "knalt minder van
// het scherm". Alleen dit scherm; het standaard AAPS-hoofdscherm (GraphsSection.kt) gebruikt nog
// gewoon AapsTheme.generalColors via BgGraphCompose's inRangeColorOverride/highColorOverride =
// null default (zie BgGraphCompose.kt).
private val DashBgInRangeColor = Color(0xFF4CAF50)
// 21/09/2026 (de gebruiker, 2e ronde) — eerst een amber/oranje (0xFFFFB300, HSL ~42°/100%/50%)
// geprobeerd, maar dat oogde te oranje i.p.v. geel. Nu een echte geel-tint (HSL ~55°) met
// vergelijkbare verzadiging/helderheid als DashBgInRangeColor hierboven (HSL ~122°/39%/49%), dus
// consistent "gedempt" met het groen, niet feller.
private val DashBgHighColor = Color(0xFFB9AF46)

/**
 * 15/09/2026 (de gebruiker) — de vier onderste snelkoppelingen: vaste hoogte (max. 2 tekstregels,
 * zodat langere labels niet afwijken van de andere drie) en dezelfde afgeronde pil-vorm
 * als de kaarten/pillen hierboven (i.p.v. Material3's standaard knopvorm) voor een iets
 * moderner, met de rest van dit scherm consistent uiterlijk.
 */
@Composable
private fun ActionButton(modifier: Modifier = Modifier, label: String, onClick: () -> Unit) {
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
            maxLines = 2
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
    val absMin = abs(minutes)
    val hours = absMin / 60
    return when {
        hours < 24 -> "${hours}u"
        else       -> "${hours / 24}d ${hours % 24}u"
    }
}

/**
 * 15/09/2026 (de gebruiker) — vergelijkt de actuele basaalsnelheid met de PROFIEL-snelheid
 * (`FclDeviceState.profileBasalRateUh`, `BasalData.basal` — de ongewijzigde geplande snelheid,
 * niet de tijdelijke) om een verhoogd/verlaagd-pijl-icoon te tonen bij de Basaal-pil. Een kleine
 * marge (2%) voorkomt dat afrondingsverschillen als "verhoogd"/"verlaagd" worden getoond wanneer
 * de snelheid in werkelijkheid gelijk is aan het profiel.
 *
 * 17/09/2026 (de gebruiker) — geeft nu een [ImageVector] terug (dezelfde `IcTbrHigh`/`IcTbrLow`-
 * iconen als het standaard scherm z'n TBR-chipje, zie `TbrChip.kt`) i.p.v. een tekst-pijltje, en
 * `null` bij een vlakke basaal (geen icoon tonen) i.p.v. een rechte-pijl-symbool.
 */
private fun basalTrendIcon(actualUh: Double, profileUh: Double): ImageVector? {
    if (profileUh <= 0.0) return null
    val ratio = actualUh / profileUh
    return when {
        ratio > 1.02 -> IcTbrHigh
        ratio < 0.98 -> IcTbrLow
        else         -> null
    }
}

private fun targetValueText(value: Double?): String {
    if (value == null) return "-"
    return roundTo(value, 1).toString()
}

/**
 * 21/09/2026 (de gebruiker) — laatste dosis + tijdstip + compacte bron-tag als trailingText
 * naast "IOB / basaal", in dezelfde stijl als "Target: 5.4" op de BG-kaart (zie GraphCard's
 * trailingText-parameter). Dit is bewust de MEEST RECENTE van drie mogelijke bronnen (zie kdoc
 * bij `FclOverviewViewModel.refresh()` en [LastDoseSource]) — niet altijd FCLvNext: een latere
 * handmatige correctie moet ook zichtbaar zijn, anders lijkt het scherm een oude/kleine dosis te
 * tonen terwijl de gebruiker zelf net iets gegeven heeft. `null` (dus geen tekst) als er nog
 * nooit een dosis is geregistreerd, i.p.v. een "-" placeholder die verwarrend zou zijn naast een
 * lege kaart.
 */
private fun lastDoseText(units: Double?, timestamp: Long?, source: LastDoseSource?, dateUtil: DateUtil): String? {
    if (units == null || timestamp == null) return null
    val unitsText = roundTo(units, 2).toString()
    val sourceTag = when (source) {
        LastDoseSource.FCLVNEXT -> "fcl:"
        LastDoseSource.AAPS     -> "aaps:"
        LastDoseSource.MANUAL   -> "manueel:"
        LastDoseSource.COMBINED -> "som:"
        null                    -> "?"
    }
    return "$sourceTag $unitsText E · ${dateUtil.timeString(timestamp)}"
}

private fun roundTo(value: Double, decimals: Int): Double {
    var factor = 1.0
    repeat(decimals) { factor *= 10.0 }
    return (value * factor).roundToInt() / factor
}
