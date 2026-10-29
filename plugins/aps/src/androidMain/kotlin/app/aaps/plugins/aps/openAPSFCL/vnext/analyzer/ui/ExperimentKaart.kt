package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Arrows of the fold button. */
private const val ARROW_OPEN = "▴"
private const val ARROW_CLOSED = "▾"

/**
 * Frame of a card on the Experimental tab: a title, a line that says if the card changes the dose or only
 * signals, and a fold button. Tapping the title or the arrow folds the card open or closed. The content is only
 * built when the card is open, so a closed card does no work.
 *
 * @param title short title of the card
 * @param badge line under the title ("changes the dose" or "only signals")
 * @param extraLine optional second line under the title, for example the on/off state
 * @param trailing optional element between the title and the arrow, for example a switch. Tapping it does not fold the card.
 * @param startExpanded true when the card is open the first time
 */
@Composable
fun ExperimentKaart(
    title: String,
    badge: String,
    extraLine: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    startExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(startExpanded) }
    val headColor = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { expanded = !expanded }) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(badge, style = MaterialTheme.typography.labelSmall, color = headColor)
                    if (extraLine != null) {
                        Text(extraLine, style = MaterialTheme.typography.labelSmall, color = headColor)
                    }
                }
                trailing?.invoke()
                Text(
                    if (expanded) ARROW_OPEN else ARROW_CLOSED,
                    modifier = Modifier.clickable { expanded = !expanded }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = headColor
                )
            }
            if (expanded) content()
        }
    }
}
