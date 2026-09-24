package app.aaps.ui.compose.overview.chips

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.navigation.color
import app.aaps.core.ui.compose.navigation.icon
import app.aaps.core.ui.compose.navigation.label
import app.aaps.core.ui.compose.stringResourceOrNull

/**
 * @see CobChipPreview
 * @see CobChipZeroPreview
 * @see CobChipBlinkingPreview
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CobChip(
    state: CobUiState,
    showIcon: Boolean = true,
    modifier: Modifier = Modifier
) {
    // 11/09/2026 (de gebruiker) — Temp Override deelt dit chip-slot met COB en wint als beide
    // tegelijk gelden (zeldzaam: alleen als iemand zowel koolhydraten invoert als een override
    // heeft lopen). Zie kdoc bij TempOverrideStatusProvider.kt (core:interfaces) voor de aanleiding.
    val chipColor = if (state.tempOverrideActive) ElementType.TEMP_TARGET_MANAGEMENT.color() else ElementType.COB.color()
    val chipIcon = if (state.tempOverrideActive) ElementType.TEMP_TARGET_MANAGEMENT.icon() else ElementType.COB.icon()
    val chipText = if (state.tempOverrideActive) state.tempOverrideText else state.text

    // When carbs are required, flash only the icon (attention cue) and let the text scroll
    // (basicMarquee) instead of wrapping — so the numbers stay crisp/readable while the icon blinks.
    // Niet relevant tijdens een actieve Temp Override (carbsReq gaat over COB, niet over de override).
    val iconAlphaModifier = if (state.carbsReq > 0 && !state.tempOverrideActive) {
        val infiniteTransition = rememberInfiniteTransition(label = "cobBlink")
        val alphaState = infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "cobAlpha"
        )
        Modifier.graphicsLayer { alpha = alphaState.value }
    } else {
        Modifier
    }

    val hasValue = state.tempOverrideActive || state.cobValue != 0.0
    // Name the whole chip, not the icon. state.text is only a bare value like "45 g", which says
    // nothing about what it measures. It goes on the Surface because the icon is dropped when the
    // row is too narrow (see IobCobChips) and the label would go with it.
    //
    // Only the noun belongs here, never the value - a description on a merging node is added
    // beside the children rather than replacing them, so repeating the value says it twice.
    // See ChipAnnouncementTest.
    //
    // 24/09/2026 — moet meebewegen met chipColor/chipIcon/chipText hierboven: tijdens een actieve
    // Temp Override toont dit chip geen COB, dus de accessibility-naam moet ook "Temp Target" zijn
    // i.p.v. "COB", anders noemt een screenreader de verkeerde naam.
    val chipDescription = stringResourceOrNull(
        if (state.tempOverrideActive) ElementType.TEMP_TARGET_MANAGEMENT.label() else ElementType.COB.label()
    ) ?: ""
    Surface(
        shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
        color = if (hasValue) chipColor.copy(alpha = 0.2f) else Color.Transparent,
        modifier = modifier
            .heightIn(min = AapsSpacing.chipHeight)
            // This chip has no onClick, so unlike its siblings it does not merge on its own.
            .semantics(mergeDescendants = true) { contentDescription = chipDescription }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)
        ) {
            if (showIcon) {
                Icon(
                    imageVector = chipIcon,
                    // Decorative: the Surface above names the chip and carries the value.
                    contentDescription = null,
                    tint = chipColor,
                    modifier = Modifier
                        .size(AapsSpacing.chipIconSize)
                        .then(iconAlphaModifier)
                )
            }
            Text(
                text = chipText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = if (showIcon) AapsSpacing.medium else 0.dp)
                    .basicMarquee()
            )
        }
    }
}
