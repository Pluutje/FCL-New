package app.aaps.plugins.aps.openAPSFCL.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.FclTempOverrideSettings
import app.aaps.plugins.aps.openAPSFCL.vnext.FclTempOverrideSettings.OverridePreset
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * "Tijdelijke aanpassing" / "Override"-kaart (11/09/2026, de gebruiker) — zie kdoc bij
 * FclTempOverrideSettings.kt voor de volledige aanleiding en werking (vlak op het ingestelde
 * percentage tot 75% van de duur, daarna kwadratisch vloeiend terug naar 100%, auto-expire na de
 * volledige duur). Verandert NOOIT de vaste instellingen (max bolus/IOB/agressiviteit) — die
 * blijven ongemoeid; dit is een losse, tijdelijke multiplier die op precies één plek in
 * FCLvNext.kt wordt toegepast.
 *
 * 17/09/2026 (de gebruiker) — losgetrokken uit FCLSettingsScreen.kt naar deze eigen, herbruikbare
 * composable: de "Override"-knop op het alternatieve overzichtsscherm (FclOverviewScreen.kt) moet
 * ALLEEN deze kaart tonen (direct uitgeklapt, in een eigen bottom sheet — geen navigatie naar het
 * volledige FCLvNext-instellingenscherm), zodat de knop een duidelijke, op zichzelf staande
 * functie houdt i.p.v. een snelkoppeling naar andere FCLvNext-instellingen te zijn.
 *
 * 24/09/2026 (de gebruiker, ronde 2) — presets herzien: elke van de 1-3 porties heeft nu een EIGEN,
 * apart instelbare hoeveelheid EN vertraging (sliders, resp. 0,05E- en 5min-stappen) i.p.v. één
 * totaalbedrag gelijk verdeeld. Zie kdoc bij FclTempOverrideSettings.PRESETS-blok voor het volledige
 * ontwerp (incl. de asymmetrische afbouw en het pauzeren).
 */
