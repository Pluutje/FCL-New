package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DailyGvp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.GvpCalculator
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.GvpStats
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.LogRow
import kotlin.math.ceil
import kotlin.math.floor

// Colors (same palette as the PGR / HbA1c charts)
private val GvpGreen = Color(0xFF2E7D32)
private val GvpAmber = Color(0xFFF9A825)
private val GvpRed = Color(0xFFB71C1C)

// Indicative GVP limits (percent). Not a hard norm, depends on the sensor.
private const val GVP_GOOD = 30.0
private const val GVP_MEDIUM = 40.0

private const val HBA1C_GOOD = 7.0
private const val TIR_GOOD = 70.0
private const val TIR_MEDIUM = 50.0

private fun gvpColor(gvp: Double): Color = when {
    gvp <= GVP_GOOD   -> GvpGreen
    gvp <= GVP_MEDIUM -> GvpAmber
    else              -> GvpRed
}

private fun hba1cColor(pct: Double): Color = when {
    pct <= HBA1C_GOOD -> GvpGreen
    pct <= 8.0        -> GvpAmber
    else              -> GvpRed
}

private fun magColor(mag: Double): Color = when {
    mag <= 2.0 -> GvpGreen
    mag <= 3.0 -> GvpAmber
    else       -> GvpRed
}

private fun tirColor(tir: Double): Color = when {
    tir >= TIR_GOOD   -> GvpGreen
    tir >= TIR_MEDIUM -> GvpAmber
    else              -> GvpRed
}

private val WINDOWS = listOf(7, 14, 30)

/** One colored band in a per-day chart. */
private data class Band(val from: Double, val to: Double, val color: Color)

/** Separate card under the CGP card. */
@Composable
fun GvpKaart(rows: List<LogRow>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            GvpTabelBlok(rows)
        }
    }
}

/**
 * Table block: HbA1c (mmol/mol, % between brackets), GVP and MAG for 7, 14 and 30 days.
 * Data source: the FCLvNext cycle log (same rows as all other statistics).
 */
@Composable
private fun GvpTabelBlok(rows: List<LogRow>) {
    val stats: List<Pair<Int, GvpStats?>> = remember(rows) {
        WINDOWS.map { days -> days to GvpCalculator.windowStats(rows, days) }
    }
    var toonInfo by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "〰 Curve length (GVP) + HbA1c",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "7 / 14 / 30 days",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "ⓘ tap for info",
                modifier = Modifier.clickable { toonInfo = true },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
        }

        if (stats.all { it.second == null }) {
            Text(
                "Not enough data yet (at least a few hours needed).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            GvpTabel(stats)
        }
    }

    if (toonInfo) GvpInfoDialog(onDismiss = { toonInfo = false })
}

