package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.Episode
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeMetrics
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

private const val MEAL_LIST_DAYS = 7L

/**
 * Indexes (in [episodes]) of the recognized meals of the last 7 days.
 * Uses the same episode list as the Episodes screen, so the counts match.
 */
internal fun mealIndexesLastDays(episodes: List<Episode>?): List<Int> {
    if (episodes == null) return emptyList()
    val from = Instant.now().minus(MEAL_LIST_DAYS, ChronoUnit.DAYS)
    return episodes.indices.filter { !episodes[it].start.isBefore(from) }
}

/**
 * List of recognized meals of the last 7 days, grouped per date.
 * A tap on a time opens the episode screen on that meal.
 */
@Composable
fun MealListScreen(
    episodes: List<Episode>,
    metrics: List<EpisodeMetrics>,
    onOpenMeal: (episodeIndex: Int) -> Unit,
    onBack: () -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL) }
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm") }

    val byDay = remember(episodes) {
        mealIndexesLastDays(episodes)
            .groupBy { episodes[it].start.atZone(zone).toLocalDate() }
            .toSortedMap(compareByDescending<LocalDate> { it })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBack) { Text(stringResource(R.string.fcl_meals_back)) }
            Text(
                stringResource(R.string.fcl_meals_title),
                style = MaterialTheme.typography.titleLarge
            )
        }

        if (byDay.isEmpty()) {
            Text(stringResource(R.string.fcl_meals_empty))
            return@Column
        }

        Text(
            stringResource(R.string.fcl_meals_total, byDay.values.sumOf { it.size }),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        byDay.forEach { (day, indexes) ->
            Text(
                stringResource(
                    R.string.fcl_meals_day_header,
                    day.format(dateFormat),
                    indexes.size
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
            indexes.sortedBy { episodes[it].start }.forEach { index ->
                val time = episodes[index].start.atZone(zone).format(timeFormat)
                val m = metrics.getOrNull(index)
                val detail = if (m != null)
                    stringResource(R.string.fcl_meals_detail, m.peakBg, m.totalInsulinDelivered)
                else ""
                Text(
                    stringResource(R.string.fcl_meals_row, time, detail),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenMeal(index) }
                        .padding(start = 24.dp, top = 8.dp, bottom = 8.dp)
                )
            }
        }
    }
}