@Composable
fun TempOverrideCard(
    startExpanded: Boolean = false,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    var tempOverridePct by remember { mutableStateOf(FclTempOverrideSettings.getPercentage(ctx)) }
    var tempOverrideDurationMin by remember { mutableStateOf(FclTempOverrideSettings.getDurationMinutes(ctx)) }
    var tempOverrideStatus by remember {
        mutableStateOf(FclTempOverrideSettings.status(ctx, System.currentTimeMillis()))
    }
    // Standaard dicht (11/09/2026, de gebruiker) — titel + livestatus hieronder blijven ook dicht
    // zichtbaar, zodat in één oogopslag duidelijk is of er iets actief is, zonder open te hoeven
    // klappen. startExpanded=true wanneer dit scherm net via een snelkoppeling is geopend.
    var expandedTempOverride by remember { mutableStateOf(startExpanded) }

    // Ververst de live "nog X min"-status elke 30 seconden zolang dit kaartje in compositie is —
    // zelfde eenvoudige polling-aanpak als elders in dit scherm, geen aparte Flow/observer nodig
    // voor zo'n lichte, lokale klok-tik.
    LaunchedEffect(Unit) {
        while (true) {
            tempOverrideStatus = FclTempOverrideSettings.status(ctx, System.currentTimeMillis())
            delay(30_000L)
        }
    }

    val sliderColor = when {
        tempOverridePct < 100 -> MaterialTheme.colorScheme.error
        tempOverridePct > 100 -> MaterialTheme.colorScheme.primary
        else                  -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (tempOverrideStatus.active)
                MaterialTheme.colorScheme.tertiaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { expandedTempOverride = !expandedTempOverride })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("🎚️", style = MaterialTheme.typography.titleMedium)
                    Column {
                        Text(
                            "Tijdelijke aanpassing",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (tempOverrideStatus.active) {
                            val uren = tempOverrideStatus.remainingMinutes / 60
                            val minuten = tempOverrideStatus.remainingMinutes % 60
                            Text(
                                "Actief: ${"%.0f".format(tempOverrideStatus.effectiveMul * 100)}% · nog ${uren}u ${minuten}m",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                "Niet actief",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Icon(
                    imageVector = if (expandedTempOverride) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            AnimatedVisibility(
                visible = expandedTempOverride,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Schaalt elke dosis tijdelijk op of af — bijvoorbeeld voorzichtiger " +
                            "als je een hypo ziet aankomen en die gaat wegeten, of juist iets " +
                            "sterker bij een uitgebreide maaltijd. Blijft de hele duur op het " +
                            "ingestelde percentage en loopt in het laatste kwart vloeiend terug " +
                            "naar normaal (100%). Laat je vaste instellingen ongemoeid.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (tempOverrideStatus.active) {
                        val eindTijd = SimpleDateFormat("HH:mm", Locale.getDefault())
                            .format(Date(System.currentTimeMillis() + tempOverrideStatus.remainingMinutes * 60_000L))
                        Text(
                            "Actief tot $eindTijd",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Text(
                        "Percentage: $tempOverridePct%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = sliderColor
                    )
                    StepperSlider(
                        value = tempOverridePct.toFloat(),
                        onValueChange = { v ->
                            tempOverridePct = v.toInt().coerceIn(FclTempOverrideSettings.MIN_PCT, FclTempOverrideSettings.MAX_PCT)
                        },
                        valueRange = FclTempOverrideSettings.MIN_PCT.toFloat()..FclTempOverrideSettings.MAX_PCT.toFloat(),
                        step = FclTempOverrideSettings.STEP_PCT.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = sliderColor,
                            activeTrackColor = sliderColor
                        )
                    )

                    val durationUur = tempOverrideDurationMin / 60.0
                    Text(
                        "Duur: %.1f uur".format(durationUur),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    StepperSlider(
                        value = tempOverrideDurationMin.toFloat(),
                        onValueChange = { v ->
                            tempOverrideDurationMin = v.toInt().coerceIn(
                                FclTempOverrideSettings.MIN_DURATION_MIN,
                                FclTempOverrideSettings.MAX_DURATION_MIN
                            )
                        },
                        valueRange = FclTempOverrideSettings.MIN_DURATION_MIN.toFloat()..FclTempOverrideSettings.MAX_DURATION_MIN.toFloat(),
                        step = 30f
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                FclTempOverrideSettings.start(ctx, tempOverridePct, tempOverrideDurationMin)
                                tempOverrideStatus = FclTempOverrideSettings.status(ctx, System.currentTimeMillis())
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (tempOverrideStatus.active) "Herstart" else "Start")
                        }
                        if (tempOverrideStatus.active) {
                            OutlinedButton(
                                onClick = {
                                    FclTempOverrideSettings.stop(ctx)
                                    tempOverrideStatus = FclTempOverrideSettings.status(ctx, System.currentTimeMillis())
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Stop")
                            }
                        }
                    }

                    // ── Presets (24/09/2026, de gebruiker) ──────────────────────────
                    // Zie kdoc bij FclTempOverrideSettings.PRESETS-blok voor de volledige
                    // aanleiding/afbouwregels. Elke preset combineert percentage+duur (zelfde
                    // mechanisme als hierboven) met 1-3 porties, elk met een EIGEN hoeveelheid
                    // en vertraging. De activatieknop toont de naam van het preset en is
                    // uitgeschakeld zolang er niets is ingesteld.
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        "Presets",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Combineert percentage+duur met 1-3 porties extra insuline (elk met een " +
                            "eigen hoeveelheid — mag negatief zijn, bijv. vóór het sporten — en " +
                            "een eigen vertraging), boven op de normale dosis. Tik op een preset " +
                            "om 'm direct te starten; tik op ✏️ om aan te passen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    var presets by remember { mutableStateOf(FclTempOverrideSettings.getPresets(ctx)) }
                    var editingPresetId by remember { mutableStateOf<Int?>(null) }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        presets.forEach { preset ->
                            PresetRow(
                                preset = preset,
                                isEditing = editingPresetId == preset.id,
                                onApply = {
                                    FclTempOverrideSettings.startWithPreset(ctx, preset, System.currentTimeMillis())
                                    tempOverrideStatus = FclTempOverrideSettings.status(ctx, System.currentTimeMillis())
                                    tempOverridePct = FclTempOverrideSettings.getPercentage(ctx)
                                    tempOverrideDurationMin = FclTempOverrideSettings.getDurationMinutes(ctx)
                                },
                                onEditToggle = {
                                    editingPresetId = if (editingPresetId == preset.id) null else preset.id
                                },
                                onSave = { updated ->
                                    FclTempOverrideSettings.savePreset(ctx, updated)
                                    presets = FclTempOverrideSettings.getPresets(ctx)
                                    editingPresetId = null
                                },
                                onCancelEdit = { editingPresetId = null }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Slider met +/- stapknoppen ernaast (24/09/2026, ronde 3, de gebruiker: "het is nu soms heel
 * moeilijk om de juiste waarde te selecteren om per stap te verschuiven") — een druk op + of -
 * verschuift de waarde met precies één stap, voor nauwkeurige bediening naast het gewone
 * sleepgedrag van de Slider zelf. Rondt zowel sleep- als knop-waarden af op hetzelfde
 * stappenraster (snap()), zodat beide invoerwegen altijd op dezelfde, geldige waarden uitkomen —
 * `steps` (het aantal TUSSENliggende Material3-stopposities) wordt hier één keer berekend uit
 * [valueRange]/[step], zodat aanroepers dat niet meer los hoeven te doen.
 */
@Composable
private fun StepperSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    colors: SliderColors = SliderDefaults.colors(),
    modifier: Modifier = Modifier
) {
    val steps = (((valueRange.endInclusive - valueRange.start) / step).roundToInt() - 1).coerceAtLeast(0)
    fun snap(v: Float): Float {
        val raw = valueRange.start + round((v - valueRange.start) / step) * step
        return raw.coerceIn(valueRange.start, valueRange.endInclusive)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        IconButton(onClick = { onValueChange(snap(value - step)) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Remove, contentDescription = "Verlagen", modifier = Modifier.size(18.dp))
        }
        Slider(
            value = value,
            onValueChange = { onValueChange(snap(it)) },
            valueRange = valueRange,
            steps = steps,
            colors = colors,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { onValueChange(snap(value + step)) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Add, contentDescription = "Verhogen", modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Kleine 3-koloms infotabel (Portie/Hoeveelheid/Vertraging) onder een preset-knop, plus
 * percentage+duur erboven — vervangt de eerdere eenregelige samenvatting (24/09/2026, ronde 2:
 * de gebruiker wil dit als "kleine tabel" i.p.v. platte tekst).
 */
@Composable
private fun PresetInfoTable(preset: OverridePreset, modifier: Modifier = Modifier) {
    val colWidth = 64.dp
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // 24/09/2026 (crash-fix, de gebruiker) — NOOIT .format() aanroepen op een string die al een
        // geïnterpoleerd "%" bevat (zoals "${preset.percentage}%"): dat "%" wordt dan zelf als
        // conversie-specifier gelezen en crasht op het volgende teken (hier "·",
        // UnknownFormatConversionException). Los geformatteerd en dan pas samengevoegd.
        val durationText = "%.1f".format(preset.durationMinutes / 60.0)
        Text(
            "${preset.percentage}% · $durationText uur",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val activePortions = (0 until preset.portionCount).mapNotNull { i ->
            val amount = preset.portionAmountsU.getOrElse(i) { 0.0 }
            if (amount == 0.0) null else Triple(i + 1, amount, preset.portionDelaysMin.getOrElse(i) { 0 })
        }
        if (activePortions.isNotEmpty()) {
            Row {
                Text("Portie", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(colWidth))
                Text("Hoeveelh.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(colWidth))
                Text("Vertraging", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(colWidth))
            }
            activePortions.forEach { (n, amount, delayMin) ->
                Row {
                    Text("#$n", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(colWidth))
                    Text("${"%+.2f".format(amount)}E", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(colWidth))
                    Text("${delayMin}min", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(colWidth))
                }
            }
        }
    }
}

/**
 * Eén preset-rij: gesloten toont een activatieknop (naam van het preset als label, uitgeschakeld
 * zolang FclTempOverrideSettings.isConfigured() false is) + potlood (bewerken), met de infotabel
 * eronder. Open (isEditing) toont een inline editor met een LOKALE draft (pas bij "Opslaan" via
 * onSave teruggeschreven) zodat tikken op een ander preset of annuleren de wijzigingen niet per
 * ongeluk bewaart.
 */
@Composable
private fun PresetRow(
    preset: OverridePreset,
    isEditing: Boolean,
    onApply: () -> Unit,
    onEditToggle: () -> Unit,
    onSave: (OverridePreset) -> Unit,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configured = FclTempOverrideSettings.isConfigured(preset)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onApply,
                enabled = configured,
                modifier = Modifier.weight(1f)
            ) {
                Text(preset.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = onEditToggle) {
                Icon(
                    imageVector = if (isEditing) Icons.Default.Close else Icons.Default.Edit,
                    contentDescription = if (isEditing) "Bewerken sluiten" else "Preset bewerken"
                )
            }
        }

        if (configured) {
            PresetInfoTable(preset, modifier = Modifier.padding(top = 2.dp, start = 4.dp))
        } else {
            Text(
                "Niet ingesteld",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp)
            )
        }

        AnimatedVisibility(visible = isEditing, enter = expandVertically(), exit = shrinkVertically()) {
            PresetEditor(
                preset = preset,
                onSave = onSave,
                onCancel = onCancelEdit
            )
        }
    }
}

/**
 * Inline editor voor één preset. Alle velden werken op een LOKALE draft (remember(preset.id)) —
 * pas bij "Opslaan" wordt FclTempOverrideSettings.savePreset() aangeroepen.
 *
 * 24/09/2026 (ronde 2, de gebruiker) — elke portie heeft nu een EIGEN hoeveelheid-slider (stappen
 * van 0,05E, mag negatief) EN een eigen vertraging-slider (stappen van 5 min, begrensd door de
 * ingestelde duur van dit preset) i.p.v. één gedeeld totaalbedrag en tekstvelden.
 */
@Composable
private fun PresetEditor(
    preset: OverridePreset,
    onSave: (OverridePreset) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember(preset.id) { mutableStateOf(preset.name) }
    var pct by remember(preset.id) { mutableStateOf(preset.percentage) }
    var durationMin by remember(preset.id) { mutableStateOf(preset.durationMinutes) }
    var portionCount by remember(preset.id) { mutableStateOf(preset.portionCount) }
    var portionAmounts by remember(preset.id) {
        mutableStateOf((0 until FclTempOverrideSettings.MAX_PORTION_COUNT).map { i -> preset.portionAmountsU.getOrElse(i) { 0.0 } })
    }
    var portionDelays by remember(preset.id) {
        mutableStateOf((0 until FclTempOverrideSettings.MAX_PORTION_COUNT).map { i -> preset.portionDelaysMin.getOrElse(i) { 0 }.coerceIn(0, preset.durationMinutes) })
    }

    val amountStepF = FclTempOverrideSettings.EXTRA_INSULIN_STEP_U.toFloat()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 4.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Naam") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Percentage: $pct%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        StepperSlider(
            value = pct.toFloat(),
            onValueChange = { v -> pct = v.toInt().coerceIn(FclTempOverrideSettings.MIN_PCT, FclTempOverrideSettings.MAX_PCT) },
            valueRange = FclTempOverrideSettings.MIN_PCT.toFloat()..FclTempOverrideSettings.MAX_PCT.toFloat(),
            step = FclTempOverrideSettings.STEP_PCT.toFloat()
        )

        Text(
            "Duur: %.1f uur".format(durationMin / 60.0),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
        StepperSlider(
            value = durationMin.toFloat(),
            onValueChange = { v ->
                durationMin = v.toInt().coerceIn(FclTempOverrideSettings.MIN_DURATION_MIN, FclTempOverrideSettings.MAX_DURATION_MIN)
                // Vertragingen mogen nooit voorbij de (mogelijk net verkorte) duur wijzen.
                portionDelays = portionDelays.map { it.coerceIn(0, durationMin) }
            },
            valueRange = FclTempOverrideSettings.MIN_DURATION_MIN.toFloat()..FclTempOverrideSettings.MAX_DURATION_MIN.toFloat(),
            step = 30f
        )

        Text("Aantal porties", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..FclTempOverrideSettings.MAX_PORTION_COUNT).forEach { n ->
                FilterChip(
                    selected = portionCount == n,
                    onClick = { portionCount = n },
                    label = { Text("$n") },
                    colors = FilterChipDefaults.filterChipColors()
                )
            }
        }

        for (i in 0 until portionCount) {
            Divider(modifier = Modifier.padding(vertical = 2.dp))
            Text("Portie ${i + 1}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)

            val amount = portionAmounts.getOrElse(i) { 0.0 }
            Text(
                "Hoeveelheid: ${"%+.2f".format(amount)} E",
                style = MaterialTheme.typography.bodySmall,
                color = if (amount < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            StepperSlider(
                value = amount.toFloat(),
                onValueChange = { v ->
                    portionAmounts = portionAmounts.toMutableList().also {
                        while (it.size <= i) it.add(0.0)
                        it[i] = v.toDouble().coerceIn(FclTempOverrideSettings.MIN_EXTRA_INSULIN_U, FclTempOverrideSettings.MAX_EXTRA_INSULIN_U)
                    }
                },
                valueRange = FclTempOverrideSettings.MIN_EXTRA_INSULIN_U.toFloat()..FclTempOverrideSettings.MAX_EXTRA_INSULIN_U.toFloat(),
                step = amountStepF
            )

            val delayMin = portionDelays.getOrElse(i) { 0 }
            Text(
                "Vertraging: $delayMin min na start",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            StepperSlider(
                value = delayMin.toFloat(),
                onValueChange = { v ->
                    portionDelays = portionDelays.toMutableList().also {
                        while (it.size <= i) it.add(0)
                        it[i] = v.toInt().coerceIn(0, durationMin)
                    }
                },
                valueRange = 0f..durationMin.toFloat(),
                step = FclTempOverrideSettings.PORTION_DELAY_STEP_MIN.toFloat()
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(
                        preset.copy(
                            name = name.ifBlank { preset.name },
                            percentage = pct,
                            durationMinutes = durationMin,
                            portionCount = portionCount,
                            portionAmountsU = portionAmounts,
                            portionDelaysMin = portionDelays
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Opslaan")
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text("Annuleren")
            }
        }
    }
}
