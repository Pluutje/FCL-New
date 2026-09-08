package app.aaps.plugins.aps.openAPSFCL.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.pickers.TimeWheelPicker
import app.aaps.core.ui.compose.pickers.WeekDaySelector
import app.aaps.plugins.aps.openAPSFCL.vnext.FCL_STATUS_VERSION
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner
import app.aaps.plugins.aps.openAPSFCL.vnext.lang.FclStrings
import app.aaps.plugins.aps.openAPSFCL.update.FclCsvUploader
import app.aaps.plugins.aps.openAPSFCL.update.FclUpdateChecker
import app.aaps.plugins.aps.openAPSFCL.update.FclUpdateInstaller
import app.aaps.plugins.aps.openAPSFCL.update.FclUpdateNotificationHelper
import app.aaps.plugins.aps.openAPSFCL.update.FclUpdatePrefs
import app.aaps.plugins.aps.openAPSFCL.update.FclUpdateScheduler
import app.aaps.plugins.aps.openAPSFCL.update.FclWhatsNewChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FCLSettingsScreen(
    preferences: Preferences,
    sp: SP,
    // 08/09/2026 (de gebruiker) — true als dit scherm net geopend is via een
    // tik op de update-melding (zie FclUpdateNotificationHelper.kt); klapt de
    // Updates-sectie hieronder direct open i.p.v. dat de gebruiker 'm zelf
    // moet vinden en aantikken.
    startExpandedOnUpdates: Boolean = false
) {

    // Link naar het externe FCLvNext-handboek (Google Doc) — los bestand,
    // zodat het bijwerken van de uitleg geen nieuwe app-build vereist.
    //  val FCL_DOCS_URL = "https://docs.google.com/document/d/14iTVM0uW8aYZgQYYRJaJ9O7XyiN9ZbAy/edit"
    val FCL_DOCS_URL = "https://docs.google.com/document/d/1bexT8FFEA0SGP7-P13pL25iXvgkfH_yA/edit"

    fun String.toHour(): Int = split(":").getOrNull(0)?.toIntOrNull() ?: 7
    fun String.toMinute(): Int = split(":").getOrNull(1)?.toIntOrNull() ?: 0

    fun weekendDagenToBoolArray(csv: String): BooleanArray {
        val selected = csv.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        return BooleanArray(7) { i -> (i + 1) in selected }
    }
    fun boolArrayToWeekendDagen(arr: BooleanArray): String =
        arr.indices.filter { arr[it] }.map { it + 1 }.joinToString(",")

    var maxBolusDay       by remember { mutableStateOf(preferences.get(DoubleKey.max_bolus_day)) }
    var maxBolusNight     by remember { mutableStateOf(preferences.get(DoubleKey.max_bolus_night)) }
    var maxIob            by remember { mutableStateOf(preferences.get(DoubleKey.fcl_vnext_MaxIOB)) }
    var doseStyle         by remember { mutableStateOf(preferences.get(StringKey.fcl_vnext_dose_distribution_style)) }
    // nightStyle / resBehavior / resStability verwijderd (18/06/2026)
    var actBehavior       by remember { mutableStateOf(preferences.get(StringKey.fcl_vnext_activity_behavior)) }
    var weekendDagen      by remember { mutableStateOf(preferences.get(StringKey.WeekendDagen)) }
    var ochtendStart      by remember { mutableStateOf(preferences.get(StringKey.OchtendStart)) }
    var ochtendWeekend    by remember { mutableStateOf(preferences.get(StringKey.OchtendStartWeekend)) }
    var nachtStart        by remember { mutableStateOf(preferences.get(StringKey.NachtStart)) }

    var expandedDosering  by remember { mutableStateOf(true) }
    var expandedAnalyserAutomaat by remember { mutableStateOf(false) }
    var expandedContext   by remember { mutableStateOf(false) }
    var expandedAutosens  by remember { mutableStateOf(false) }

    var showExpertSection   by remember { mutableStateOf(false) }
    var expertPinInput      by remember { mutableStateOf("") }
    var expertPinError      by remember { mutableStateOf(false) }
    var expertPinDialogOpen by remember { mutableStateOf(false) }
    val EXPERT_PIN = "0000"
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Geleidelijke nacht-overgang (17/07/2026) — zie FclNachtOvergangSettings.kt.
    // BUGFIX (17/07/2026): moet NA de ctx-declaratie hierboven staan — ctx
    // bestond nog niet toen deze regel eerder (per abuis) bij nachtStart stond.
    var nachtOvergangMinuten by remember {
        mutableStateOf(app.aaps.plugins.aps.openAPSFCL.vnext.FclNachtOvergangSettings.get(ctx))
    }
    var selectedLocale by remember {
        mutableStateOf(app.aaps.plugins.aps.openAPSFCL.vnext.lang.FclStrings.loadLocale(ctx))
    }
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)

    var showOchtendPicker        by remember { mutableStateOf(false) }
    var showOchtendWeekendPicker by remember { mutableStateOf(false) }
    var showNachtPicker          by remember { mutableStateOf(false) }

    // ── AIGF-state (14/07/2026) ───────────────────────────────────────
    // BUGFIX (14/07/2026): stond eerst genest in de FCLSection("Activiteit")
    // lambda hieronder — daardoor "Unresolved reference" op de picker-dialogen
    // verderop in dit bestand, die buiten die lambda staan (zelfde patroon als
    // showOchtendPicker/showNachtPicker hierboven: top-niveau state, overal in
    // deze composable bruikbaar). Waarde blijft intern Double (voor precisie/
    // toekomstbestendigheid), maar de UI toont/scrollt alleen hele procenten
    // (geen decimalen) via PercentWheelPicker. Min% begrensd 75-100, Max% 100-125
    // (symmetrisch rond het neutrale punt 100, zie FclActivitySensitivity.kt).
    var aigfActive by remember {
        mutableStateOf(
            ctx.getSharedPreferences("fcl_activity_sensitivity_settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("aigf_active", false)
        )
    }
    var aigfMinPct by remember {
        mutableStateOf(
            ctx.getSharedPreferences("fcl_activity_sensitivity_settings", android.content.Context.MODE_PRIVATE)
                .getFloat("aigf_min_pct", 95.0f).toDouble()
        )
    }
    var aigfMaxPct by remember {
        mutableStateOf(
            ctx.getSharedPreferences("fcl_activity_sensitivity_settings", android.content.Context.MODE_PRIVATE)
                .getFloat("aigf_max_pct", 105.0f).toDouble()
        )
    }
    var showAigfMinPicker by remember { mutableStateOf(false) }
    var showAigfMaxPicker by remember { mutableStateOf(false) }
    var showNachtOvergangPicker by remember { mutableStateOf(false) }

    var infoDialogTitle   by remember { mutableStateOf("") }
    var infoDialogText    by remember { mutableStateOf("") }
    var showInfoDialog    by remember { mutableStateOf(false) }

    fun showInfo(title: String, text: String) {
        infoDialogTitle = title
        infoDialogText  = text
        showInfoDialog  = true
    }

    val doseOptions = listOf(
        // 22/07/2026 — nieuwe trap boven VERY_SMOOTH, zie FCLvNextConfig.kt
        "SUPER_SMOOTH" to s.doseStyleLabel("SUPER_SMOOTH"),
        "VERY_SMOOTH" to s.doseStyleLabel("VERY_SMOOTH"),
        "SMOOTH"      to s.doseStyleLabel("SMOOTH"),
        "BALANCED"    to s.doseStyleLabel("BALANCED"),
        "PULSED"      to s.doseStyleLabel("PULSED"),
        "VERY_PULSED" to s.doseStyleLabel("VERY_PULSED")
    )
    // nightOptions/resBehaviorOptions/resStabilityOptions verwijderd (18/06/2026)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        // ── Documentatie: link naar het volledige FCLvNext-handboek ───────
        // Externe (Google Doc) link i.p.v. ingebouwde tekst — zelfde opzet
        // als het oude systeem: één centraal, makkelijk bij te werken
        // document i.p.v. uitleg die in de app zelf verspreid staat.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("📘", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Hoe werkt FCLvNext?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
                Text(
                    "Uitleg van het hele algoritme — wat het doet, hoe het leert " +
                        "(indien ingeschakeld) en wat elke instelling betekent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(FCL_DOCS_URL)
                        )
                        ctx.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open handleiding")
                }
            }
        }

        FCLSection(
            title = s.settingsDosisBehavior,
            expanded = expandedDosering,
            onToggle = { expandedDosering = !expandedDosering }
        ) {
            FCLDoubleRow(
                label = s.settingsMaxBolusDay,
                summary = "Referentiewaarde voor de Analyzer (actieve maxSMB = 50–125% van deze waarde).",
                value = maxBolusDay,
                min = 0.1, max = 8.0, step = 0.05, unit = "U",
                onInfo = {
                    showInfo(
                        "Max bolus dag — referentiewaarde",
                        "Dit is de referentiewaarde die de Analyzer gebruikt om de actieve maxSMB " +
                            "bij te sturen.\n\n" +
                            "• De Analyzer mag de actieve maxSMB verhogen tot 125% van deze waarde\n" +
                            "• De Analyzer mag de actieve maxSMB verlagen tot 50% van deze waarde\n" +
                            "• De waarde die je hier instelt wordt zelf nooit gewijzigd\n\n" +
                            "Stel dit in op wat je normaal als maximale maaltijdbolus zou nemen."
                    )
                },
                onValueChange = {
                    maxBolusDay = it
                    sp.putDouble(DoubleKey.max_bolus_day.key, it)
                }
            )

            FCLDoubleRow(
                label = s.settingsMaxBolusNight,
                summary = "Maximale SMB 's nachts in één actie.",
                value = maxBolusNight,
                min = 0.1, max = 8.0, step = 0.05, unit = "U",
                onInfo = {
                    showInfo(
                        s.settingsMaxBolusNight,
                        "Maximale hoeveelheid insuline die FCL vNext 's nachts in één actie " +
                            "mag toedienen.\n\nExtra bescherming tegen nachtelijke hypo's."
                    )
                },
                onValueChange = {
                    maxBolusNight = it
                    sp.putDouble(DoubleKey.max_bolus_night.key, it)
                }
            )

            FCLDoubleRow(
                label = s.settingsMaxIob,
                summary = "Maximaal actief insuline in het lichaam.",
                value = maxIob,
                min = 1.0, max = 25.0, step = 0.5, unit = "U",
                onInfo = {
                    showInfo(
                        s.settingsMaxIob,
                        "FCL vNext levert geen extra insuline als het actieve insuline (IOB) " +
                            "boven deze waarde komt.\n\nStel dit in op ongeveer 2–3× je gemiddelde " +
                            "maaltijdbolus."
                    )
                },
                onValueChange = {
                    maxIob = it
                    sp.putDouble(DoubleKey.fcl_vnext_MaxIOB.key, it)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            FCLListRow(
                label = "💉 Insulineverdeling",
                summary = "Hoe insuline wordt verdeeld: meer basaal (smooth) of meer SMB (pulsen).",
                options = doseOptions,
                selected = doseStyle,
                onInfo = {
                    showInfo(
                        "Insulineverdeling",
                        "Bepaalt de verhouding tussen tijdelijke basaalraten en SMB-bolussen.\n\n" +
                            "⚖️ Balanced: standaard mix — aanbevolen startpunt."
                    )
                },
                onSelect = {
                    doseStyle = it
                    sp.putString(StringKey.fcl_vnext_dose_distribution_style.key, it)
                }
            )

        }

        FCLSection(
            title = "🤖 Analyser Automaat / AI Advisor",
            expanded = expandedAnalyserAutomaat,
            onToggle = { expandedAnalyserAutomaat = !expandedAnalyserAutomaat }
        ) {
            // 10/07/2026 — beide aan/uit + automatisch/handmatig-
            // schakelaars samengevoegd op één plek. Voorheen stond hier alleen
            // de oude "Automaat leert"-knop (nu vervangen door de Learner-kaart
            // hieronder), en stonden de nieuwe kaarten los in het Learner- resp.
            // AI-tabblad zelf — daar staat nu alleen nog de inhoud (het
            // openstaande voorstel), de bediening staat hier bij elkaar.
            //
            // DAG/NACHT-HERSTRUCTURERING (26/07/2026, de gebruiker; herzien n.a.v. de gebruikers
            // terugkoppeling): "dat moet maar 1 keer voor de analyzer en 1 keer
            // voor de AI zijn. Als ik hem uit wil zetten dan geldt dat zowel
            // voor dag als nacht en als ik dag wel automatisch zou willen maar
            // nacht niet dan zet ik nacht wel op handmatig en kan ik hem gewoon
            // afwijzen." Eerste versie had 4 losse aan/uit-schakelaars (Learner-
            // Dag/Nacht, AI-adviseur-Dag/Nacht apart); nu 2, via
            // FclDayNightModeSelectorCard: ÉÉN aan/uit per as die dag+nacht
            // samen raakt, met daaronder twee ONAFHANKELIJKE Automatisch/
            // Handmatig-keuzes. Dag/Nacht hergebruiken/introduceren dezelfde
            // onderliggende sleutels als voorheen (DFLearner's dag-/nacht-as,
            // FclAiAdvisorSettingsStore, FclNightBasalAutoAdjustStore — dat nu
            // de VOLLEDIGE nacht-AI-adviseur governt, niet alleen het
            // profiel-bijstellen, zie kdoc in FclNightAiAdvisorScheduler.kt).
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui.FclDayNightModeSelectorCard(
                    title = "Learner",
                    initialDayMode = DFLearner.getMode(ctx, isNight = false),
                    initialNightMode = DFLearner.getMode(ctx, isNight = true),
                    dayAutoDescription = "Past D/F/timing automatisch aan na elke maaltijdepisode",
                    dayManualDescription = "Berekent een voorstel, past pas toe na jouw goedkeuring",
                    nightAutoDescription = "Past de nacht-NF-schaal automatisch aan na elke nacht",
                    nightManualDescription = "Berekent een NF-voorstel, past pas toe na jouw goedkeuring",
                    onDayModeChange = { DFLearner.setMode(ctx, isNight = false, it) },
                    onNightModeChange = { DFLearner.setMode(ctx, isNight = true, it) }
                )
                app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui.FclDayNightModeSelectorCard(
                    title = "AI-adviseur",
                    initialDayMode = app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiAdvisorSettingsStore.getMode(ctx),
                    initialNightMode = app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.night.FclNightBasalAutoAdjustStore.getMode(ctx),
                    dayAutoDescription = "Past goedgekeurde parameters automatisch toe, geen melding",
                    dayManualDescription = "Meldt nieuwe voorstellen, past pas toe na jouw goedkeuring",
                    nightAutoDescription = "Genereert elk nachtrapport en past het basaal-advies automatisch toe op het pompprofiel",
                    nightManualDescription = "Genereert elk nachtrapport; basaal-aanpassing pas na jouw Accepteren op het Nacht-tabblad",
                    onDayModeChange = { app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiAdvisorSettingsStore.setMode(ctx, it) },
                    onNightModeChange = { app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.night.FclNightBasalAutoAdjustStore.setMode(ctx, it) }
                )
                // 16/08/2026 — ISF-auto-adjust: geen dag/nacht-as (ISF-metingen
                // zijn niet aan nacht gebonden zoals de basaal-variant), dus de
                // gewone, enkelvoudige FclModeSelectorCard i.p.v. de Dag/Nacht-
                // variant hierboven. Kaartje zelf staat in de Learner-tab
                // (IsfAutoAdjustCard, Dfcontroltab.kt) — hier alleen de bediening,
                // zelfde plaatsingsprincipe als Learner/AI-adviseur hierboven.
                app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui.FclModeSelectorCard(
                    title = "ISF-auto-adjust",
                    initialMode = app.aaps.plugins.aps.openAPSFCL.vnext.advisor.isf.FclIsfAutoAdjustStore.getMode(ctx),
                    autoDescription = "Past het gemiddelde ISF-voorstel automatisch toe op het pompprofiel, in kleine stapjes met een harde grens",
                    manualDescription = "Berekent een ISF-voorstel uit zuivere correctiemomenten, past pas toe na jouw goedkeuring",
                    onModeChange = { app.aaps.plugins.aps.openAPSFCL.vnext.advisor.isf.FclIsfAutoAdjustStore.setMode(ctx, it) }
                )
            }
        }

        FCLSection(
            title = s.settingsDagNacht,
            expanded = expandedContext,
            onToggle = { expandedContext = !expandedContext }
        ) {
            Text(
                text = "FCL vNext bepaalt automatisch of het dag of nacht is op basis van " +
                    "onderstaande tijden en weekenddagen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(s.settingsWeekendDagen, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = {
                    showInfo(
                        s.settingsWeekendDagen,
                        "Selecteer de dagen die als weekend worden behandeld."
                    )
                }) {
                    Icon(Icons.Default.Info, contentDescription = null,
                         tint = MaterialTheme.colorScheme.primary)
                }
            }
            WeekDaySelector(
                selectedDays = weekendDagenToBoolArray(weekendDagen),
                onDayToggle = { day, selected ->
                    val arr = weekendDagenToBoolArray(weekendDagen)
                    arr[day.ordinal] = selected
                    weekendDagen = boolArrayToWeekendDagen(arr)
                    sp.putString(StringKey.WeekendDagen.key, weekendDagen)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            FCLTimeRow(
                label = "Ochtendstart (doordeweeks)",
                summary = "Tijdstip waarop FCL overschakelt naar daginstellingen.",
                value = ochtendStart,
                onInfo = {
                    showInfo("Ochtendstart (doordeweeks)",
                             "Vanaf dit tijdstip gelden de daglimiet en de dag-nachtfactor.")
                },
                onWijzig = { showOchtendPicker = true }
            )

            FCLTimeRow(
                label = "Ochtendstart (weekend)",
                summary = "Tijdstip waarop FCL in het weekend overschakelt naar daginstellingen.",
                value = ochtendWeekend,
                onInfo = {
                    showInfo("Ochtendstart (weekend)",
                             "Dezelfde functie als doordeweeks, maar voor weekenddagen.")
                },
                onWijzig = { showOchtendWeekendPicker = true }
            )

            FCLTimeRow(
                label = "Nachtstart",
                summary = "Tijdstip waarop FCL overschakelt naar nachtinstellingen.",
                value = nachtStart,
                onInfo = {
                    showInfo("Nachtstart",
                             "Vanaf dit tijdstip gelden de nachtlimiet en de nachtrespons-instelling.")
                },
                onWijzig = { showNachtPicker = true }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Geleidelijke nacht-overgang (17/07/2026): i.p.v. dat alle
            // nacht-instellingen in één cyclus omklappen zodra Nachtstart
            // passeert, lopen ze lineair over gedurende deze duur. 0 minuten
            // = oude harde gedrag (direct omklappen).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Geleidelijke nacht-overgang", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (nachtOvergangMinuten > 0)
                            "Nacht-instellingen lopen lineair over gedurende $nachtOvergangMinuten minuten na Nachtstart."
                        else
                            "Uit: nacht-instellingen klappen direct om bij Nachtstart (oud gedrag).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    showInfo(
                        "Geleidelijke nacht-overgang",
                        "Bepaalt hoe lang na Nachtstart het duurt voordat FCL volledig op " +
                            "nacht-instellingen draait (gain, maxSMB, nacht-responsstijl, IOB-demping, " +
                            "de persistent-correctiedrempel en de AAPS-multiplier). In plaats van in " +
                            "één cyclus omklappen, lopen deze lineair over. Aanleiding: bij meerdere " +
                            "avonden ontstond een onnodig hoge piek doordat een nog-stijgende " +
                            "maaltijdreactie abrupt werd afgeremd zodra de klok Nachtstart passeerde. " +
                            "Bereik 0-180 minuten, stappen van 10. 0 = oude, directe gedrag."
                    )
                }) {
                    Icon(Icons.Default.Info, contentDescription = null,
                         tint = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { showNachtOvergangPicker = true }) {
                    Text("${nachtOvergangMinuten} min")
                }
            }
        }

        FCLSection(
            title = "🚶 Activiteit",
            expanded = expandedAutosens,
            onToggle = { expandedAutosens = !expandedAutosens }
        ) {
            // AutoSens-sectie verwijderd (18/06/2026)
            // Vereenvoudigd van 4 standen (UIT/LICHT/NORMAAL/STERK) naar AAN/UIT
            // (29/06/2026): intensiteitsdetectie op basis van stappenaantal regelt
            // het effect automatisch — aparte standen voegden geen meerwaarde toe.
            // 14/07/2026 — hernoemd naar "Kortetermijn activiteit (stappen)"
            // om deze duidelijk te onderscheiden van de nieuwe AIGF hieronder: dit
            // blok reageert op stappen NU (real-time, FCLActivityModule), AIGF
            // kijkt naar het 8-uurs-calorieniveau t.o.v. een glijdend 7-daags
            // gemiddelde (FclActivitySensitivity).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "🚶 Kortetermijn activiteit (stappen)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (actBehavior != "OFF")
                            "Past insuline en target direct aan op basis van de actuele stappenteller."
                        else
                            "Activiteitsdetectie uitgeschakeld.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = actBehavior != "OFF",
                    onCheckedChange = { aan ->
                        actBehavior = if (aan) "ON" else "OFF"
                        sp.putString(StringKey.fcl_vnext_activity_behavior.key, actBehavior)
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // ── AIGF: Activiteits Insuline Gevoeligheids Factor (14/07/2026) ──
            // Bewust EIGEN, niet-expert SharedPreferences-bestand ("fcl_activity_
            // sensitivity_settings", zie FCLvNext.kt isAigfActive()/getAigfMinPct()/
            // getAigfMaxPct()) — raw SharedPreferences i.p.v. een officiële AAPS-
            // Preferences DoubleKey/StringKey, omdat deze plugin geen toegang heeft
            // om nieuwe entries aan de AAPS-core keys-lijst toe te voegen. Bewust
            // standaard UIT en met een voorzichtig 95-105%-bereik — pas na een
            // periode van vertrouwen breder te zetten.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "📊 AIGF (Activiteits Insuline Gevoeligheidsfactor)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = aigfActive,
                            onCheckedChange = { active ->
                                aigfActive = active
                                ctx.getSharedPreferences("fcl_activity_sensitivity_settings", android.content.Context.MODE_PRIVATE)
                                    .edit().putBoolean("aigf_active", active).apply()
                            }
                        )
                    }
                    Text(
                        "Vergelijkt de calorieën van de laatste 8 uur met je glijdende 7-daagse gemiddelde. " +
                            "Actiever dan gemiddeld → gevoeliger voor insuline → extra afbouw ná de eerste grote " +
                            "commit. Inactiever dan gemiddeld → minder gevoelig → iets meer insuline bij de grote " +
                            "commit(s) van een maaltijd.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (aigfActive) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showAigfMinPicker = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Min: ${aigfMinPct.toInt()}%")
                            }
                            OutlinedButton(
                                onClick = { showAigfMaxPicker = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Max: ${aigfMaxPct.toInt()}%")
                            }
                        }
                    }
                }
            }
        }

        // ── Expert modus ──────────────────────────────────────────────────
        if (!showExpertSection) {
            OutlinedButton(
                onClick = { expertPinDialogOpen = true; expertPinInput = ""; expertPinError = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(s.expertModus)
            }
        } else {
            FCLSection(
                title = s.expertModus,
                expanded = true,
                onToggle = { showExpertSection = false }
            ) {
                // ── Taalinstelling ───────────────────────────────────
                Text(
                    if (selectedLocale.code == "nl") "Taalinstelling" else "Language",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    app.aaps.plugins.aps.openAPSFCL.vnext.lang.FclLocale.values().forEach { locale ->
                        FilterChip(
                            selected = selectedLocale == locale,
                            onClick = {
                                selectedLocale = locale
                                app.aaps.plugins.aps.openAPSFCL.vnext.lang.FclStrings.saveLocale(ctx, locale)
                            },
                            label = { Text(locale.displayName) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // ── Expert modus aan/uit toggle ──────────────────────
                var expertModeActive by remember {
                    mutableStateOf(
                        ctx.getSharedPreferences("fcl_expert_prefs", android.content.Context.MODE_PRIVATE)
                            .getBoolean("expert_mode_active", false)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (expertModeActive) s.expertModeAan else s.expertModeUit,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            s.fijnafstemming,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = expertModeActive,
                        onCheckedChange = { active ->
                            expertModeActive = active
                            ctx.getSharedPreferences("fcl_expert_prefs", android.content.Context.MODE_PRIVATE)
                                .edit().putBoolean("expert_mode_active", active).apply()
                        }
                    )
                }

                // ── Fijnafstemming blok (alleen zichtbaar als expert mode aan) ──
                if (expertModeActive) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // ── T1-versterking (13/07/2026) ────────────────────
                    // Losse, uitzetbare feature-toggle onder Expert modus. Direct
                    // gelezen door FCLvNext.kt (zelfde "fcl_expert_prefs"-bestand,
                    // zie isSustainT1BoostActive() aldaar) — geen herstart nodig,
                    // volgende cyclus leest de nieuwe waarde.
                    var sustainT1BoostActive by remember {
                        mutableStateOf(
                            ctx.getSharedPreferences("fcl_expert_prefs", android.content.Context.MODE_PRIVATE)
                                .getBoolean("sustain_t1_boost_active", false)
                        )
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    s.expertT1BoostTitel,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = sustainT1BoostActive,
                                    onCheckedChange = { active ->
                                        sustainT1BoostActive = active
                                        ctx.getSharedPreferences("fcl_expert_prefs", android.content.Context.MODE_PRIVATE)
                                            .edit().putBoolean("sustain_t1_boost_active", active).apply()
                                    }
                                )
                            }
                            Text(
                                s.expertT1BoostUitleg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // ── Vroege-signalering-drempel (predictedPeak start voor WATCHING) ──
                    // 01/09/2026: handmatige testschuif, zie kdoc bij
                    // WATCHING_PEAK_START_PREFS in FCLvNext.kt voor de backtest-
                    // onderbouwing. Bewust EIGEN SharedPreferences-bestand (zelfde
                    // patroon als AIGF/T1-boost hierboven) — geen nieuwe AAPS-
                    // Preferences-key nodig. Default (9,0) = huidig, ongewijzigd
                    // gedrag; schuif kan alleen binnen het backtest-geverifieerde
                    // bereik 7,5-9,0 bewegen (MIN/MAX-constantes hieronder moeten
                    // gelijk blijven aan WATCHING_PEAK_START_MIN/MAX in FCLvNext.kt).
                    val watchingPeakStartMin = 7.5f
                    val watchingPeakStartMax = 9.0f
                    var watchingPeakStartThreshold by remember {
                        mutableStateOf(
                            ctx.getSharedPreferences("fcl_watching_peak_start_settings", android.content.Context.MODE_PRIVATE)
                                .getFloat("watching_peak_start_threshold", 9.0f)
                                .coerceIn(watchingPeakStartMin, watchingPeakStartMax)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                s.expertPeakStartTitel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                            Text(
                                s.expertPeakStartUitleg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                s.expertPeakStartWaarde.format(watchingPeakStartThreshold),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                color = if (watchingPeakStartThreshold >= watchingPeakStartMax)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else
                                    MaterialTheme.colorScheme.primary
                            )
                            Slider(
                                value = watchingPeakStartThreshold,
                                onValueChange = { v ->
                                    val clamped = v.coerceIn(watchingPeakStartMin, watchingPeakStartMax)
                                    watchingPeakStartThreshold = clamped
                                    ctx.getSharedPreferences("fcl_watching_peak_start_settings", android.content.Context.MODE_PRIVATE)
                                        .edit().putFloat("watching_peak_start_threshold", clamped).apply()
                                },
                                valueRange = watchingPeakStartMin..watchingPeakStartMax,
                                steps = 14  // stapjes van 0,1 mmol tussen 7,5 en 9,0
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                s.fijnafstemming,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                            Text(
                                s.expertModusMeerOpties,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ── Update-checker (06/09/2026, de gebruiker) ───────────────────────
        // Losstaand van de rest van dit scherm: eigen SharedPreferences
        // (FclUpdatePrefs), geen AAPS-kern-Preferences. Zie FclUpdateChecker.kt
        // voor de volledige toelichting (Drive-map, bestandsnaamconventie).
        run {
            val coroutineScope = rememberCoroutineScope()
            // 07/09/2026 (de gebruiker) — FCL_STATUS_VERSION i.p.v. het
            // (nu bevroren) Android-versionCode, zie kdoc bij die constante
            // in FCLvNextStatusFormatter.kt en bij Versions.versionCode.
            val currentVersionCode = FCL_STATUS_VERSION
            var expandedUpdates by remember { mutableStateOf(startExpandedOnUpdates) }
            var updateChecking by remember { mutableStateOf(false) }
            var updateInstalling by remember { mutableStateOf(false) }
            var availableUpdate by remember { mutableStateOf(FclUpdatePrefs.availableUpdate(ctx)) }
            var lastCheckAtMs by remember { mutableStateOf(FclUpdatePrefs.lastCheckAtMs(ctx)) }
            var lastError by remember { mutableStateOf(FclUpdatePrefs.lastError(ctx)) }
            var whatsNewDialogText by remember { mutableStateOf<String?>(null) }
            // ── "Versie wijzigen" (07/09/2026, de gebruiker) ────────────────
            // Los van de "is er iets nieuwers"-check hierboven: deze lijst
            // toont ALLE versies uit de Drive-map (ook ouder dan wat nu
            // geinstalleerd is), zodat een mislukte/niet-bevallende update
            // teruggezet kan worden. Hoever terug mogelijk is, bepaalt de
            // gebruiker zelf door oude builds uit de Drive-map te
            // verwijderen (zie kdoc bij FclUpdateChecker.listAllVersions()).
            var showVersionPicker by remember { mutableStateOf(false) }
            var versionListLoading by remember { mutableStateOf(false) }
            var versionList by remember { mutableStateOf<List<FclUpdateChecker.VersionEntry>?>(null) }
            var versionListError by remember { mutableStateOf<String?>(null) }
            var selectedVersionCode by remember { mutableStateOf<Int?>(null) }
            var installError by remember { mutableStateOf<String?>(null) }

            FCLSection(
                title = "Updates",
                expanded = expandedUpdates,
                onToggle = { expandedUpdates = !expandedUpdates }
            ) {
                // 06/09/2026 (de gebruiker) — automatisch verversen bij het
                // openklappen van dit blok. AANLEIDING: "Update beschikbaar"
                // toonde nog versionCode 1596 terwijl 1597 allang in de
                // Drive-map stond — de vorige controle dateerde van vóór die
                // upload, en het scherm ververste dat verouderde resultaat
                // pas bij een handmatige "Controleer nu"-druk. Zonder verse
                // controle had "Update nu" op dat moment dus gewoon 1596
                // opnieuw gedownload i.p.v. 1597.
                // Deze content-lambda zit in de AnimatedVisibility van
                // FCLSection hierboven en wordt bij het dichtklappen uit
                // compositie gehaald — bij elke heropening is dit dus weer
                // een verse compositie, dus start LaunchedEffect(Unit) hier
                // gewoon opnieuw, ongeacht de key. Zelfde
                // FclUpdateScheduler.checkNow-pad als de "Controleer
                // nu"-knop verderop (geen nieuw mechanisme); de
                // !updateChecking-guard voorkomt alleen een dubbele
                // aanroep als er toevalligerwijs al een controle loopt
                // (bijv. de periodieke achtergrond-check).
                LaunchedEffect(Unit) {
                    // 08/09/2026 (de gebruiker) — de gebruiker heeft de Updates-
                    // sectie nu daadwerkelijk gezien (open­geklapt, hetzij
                    // handmatig hetzij via de update-melding), dus de eerder
                    // getoonde melding kan weg. Als checkNow hieronder een
                    // nieuwere versie vindt (bijv. omdat er ná de vorige check
                    // alweer een build is geüpload) verschijnt de melding vanzelf
                    // opnieuw via FclUpdateScheduler.notifyResult().
                    FclUpdateNotificationHelper.dismissUpdateNotice(ctx)
                    if (!updateChecking) {
                        updateChecking = true
                        FclUpdateScheduler.checkNow(ctx) { result ->
                            updateChecking = false
                            availableUpdate = result as? FclUpdateChecker.Result.UpdateAvailable
                            lastCheckAtMs = FclUpdatePrefs.lastCheckAtMs(ctx)
                            lastError = (result as? FclUpdateChecker.Result.Error)?.message
                        }
                    }
                }
                Text(
                    "Huidige versie: versionCode $currentVersionCode",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    lastCheckAtMs?.let { ms ->
                        "Laatste controle: " + java.text.SimpleDateFormat("dd-MM-yyyy HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(ms))
                    } ?: "Nog niet eerder gecontroleerd",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (availableUpdate != null) {
                    Text(
                        "Update beschikbaar: versionCode ${availableUpdate!!.versionCode}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (lastError != null) {
                    Text(
                        "Laatste controle mislukt: ${lastError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            updateChecking = true
                            FclUpdateScheduler.checkNow(ctx) { result ->
                                updateChecking = false
                                availableUpdate = result as? FclUpdateChecker.Result.UpdateAvailable
                                lastCheckAtMs = FclUpdatePrefs.lastCheckAtMs(ctx)
                                lastError = (result as? FclUpdateChecker.Result.Error)?.message
                            }
                        },
                        enabled = !updateChecking,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (updateChecking) "Bezig…" else "Controleer nu")
                    }
                    if (availableUpdate != null) {
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    val entries = withContext(Dispatchers.IO) {
                                        FclWhatsNewChecker.fetchSince(ctx, currentVersionCode)
                                    }
                                    whatsNewDialogText =
                                        if (entries.isEmpty()) "Geen changelog gevonden."
                                        else entries.joinToString("\n\n") { entry ->
                                            val text = entry.text.trim().ifBlank { "(geen changelogtekst)" }
                                            "v${entry.versionCode}:\n$text"
                                        }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Wat is nieuw")
                        }
                    }
                }
                // 07/09/2026 (de gebruiker) — altijd beschikbaar (niet meer
                // afhankelijk van availableUpdate != null): dit is ook de weg
                // terug naar een oudere versie, niet alleen naar een nieuwere.
                Button(
                    onClick = {
                        showVersionPicker = true
                        versionListLoading = true
                        versionListError = null
                        coroutineScope.launch {
                            when (val result = withContext(Dispatchers.IO) { FclUpdateChecker.listAllVersions() }) {
                                is FclUpdateChecker.VersionListResult.Success -> {
                                    versionList = result.versions
                                    selectedVersionCode = result.versions.maxByOrNull { it.versionCode }?.versionCode
                                }
                                is FclUpdateChecker.VersionListResult.NotConfigured -> {
                                    versionListError = "Update-checker niet geconfigureerd"
                                }
                                is FclUpdateChecker.VersionListResult.Error -> {
                                    versionListError = result.message
                                }
                            }
                            versionListLoading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Versie wijzigen")
                }
            }

            if (whatsNewDialogText != null) {
                AlertDialog(
                    onDismissRequest = { whatsNewDialogText = null },
                    title = { Text("Wat is nieuw") },
                    // 07/09/2026 (de gebruiker) — was een kale Text() zonder
                    // scroll-mogelijkheid: bij één langere versietekst (of,
                    // erger, meerdere versies achter elkaar via
                    // FclWhatsNewChecker.fetchSince() als er een tijdje niet
                    // is bijgewerkt) viel de tekst gewoon van het scherm af,
                    // onleesbaar. Zelfde patroon als de "Versie wijzigen"-
                    // dialoog hieronder: een hoogte-begrensde, scrollbare
                    // Column.
                    text = {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(whatsNewDialogText ?: "")
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { whatsNewDialogText = null }) { Text(s.close) }
                    }
                )
            }

            // ── "Versie wijzigen"-dialoog (07/09/2026, de gebruiker) ────────
            if (showVersionPicker) {
                AlertDialog(
                    onDismissRequest = { if (!updateInstalling) showVersionPicker = false },
                    title = { Text("Versie wijzigen") },
                    text = {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            when {
                                versionListLoading -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text("Bezig met ophalen…")
                                    }
                                }
                                versionListError != null -> {
                                    Text(
                                        "Kon versielijst niet ophalen: $versionListError",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                versionList.isNullOrEmpty() -> {
                                    Text("Geen versies gevonden in de Drive-map.")
                                }
                                else -> {
                                    versionList!!.forEach { entry ->
                                        val isCurrent = entry.versionCode == currentVersionCode
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { selectedVersionCode = entry.versionCode }
                                                .padding(vertical = 4.dp)
                                        ) {
                                            RadioButton(
                                                selected = entry.versionCode == selectedVersionCode,
                                                onClick = { selectedVersionCode = entry.versionCode }
                                            )
                                            Text(
                                                "versionCode ${entry.versionCode}" + if (isCurrent) " (huidige)" else ""
                                            )
                                        }
                                    }
                                    val selected = selectedVersionCode
                                    if (selected != null && selected < currentVersionCode) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "Let op: dit zet de app terug naar een OUDERE versie dan nu " +
                                                "geïnstalleerd. Doe dit alleen als je weet dat dit veilig is " +
                                                "(bijv. geen database-wijziging tussen deze versies).",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    if (installError != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "Installeren mislukt: $installError",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = !updateInstalling && !versionListLoading && selectedVersionCode != null,
                            onClick = {
                                val entry = versionList?.firstOrNull { it.versionCode == selectedVersionCode }
                                    ?: return@TextButton
                                coroutineScope.launch {
                                    updateInstalling = true
                                    installError = null
                                    val result = FclUpdateInstaller.downloadAndLaunchInstall(ctx, entry.fileId)
                                    updateInstalling = false
                                    if (result is FclUpdateInstaller.Result.Error) {
                                        installError = result.message
                                    } else {
                                        showVersionPicker = false
                                    }
                                }
                            }
                        ) {
                            Text(if (updateInstalling) "Downloaden…" else "Installeren")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            enabled = !updateInstalling,
                            onClick = { showVersionPicker = false }
                        ) { Text(s.cancel) }
                    }
                )
            }
        }

        // ── CSV-upload (07/09/2026, de gebruiker) ────────────────────────
        // Stuurt het bestaande rollende 7-dagen-logboek naar een door de
        // gebruiker zelf gedeployde Apps Script Web App, zodat een CSV
        // delen niet meer los ge-e-maild hoeft te worden. Zie
        // FclCsvUploader.kt voor de volledige toelichting (waarom geen
        // service-account-sleutel, het device-ID in de bestandsnaam, enz.).
        run {
            val coroutineScope = rememberCoroutineScope()
            var expandedCsvUpload by remember { mutableStateOf(false) }
            var uploading by remember { mutableStateOf(false) }
            var uploadResultText by remember { mutableStateOf<String?>(null) }
            var uploadIsError by remember { mutableStateOf(false) }

            FCLSection(
                title = "CSV delen",
                expanded = expandedCsvUpload,
                onToggle = { expandedCsvUpload = !expandedCsvUpload }
            ) {
                Text(
                    "Stuurt je meest recente 7-dagen-logboek naar de gedeelde " +
                        "Drive-map, zodat die bekeken kan worden zonder dat je " +
                        "zelf een bestand hoeft te versturen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uploadResultText != null) {
                    Text(
                        uploadResultText ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uploadIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                Button(
                    onClick = {
                        uploading = true
                        uploadResultText = null
                        coroutineScope.launch {
                            when (val result = FclCsvUploader.upload(ctx)) {
                                is FclCsvUploader.Result.Success -> {
                                    uploadIsError = false
                                    uploadResultText = "Geüpload als ${result.fileName}"
                                }
                                is FclCsvUploader.Result.NotConfigured -> {
                                    uploadIsError = true
                                    uploadResultText = "CSV-upload niet geconfigureerd"
                                }
                                is FclCsvUploader.Result.CsvNotFound -> {
                                    uploadIsError = true
                                    uploadResultText = "Nog geen logboek gevonden om te uploaden"
                                }
                                is FclCsvUploader.Result.Error -> {
                                    uploadIsError = true
                                    uploadResultText = "Uploaden mislukt: ${result.message}"
                                }
                            }
                            uploading = false
                        }
                    },
                    enabled = !uploading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (uploading) "Bezig met uploaden…" else "Upload CSV")
                }
            }
        }

    } // einde Column

    // ── Tijdpickers (buiten Column — zijn Popups) ─────────────────────────
    if (showOchtendPicker) {
        TimeWheelPicker(
            selectedHour = ochtendStart.toHour(),
            selectedMinute = ochtendStart.toMinute(),
            minHour = 4, maxHour = 11, minuteStep = 15,
            onTimeSelected = { h, m ->
                ochtendStart = "%02d:%02d".format(h, m)
                sp.putString(StringKey.OchtendStart.key, ochtendStart)
            },
            onDismiss = { showOchtendPicker = false }
        )
    }
    if (showOchtendWeekendPicker) {
        TimeWheelPicker(
            selectedHour = ochtendWeekend.toHour(),
            selectedMinute = ochtendWeekend.toMinute(),
            minHour = 4, maxHour = 12, minuteStep = 15,
            onTimeSelected = { h, m ->
                ochtendWeekend = "%02d:%02d".format(h, m)
                sp.putString(StringKey.OchtendStartWeekend.key, ochtendWeekend)
            },
            onDismiss = { showOchtendWeekendPicker = false }
        )
    }
    if (showNachtPicker) {
        TimeWheelPicker(
            selectedHour = nachtStart.toHour(),
            selectedMinute = nachtStart.toMinute(),
            minHour = 20, maxHour = 23, minuteStep = 15,
            onTimeSelected = { h, m ->
                nachtStart = "%02d:%02d".format(h, m)
                sp.putString(StringKey.NachtStart.key, nachtStart)
            },
            onDismiss = { showNachtPicker = false }
        )
    }

    // ── AIGF min/max %-pickers (14/07/2026) ──────────────────────────
    // Zelfde "buiten de Column, want Popup"-plaatsing als de tijd-pickers
    // hierboven. Min% begrensd 75-100, Max% begrensd 100-125 — symmetrisch
    // rond het neutrale punt 100 (zie FclActivitySensitivity.kt).
    if (showAigfMinPicker) {
        PercentWheelPicker(
            initialValue = aigfMinPct.toInt(),
            minValue = 75, maxValue = 100,
            title = "AIGF Min %",
            onValueSelected = { v ->
                aigfMinPct = v.toDouble()
                ctx.getSharedPreferences("fcl_activity_sensitivity_settings", android.content.Context.MODE_PRIVATE)
                    .edit().putFloat("aigf_min_pct", v.toFloat()).apply()
            },
            onDismiss = { showAigfMinPicker = false }
        )
    }
    if (showAigfMaxPicker) {
        PercentWheelPicker(
            initialValue = aigfMaxPct.toInt(),
            minValue = 100, maxValue = 125,
            title = "AIGF Max %",
            onValueSelected = { v ->
                aigfMaxPct = v.toDouble()
                ctx.getSharedPreferences("fcl_activity_sensitivity_settings", android.content.Context.MODE_PRIVATE)
                    .edit().putFloat("aigf_max_pct", v.toFloat()).apply()
            },
            onDismiss = { showAigfMaxPicker = false }
        )
    }
    if (showNachtOvergangPicker) {
        val stapMinuten = 10
        val aantalStappen = (app.aaps.plugins.aps.openAPSFCL.vnext.FclNachtOvergangSettings.MAX_MINUTEN / stapMinuten) + 1
        val labels = (0 until aantalStappen).map { (it * stapMinuten).toString() }
        PercentWheelPicker(
            initialValue = (nachtOvergangMinuten / stapMinuten).coerceIn(0, aantalStappen - 1),
            minValue = 0, maxValue = aantalStappen - 1,
            title = "Nacht-overgang (minuten)",
            displayedValues = labels,
            onValueSelected = { indexVal ->
                val minuten = indexVal * stapMinuten
                nachtOvergangMinuten = minuten
                app.aaps.plugins.aps.openAPSFCL.vnext.FclNachtOvergangSettings.set(ctx, minuten)
            },
            onDismiss = { showNachtOvergangPicker = false }
        )
    }

    // ── Info dialoog ──────────────────────────────────────────────────────
    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(infoDialogTitle) },
            text = { Text(infoDialogText) },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text(s.close)
                }
            }
        )
    }

    // ── Pincode dialoog voor expert modus ─────────────────────────────────
    if (expertPinDialogOpen) {
        AlertDialog(
            onDismissRequest = { expertPinDialogOpen = false },
            title = { Text(s.expertModus) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Voer de pincode in (standaard: 0000).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = expertPinInput,
                        onValueChange = { if (it.length <= 4) { expertPinInput = it; expertPinError = false } },
                        label = { Text(s.pincode) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        isError = expertPinError,
                        singleLine = true
                    )
                    if (expertPinError) {
                        Text(
                            s.expertPinFout,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (expertPinInput == EXPERT_PIN) {
                        showExpertSection = true
                        expertPinDialogOpen = false
                    } else expertPinError = true
                }) { Text(s.openen) }
            },
            dismissButton = {
                TextButton(onClick = { expertPinDialogOpen = false }) { Text(s.annuleren) }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Herbruikbare componenten
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FCLSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun FCLDoubleRow(
    label: String,
    summary: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    unit: String,
    onInfo: () -> Unit,
    onValueChange: (Double) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = onInfo, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Info, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                val raw = (value - step).coerceAtLeast(min)
                onValueChange((Math.round(raw / step) * step * 100.0).toLong() / 100.0)
            }) { Text("−", style = MaterialTheme.typography.titleMedium) }
            Text(
                text = "%.2f %s".format(value, unit),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(min = 60.dp)
            )
            IconButton(onClick = {
                val raw = (value + step).coerceAtMost(max)
                onValueChange((Math.round(raw / step) * step * 100.0).toLong() / 100.0)
            }) { Text("+", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun FCLListRow(
    label: String,
    summary: String,
    options: List<Pair<String, String>>,
    selected: String,
    onInfo: () -> Unit,
    onSelect: (String) -> Unit
) {
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = onInfo, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Info, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "▸ $selectedLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Box {
            TextButton(onClick = { expanded = true }) { Text(s.change) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, display) ->
                    DropdownMenuItem(
                        text = { Text(display) },
                        onClick = { onSelect(value); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun FCLTimeRow(
    label: String,
    summary: String,
    value: String,
    onInfo: () -> Unit,
    onWijzig: () -> Unit
) {
    val s = FclStrings.get(androidx.compose.ui.platform.LocalContext.current)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = onInfo, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Info, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        TextButton(onClick = onWijzig) { Text(s.change) }
    }
}

// ── styleNumberPickerText (15/07/2026, de gebruiker; bijgewerkt 15/07/2026 na
// Android Studio-waarschuwing over reflectie op API 29+) ────────────────────
// Bugfix: android.widget.NumberPicker erft zijn tekstkleur van het GLOBALE
// Activity-thema (light/dark), niet van de Compose MaterialTheme die de
// omliggende Dialog/Surface hierboven kleurt. Bij de gebruiker bleek dat op een licht
// scherm de Surface wit is, maar de NumberPicker-cijfers ook (bijna) wit
// waren -> onzichtbaar. Twee tekst-bronnen binnen NumberPicker moeten apart
// gekleurd worden:
//   1. De GESELECTEERDE waarde: een losse, publiek bereikbare EditText-kind
//      (altijd via de publieke weg, geen reflectie nodig).
//   2. De NIET-geselecteerde waarden erboven/eronder: getekend via een
//      interne Paint (mSelectorWheelPaint). Android 10/API 29 introduceerde
//      hiervoor de OFFICIËLE publieke methode NumberPicker.setTextColor(),
//      die gebruiken we vanaf hier — geen reflectie meer nodig op de
//      apparaten waar de meeste gebruikers op zitten. Reflectie op dat
//      interne veld is vanaf API 29 bovendien niet gegarandeerd meer
//      toegankelijk (hidden-API-restricties) en gaf precies daarom de
//      terechte Android Studio-waarschuwing. Onder API 29 bestaat
//      setTextColor() niet, dus daar blijft de reflectie-route als
//      fallback bestaan, met stille no-op als het veld op een OEM-variant
//      een andere naam heeft (dan blijft alleen de geselecteerde waarde
//      alsnog zichtbaar, i.p.v. een crash).
private fun styleNumberPickerText(picker: android.widget.NumberPicker, colorArgb: Int) {
    for (i in 0 until picker.childCount) {
        val child = picker.getChildAt(i)
        if (child is android.widget.EditText) {
            child.setTextColor(colorArgb)
        }
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        picker.setTextColor(colorArgb)
    } else {
        try {
            val paintField = android.widget.NumberPicker::class.java.getDeclaredField("mSelectorWheelPaint")
            paintField.isAccessible = true
            (paintField.get(picker) as? android.graphics.Paint)?.color = colorArgb
        } catch (e: Exception) {
            // Best-effort — zie kdoc hierboven.
        }
    }
}

// ── PercentWheelPicker (14/07/2026) ──────────────────────────────────
// Scrollbare heel-getal-kiezer voor de AIGF min/max%-instellingen, analoog
// aan TimeWheelPicker hierboven maar zelfstandig (geen afhankelijkheid van
// dat bestand — TimeWheelPicker's implementatie zit elders in het project
// en was niet in de aangeleverde bestanden aanwezig). Gebruikt de klassieke
// android.widget.NumberPicker via AndroidView: een beproefde, native
// scrollwiel-widget met ingebouwde grenzen (minValue/maxValue) — voorkomt
// dat de gebruiker een waarde buiten het toegestane bereik kan intypen,
// wat met de eerdere vrije-tekst-invoer wél kon.
@Composable
private fun PercentWheelPicker(
    initialValue: Int,
    minValue: Int,
    maxValue: Int,
    title: String,
    // 17/07/2026 (nacht-overgang-picker): optionele custom labels per
    // stap (bijv. "0","10","20",...) i.p.v. de kale index — de NumberPicker
    // zelf blijft intern op index werken (minValue..maxValue), de aanroeper
    // rekent de index terug naar de echte waarde. null = ongewijzigd oud
    // gedrag (kale integers, zoals de AIGF %-pickers).
    displayedValues: List<String>? = null,
    onValueSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var currentValue by remember { mutableStateOf(initialValue.coerceIn(minValue, maxValue)) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                val aigfPickerTextColorArgb = MaterialTheme.colorScheme.onSurface.toArgb()
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { context ->
                        android.widget.NumberPicker(context).apply {
                            // Android-eigenaardigheid: displayedValues moet leeg zijn vóórdat
                            // min/max wijzigen, anders kan een oude array met een niet-passende
                            // lengte een crash geven.
                            this.displayedValues = null
                            this.minValue = minValue
                            this.maxValue = maxValue
                            if (displayedValues != null) this.displayedValues = displayedValues.toTypedArray()
                            this.value = currentValue
                            this.wrapSelectorWheel = false
                            setOnValueChangedListener { _, _, newVal -> currentValue = newVal }
                            styleNumberPickerText(this, aigfPickerTextColorArgb)
                        }
                    },
                    update = { picker -> styleNumberPickerText(picker, aigfPickerTextColorArgb) },
                    modifier = Modifier.wrapContentSize()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onDismiss) { Text("Annuleren") }
                    Button(onClick = { onValueSelected(currentValue); onDismiss() }) { Text("OK") }
                }
            }
        }
    }
}