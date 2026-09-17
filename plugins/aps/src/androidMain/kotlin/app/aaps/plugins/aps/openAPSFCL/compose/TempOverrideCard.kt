package app.aaps.plugins.aps.openAPSFCL.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 * functie houdt i.p.v. een snelkoppeling naar andere FCLvNext-instellingen te zijn — met het oog
 * op toekomstige extra override-functies/preset-knoppen op diezelfde plek. FCLSettingsScreen.kt
 * blijft deze kaart ZELF ook nog tonen (fase-1-tabblad, ongewijzigd gedrag).
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
                    Slider(
                        value = tempOverridePct.toFloat(),
                        onValueChange = { v ->
                            val stepped = (v / FclTempOverrideSettings.STEP_PCT).toInt() * FclTempOverrideSettings.STEP_PCT
                            tempOverridePct = stepped.coerceIn(FclTempOverrideSettings.MIN_PCT, FclTempOverrideSettings.MAX_PCT)
                        },
                        valueRange = FclTempOverrideSettings.MIN_PCT.toFloat()..FclTempOverrideSettings.MAX_PCT.toFloat(),
                        steps = (FclTempOverrideSettings.MAX_PCT - FclTempOverrideSettings.MIN_PCT) / FclTempOverrideSettings.STEP_PCT - 1,
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
                    Slider(
                        value = tempOverrideDurationMin.toFloat(),
                        onValueChange = { v ->
                            val stepped = (v / 30).toInt() * 30
                            tempOverrideDurationMin = stepped.coerceIn(
                                FclTempOverrideSettings.MIN_DURATION_MIN,
                                FclTempOverrideSettings.MAX_DURATION_MIN
                            )
                        },
                        valueRange = FclTempOverrideSettings.MIN_DURATION_MIN.toFloat()..FclTempOverrideSettings.MAX_DURATION_MIN.toFloat(),
                        steps = (FclTempOverrideSettings.MAX_DURATION_MIN - FclTempOverrideSettings.MIN_DURATION_MIN) / 30 - 1
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
                }
            }
        }
    }
}