@Composable
private fun GvpTabel(stats: List<Pair<Int, GvpStats?>>) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text("", modifier = Modifier.weight(1.5f))
            stats.forEach { (days, _) ->
                Text(
                    "$days d",
                    modifier = Modifier.weight(1.5f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End
                )
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

        GvpRij("HbA1c (est.)", stats, { hba1cColor(estimateHba1cPct(it.meanMmol)) }) {
            val pct = estimateHba1cPct(it.meanMmol)
            "%.0f (%.1f%%)".format(pctToMmolMol(pct), pct)
        }
        GvpRij("GVP", stats, { gvpColor(it.gvpPct) }) { "%.1f%%".format(it.gvpPct) }
        GvpRij("MAG (mmol/L/h)", stats, { magColor(it.magMmolPerHour) }) {
            "%.2f".format(it.magMmolPerHour)
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    }
}

@Composable
private fun GvpRij(
    label: String,
    stats: List<Pair<Int, GvpStats?>>,
    kleur: (GvpStats) -> Color,
    tekst: (GvpStats) -> String
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1.5f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        stats.forEach { (_, s) ->
            Text(
                if (s == null) "—" else tekst(s),
                modifier = Modifier.weight(1.5f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (s == null) MaterialTheme.colorScheme.onSurfaceVariant else kleur(s),
                textAlign = TextAlign.End
            )
        }
    }
}

/** Per-day GVP: dots colored by zone + line with the rolling 14-day average. */
@Composable
fun GvpPerDagLijn(rows: List<LogRow>, modifier: Modifier = Modifier) {
    val dagen: List<DailyGvp> = remember(rows) { GvpCalculator.dailyStats(rows, 30) }
    val waarden = dagen.map { it.stats.gvpPct }
    val yMax = maxOf(50.0, ceil((waarden.maxOrNull() ?: 50.0) / 10.0) * 10.0)
    DagTrendCanvas(
        waarden = waarden,
        yMin = 0.0,
        yMax = yMax,
        gridStep = 10.0,
        bands = listOf(
            Band(0.0, GVP_GOOD, GvpGreen),
            Band(GVP_GOOD, GVP_MEDIUM, GvpAmber),
            Band(GVP_MEDIUM, yMax, GvpRed)
        ),
        dotColor = ::gvpColor,
        modifier = modifier
    )
}

/** Per-day TIR: dots colored by zone + line with the rolling 14-day average. */
@Composable
fun TirPerDagLijn(rows: List<LogRow>, modifier: Modifier = Modifier) {
    val dagen: List<DailyGvp> = remember(rows) { GvpCalculator.dailyStats(rows, 30) }
    val waarden = dagen.map { it.stats.tirPct }
    DagTrendCanvas(
        waarden = waarden,
        yMin = 0.0,
        yMax = 100.0,
        gridStep = 25.0,
        bands = listOf(
            Band(0.0, TIR_MEDIUM, GvpRed),
            Band(TIR_MEDIUM, TIR_GOOD, GvpAmber),
            Band(TIR_GOOD, 100.0, GvpGreen)
        ),
        dotColor = ::tirColor,
        modifier = modifier
    )
}

@Composable
private fun DagTrendCanvas(
    waarden: List<Double>,
    yMin: Double,
    yMax: Double,
    gridStep: Double,
    bands: List<Band>,
    dotColor: (Double) -> Color,
    modifier: Modifier = Modifier
) {
    val lijnKleur = MaterialTheme.colorScheme.secondary
    val gridKleur = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val labelKleur = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val lijn = GvpCalculator.rollingAverage(waarden, 14)
    val textMeasurer = rememberTextMeasurer()

    if (waarden.size < 2) {
        Text(
            "Not enough days with data yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Canvas(modifier = modifier) {
        val labelW = 32f
        val w = size.width - labelW
        val h = size.height
        val n = waarden.size

        fun xOf(i: Int) = labelW + (i.toFloat() / (n - 1)) * w
        fun yOf(v: Double) = h - ((v.coerceIn(yMin, yMax) - yMin) / (yMax - yMin) * h).toFloat()

        bands.forEach { b ->
            val top = yOf(b.to)
            val bottom = yOf(b.from)
            if (bottom > top) {
                drawRect(b.color.copy(alpha = 0.12f), Offset(labelW, top), Size(w, bottom - top))
            }
        }

        val labelStyle = TextStyle(fontSize = 9.sp, color = labelKleur)
        var level = ceil(yMin / gridStep) * gridStep
        while (level <= yMax + 1e-9) {
            val y = yOf(level)
            drawLine(gridKleur, Offset(labelW, y), Offset(labelW + w, y), 0.8f)
            val t = "%.0f".format(level)
            val m = textMeasurer.measure(t, labelStyle)
            drawText(textMeasurer, t, Offset(0f, y - m.size.height / 2f), labelStyle)
            level += gridStep
        }

        val path = Path()
        lijn.forEachIndexed { i, v ->
            val x = xOf(i)
            val y = yOf(v)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lijnKleur, style = Stroke(width = 2.5f))

        waarden.forEachIndexed { i, v ->
            val c = Offset(xOf(i), yOf(v))
            drawCircle(dotColor(v), radius = 5f, center = c)
            drawCircle(Color.White, radius = 2.5f, center = c)
        }
    }
}

/** Info popup. In English, like the PGR and HbA1c info popups. */
@Composable
private fun GvpInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Curve length (GVP) and HbA1c",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoKop("Why look at the curve length?")
                Text(
                    "HbA1c only tells you the average. A steady 9 and a curve that jumps " +
                        "between 3 and 15 can give the same HbA1c. The curve length shows how " +
                        "restless the line is. A good HbA1c with a calm curve is the best combination.",
                    style = MaterialTheme.typography.bodySmall
                )
                InfoKop("GVP (Glycemic Variability Percentage)")
                Text(
                    "Hill et al. 2018. The real length of the glucose line (time and glucose together) " +
                        "compared with a perfectly flat line. 0% = flat line. 30% means the line is 30% " +
                        "longer than flat. Lower is calmer.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "up to 30%  calm / good\n30 - 40%  medium\nabove 40%  restless",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                InfoKop("MAG")
                Text(
                    "Mean Absolute Glucose change: the total up and down movement of the glucose " +
                        "line, per hour (mmol/L per hour). Lower is calmer.",
                    style = MaterialTheme.typography.bodySmall
                )
                InfoKop("The charts")
                Text(
                    "GVP per day and TIR per day show one dot per day. The line is the rolling " +
                        "14-day average. For GVP lower is better, for TIR higher is better. " +
                        "The HbA1c per day chart is shown above.",
                    style = MaterialTheme.typography.bodySmall
                )
                InfoKop("Good to know")
                Text(
                    "The limits above are a guide, not a hard norm. GVP depends on the sensor and its " +
                        "reading interval, so compare only with your own earlier values on the same sensor. " +
                        "GVP counts a dip like a peak, so also look at TBR (time below 4.0). " +
                        "Gaps longer than 15 minutes are skipped.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Calculated from the FCLvNext cycle log, the same data as the other statistics on " +
                        "this page. The HbA1c here is the same estimate as in the Glucose ranges card, " +
                        "in mmol/mol with the % value between brackets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun InfoKop(tekst: String) {
    Text(
        tekst,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}
