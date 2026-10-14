package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CgpHistory
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CgpScore
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CgpScoreCalculator
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.LongTermHistory
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.OverviewPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * CGP Scorekaart met:
 *  1. PGR-totaalscore + label
 *  2. Vijf deelmetrieken met referentie
 *  3. Pentagon (radar) op basis van 14-daags gemiddelde
 *  4. PGR-trendlijn: dagelijkse punten + 3-daags voortschrijdend gemiddelde
 *
 * Methode: Vigersky 2018 (zelfde als AAPS Statistics).
 * Pentagon kleiner = beter; PGR lager = beter.
 */
@Composable
fun CgpScoreKaart(context: Context) {
    val scores14d  = remember { CgpHistory.get14dScores(context) }
    val scores24h  = remember { CgpHistory.get24hScores(context) }

    // Lijn = voortschrijdend gemiddelde van de 24h-stippen zelf.
    // Het laatste lijnpunt = gemiddelde van alle 14 zichtbare stippen;
    // verder terug in de tijd het gemiddelde van minder stippen.
    // Deze reeks is parallel aan scores24h (niet aan scores14d).
    val lijnReeks: List<Double?> = remember {
        CgpHistory.getRollingAverageOfDots(context)
    }

    // Bovenste blok toont het meest recente 14-daagse venster — dat is
    // het officiële 14-daags gemiddelde, consistent met AAPS Statistics.
    val prev14d  = scores14d.dropLast(1).lastOrNull()?.pgr

    // BUGFIX (18/06/2026): voorheen werd hier window14.average() genomen
    // over de laatste 14 DAGPUNTEN — maar elk dagpunt in scores14d is zelf
    // al een 14-daags schuifvenster-gemiddelde (zie class-comment hierboven).
    // Een gemiddelde van 14 van die punten is dus een gemiddelde-van-
    // gemiddelden, met een effectief venster tot ~28 dagen, dat oudere,
    // mogelijk afwijkende periodes onterecht laat doorwegen. Dit veroorzaakte
    // een waargenomen discrepantie tussen de TIR-kaart (schoon 14-dagen-
    // venster, bv. TAR 6%) en deze tabel (uitgesmeerd gemiddelde, bv.
    // Hyper-tijd 9,3% op hetzelfde moment). Nu: gewoon het laatste,
    // enkelvoudige 14-daags-schuifvenster-datapunt — consistent met de
    // TIR-kaart en met de class-comment hierboven.
    val display: CgpScore? = scores14d.lastOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Header ────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("📊 CGP Score — 14 dagen",
                         style = MaterialTheme.typography.bodyMedium,
                         fontWeight = FontWeight.SemiBold)
                    Text("Methode: Vigersky 2018",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (display != null) {
                    val arrow = CgpScoreCalculator.trendArrow(display.pgr, prev14d)
                    var toonInfo by remember { mutableStateOf(false) }

                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.clickable { toonInfo = true }
                    ) {
                        Text("PGR ${"%.1f".format(display.pgr)}$arrow",
                             style = MaterialTheme.typography.titleLarge,
                             fontWeight = FontWeight.Bold,
                             color = pgrKleur(display.pgr))
                        Text(display.pgrLabel,
                             style = MaterialTheme.typography.labelSmall,
                             color = pgrKleur(display.pgr))
                        Text("ⓘ tap for info",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                    }

                    if (toonInfo) {
                        PgrInfoDialog(pgr = display.pgr, onDismiss = { toonInfo = false })
                    }
                } else {
                    Text("—", style = MaterialTheme.typography.titleLarge,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (display == null) {
                Text("Score beschikbaar na de eerste middernacht-berekening " +
                         "(minimaal 1 dag data nodig).",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }

            // ── Metrieken-tabel met bijdragepercentage ────────────────────
            val bijdragen = CgpScoreCalculator.contributionPct(
                torPct   = display.torPct,
                cvPct    = display.cvPct,
                hypoPct  = display.hypoPct,
                hyperPct = display.hyperPct,
                meanMgdl = display.meanMgdl
            )
            val dimensies = listOf("TOR","CV","HYPO","HYPER","MEAN")

            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                // Tabelkop
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Parameter",
                         modifier = Modifier.weight(2.2f),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         fontWeight = FontWeight.SemiBold)
                    Text("Ref",
                         modifier = Modifier.weight(1.2f),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         fontWeight = FontWeight.SemiBold,
                         textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text("Actueel",
                         modifier = Modifier.weight(1.5f),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         fontWeight = FontWeight.SemiBold,
                         textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text("Bijdrage",
                         modifier = Modifier.weight(1.5f),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         fontWeight = FontWeight.SemiBold,
                         textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
                Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

                dimensies.forEach { dim ->
                    val pct  = bijdragen[dim] ?: 0
                    val kleur = when (CgpScoreCalculator.contributionColor(pct)) {
                        "GREEN"  -> androidx.compose.ui.graphics.Color(0xFF2E7D32) // donkergroen
                        "YELLOW" -> androidx.compose.ui.graphics.Color(0xFFF9A825) // amber
                        "ORANGE" -> androidx.compose.ui.graphics.Color(0xFFE65100) // donkeroranje
                        else     -> MaterialTheme.colorScheme.error                // rood
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Parameternaam
                        Text(
                            CgpScoreCalculator.dimensionLabel(dim),
                            modifier = Modifier.weight(2.2f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Referentiewaarde
                        Text(
                            CgpScoreCalculator.REFERENCE[dim] ?: "",
                            modifier = Modifier.weight(1.2f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                        // Actuele waarde
                        Text(
                            CgpScoreCalculator.dimensionValue(display, dim),
                            modifier = Modifier.weight(1.5f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                        // Bijdrage % met kleurcode
                        Text(
                            "$pct%",
                            modifier = Modifier.weight(1.5f),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = kleur,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    }
                }
                Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            }

            Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            // ── Pentagon op basis van het laatste 14-daags schuifvenster-punt ──
            // (zelfde brondatapunt als de tabel hierboven — zie bugfix-comment
            // bij gemiddeld14Daags())
            val gemiddeld14d = gemiddeld14Daags(display)
            if (gemiddeld14d != null) {
                Text("Pentagon (laatste 14-daags venster)",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)

                // 20/07/2026: BUGFIX — MaterialTheme.colorScheme.primary.copy(
                // green = 0.7f) forceerde alleen het groen-kanaal en liet rood/blauw
                // van de actieve primary-kleur ongemoeid. De primary van deze app is
                // paars/lavendel (hoog rood EN hoog blauw) — in het donkere thema
                // overheerst dat, dus "Referentie (gezond)" bleef paars/grijzig i.p.v.
                // groen. In het lichte thema had primary toevallig een andere
                // rood/blauw-balans waardoor het geforceerde groen-kanaal wél
                // domineerde. Vaste, thema-onafhankelijke groentint (dezelfde
                // "donkergroen" als elders in dit bestand, bijv. contributionColor
                // GREEN en de PGR-zones hieronder) lost dit op — geen kans meer dat
                // een thema-kleur de bedoelde groene betekenis overstemt.
                val refKleur   = androidx.compose.ui.graphics.Color(0xFF2E7D32)
                val patKleur   = MaterialTheme.colorScheme.primary
                val gridKleur  = MaterialTheme.colorScheme.outlineVariant
                val asKleur    = MaterialTheme.colorScheme.outline
                val labelKleur = MaterialTheme.colorScheme.onSurface
                val textMeasurer = rememberTextMeasurer()

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                ) {
                    tekenPentagon(
                        gemiddeld14d  = gemiddeld14d,
                        latest        = display,
                        refKleur      = refKleur,
                        patKleur      = patKleur,
                        gridKleur     = gridKleur,
                        asKleur       = asKleur,
                        labelKleur    = labelKleur,
                        textMeasurer  = textMeasurer
                    )
                }

                // Legenda
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegendaDot(color = refKleur, tekst = "Referentie (gezond)")
                    Spacer(Modifier.width(20.dp))
                    LegendaDot(color = patKleur, tekst = "14-daags gemiddelde")
                }
            }

        }
    }
}

/**
 * Losse kaart met de vier verlopen (stippen + gemiddelde lijn):
 * HbA1c, PGR, GVP en TIR. Staat onder de CGP-kaart en de GVP-kaart.
 * Rechtsboven kiest de gebruiker de periode: 14 d (per dag), 30 d (per 2 dagen),
 * 90 d (per week) of 1 jaar (per maand). Data: LongTermHistory (dagwaarden uit BG).
 */
@Composable
fun CgpTrendKaart(context: Context) {
    var period by remember { mutableStateOf(OverviewPeriod.D14) }
    var menuOpen by remember { mutableStateOf(false) }
    var dagen by remember { mutableStateOf(LongTermHistory.load(context)) }
    // Opnieuw laden als de achtergrondtaak net nieuwe dagen heeft weggeschreven.
    LaunchedEffect(Unit) {
        dagen = withContext(Dispatchers.IO) { LongTermHistory.load(context) }
    }
    val buckets = remember(dagen, period) { LongTermHistory.buckets(dagen, period) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📈 Overzicht",
                     style = MaterialTheme.typography.bodyMedium,
                     fontWeight = FontWeight.SemiBold)
                Box {
                    TextButton(onClick = { menuOpen = true }) {
                        Text("${period.label} ▾")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        OverviewPeriod.values().forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.label) },
                                onClick = { period = p; menuOpen = false }
                            )
                        }
                    }
                }
            }

            Text("HbA1c (geschat, mmol/mol)",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Hba1cPeriodLijn(buckets, period, Modifier.fillMaxWidth().height(80.dp))

            Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            Text("PGR (risicoscore, lager = beter)",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            PgrPeriodLijn(buckets, period, Modifier.fillMaxWidth().height(80.dp))

            Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            Text("GVP (onrust van de curve, lager = beter)",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            GvpPeriodLijn(buckets, period, Modifier.fillMaxWidth().height(80.dp))

            Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            Text("TIR (tijd in bereik, hoger = beter)",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            TirPeriodLijn(buckets, period, Modifier.fillMaxWidth().height(80.dp))

            Text(period.legend,
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
        }
    }
}

// ── Pentagon tekenen ─────────────────────────────────────────────────────

private fun DrawScope.tekenPentagon(
    gemiddeld14d: List<Double>,
    latest: CgpScore,
    refKleur: Color, patKleur: Color,
    gridKleur: Color, asKleur: Color,
    labelKleur: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val maxR = min(cx, cy) * 0.72f

    val BASELINE_OFFSET = 0.18
    fun norm(v: Double, max: Double) =
        BASELINE_OFFSET + (1.0 - BASELINE_OFFSET) * (v / max).coerceIn(0.0, 1.0)

    val refValues = listOf(
        norm(0.0,  100.0), norm(17.0,  60.0), norm(0.0,  20.0),
        norm(0.0,   80.0), norm(90.0, 300.0)
    )
    val patValues = gemiddeld14d

    fun axisAngle(i: Int): Double = (-90.0 + i * 72.0) * PI / 180.0

    // Grid-pentagons
    for (level in 1..4) {
        val fr = level / 4f
        val path = Path()
        for (i in 0..4) {
            val a = axisAngle(i)
            val x = cx + maxR * fr * cos(a).toFloat()
            val y = cy + maxR * fr * sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, gridKleur, style = Stroke(1f))
    }

    // As-lijnen
    for (i in 0..4) {
        val a = axisAngle(i)
        drawLine(asKleur,
                 start = Offset(cx, cy),
                 end = Offset(cx + maxR * cos(a).toFloat(), cy + maxR * sin(a).toFloat()),
                 strokeWidth = 1f)
    }

    // Referentie-pentagon
    drawDataPentagon(cx, cy, maxR, refValues, refKleur.copy(alpha = 0.20f), refKleur, 2f)

    // Patiënt-pentagon
    drawDataPentagon(cx, cy, maxR, patValues, patKleur.copy(alpha = 0.25f), patKleur, 2.5f)

    // As-labels: naam + actuele waarde in grijs op tweede regel
    val asLabels = listOf("TOR", "%CV", "Hypo%", "Hyper%", "Mean")
    val asWaarden = listOf(
        "${"%.0f".format(latest.torPct)}%",
        "${"%.1f".format(latest.cvPct)}%",
        "${"%.1f".format(latest.hypoPct)}%",
        "${"%.1f".format(latest.hyperPct)}%",
        "${"%.1f".format(latest.meanMmol)} mmol"
    )
    val labelStijl = TextStyle(fontSize = 10.sp, color = labelKleur,
                               textAlign = TextAlign.Center)
    val waardeStijl = TextStyle(fontSize = 9.sp,
                                color = labelKleur.copy(alpha = 0.55f),
                                textAlign = TextAlign.Center)

    for (i in 0..4) {
        val a = axisAngle(i)
        val lr = maxR * 1.20f
        val lx = cx + lr * cos(a).toFloat()
        val ly = cy + lr * sin(a).toFloat()

        val labelTekst = asLabels[i]
        val waardeTekst = asWaarden[i]

        val mLabel  = textMeasurer.measure(labelTekst, labelStijl)
        val mWaarde = textMeasurer.measure(waardeTekst, waardeStijl)
        val totH = mLabel.size.height + mWaarde.size.height + 1

        val topY = ly - totH / 2f
        drawText(textMeasurer, labelTekst,
                 topLeft = Offset(lx - mLabel.size.width / 2f, topY),
                 style = labelStijl)
        drawText(textMeasurer, waardeTekst,
                 topLeft = Offset(lx - mWaarde.size.width / 2f, topY + mLabel.size.height + 1),
                 style = waardeStijl)
    }
}

private fun DrawScope.drawDataPentagon(
    cx: Float, cy: Float, maxR: Float,
    values: List<Double>,
    fillColor: Color, strokeColor: Color, strokeWidth: Float
) {
    val path = Path()
    for (i in values.indices) {
        val a = (-90.0 + i * 72.0) * PI / 180.0
        val r = maxR * values[i].toFloat()
        val x = cx + r * cos(a).toFloat()
        val y = cy + r * sin(a).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, fillColor, style = Fill)
    drawPath(path, strokeColor, style = Stroke(strokeWidth))
}

@Composable
private fun LegendaDot(color: Color, tekst: String) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Text(tekst, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun pgrKleur(pgr: Double) = when {
    pgr <= 3.0 -> MaterialTheme.colorScheme.primary
    pgr <= 4.0 -> MaterialTheme.colorScheme.tertiary
    else       -> MaterialTheme.colorScheme.error
}

/**
 * Informatieve popup over de CGP/PGR-methode.
 * Bewust in het Engels — methodetoelichting hoeft niet vertaald.
 */
@Composable
private fun PgrInfoDialog(pgr: Double, onDismiss: () -> Unit) {
    val riskLabel = when {
        pgr <= 2.0 -> "very low risk"
        pgr <= 3.0 -> "low risk"
        pgr <= 4.0 -> "moderate risk"
        pgr <= 4.5 -> "high risk"
        else       -> "extremely high risk"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Prognostic Glycemic Risk (PGR)",
                 style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Your current PGR is ${"%.1f".format(pgr)} ($riskLabel).",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "What is PGR?",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "PGR (Prognostic Glycemic Risk) is a composite score derived from " +
                        "the Comprehensive Glucose Pentagon (CGP), introduced by Vigersky & McMahon (2018). " +
                        "It combines five CGM-derived metrics into a single number that reflects " +
                        "both short-term hypoglycemia risk and long-term complication risk.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "The five metrics",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "• TOR — Time Out of Range (< 3.9 or > 10.0 mmol/L)\n" +
                        "• %CV — Coefficient of Variation (SD ÷ mean glucose)\n" +
                        "• Hypo% — Time below 3.9 mmol/L\n" +
                        "• Hyper% — Time above 10.0 mmol/L\n" +
                        "• Mean — Average glucose level",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "How is it calculated?",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Each metric is normalized to a 0.18–1.0 scale. The PGR equals " +
                        "the area of the patient's pentagon divided by the area of a reference " +
                        "pentagon representing a person without diabetes. " +
                        "A smaller pentagon means better control — so a lower PGR is better.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Risk thresholds (Vigersky 2018)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "≤ 2.0  Very low risk\n" +
                        "≤ 3.0  Low risk\n" +
                        "≤ 4.0  Moderate risk\n" +
                        "≤ 4.5  High risk\n" +
                        "> 4.5  Extremely high risk",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Text(
                    "The score shown here is calculated from the FCLvNext cycle log " +
                        "over the past 14 days and is updated daily at midnight. " +
                        "It uses the same method as AAPS Statistics.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
/**
 * Normaliseert één CgpScore (bedoeld: het laatste 14-daags
 * schuifvenster-punt) naar [0.18-1.0] voor de pentagon-grafiek.
 *
 * BUGFIX (20/06/2026): nam voorheen het gemiddelde over ALLE punten in
 * scores14d — maar elk punt in scores14d is zelf al een 14-daags
 * schuifvenster-gemiddelde (zie class-comment hierboven en de bugfix van
 * 18/06/2026 voor `display` in CgpScoreKaart()). Een gemiddelde van die
 * punten is dus weer een gemiddelde-van-gemiddelden, met een effectief
 * venster tot ~28 dagen — exact dezelfde fout die toen voor de tabel is
 * opgelost, maar hier per ongeluk niet meegenomen. Gevolg: bij waarden die
 * in de tabel (Actueel) op 0% staan, week de pentagon daar toch vanaf
 * zodra er meerdere dagpunten in scores14d zaten. Nu: zelfde brondatapunt
 * (`display`) als de tabel, dus altijd consistent.
 */
private fun gemiddeld14Daags(latest: CgpScore?): List<Double>? {
    if (latest == null) return null

    val BASELINE_OFFSET = 0.18
    fun norm(v: Double, max: Double) =
        BASELINE_OFFSET + (1.0 - BASELINE_OFFSET) * (v / max).coerceIn(0.0, 1.0)

    return listOf(
        norm(latest.torPct,    100.0),
        norm(latest.cvPct,      60.0),
        norm(latest.hypoPct,    20.0),
        norm(latest.hyperPct,   80.0),
        norm(latest.meanMgdl,  300.0)
    )
}