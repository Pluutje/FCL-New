package app.aaps.core.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints

/** Slot ids for the two mutually-exclusive contents [CrashSafeContent] can subcompose. */
private enum class CrashSafeSlot { Content, Fallback }

/**
 * Isolates exceptions thrown while composing [content], so they cannot crash the whole app —
 * [fallback] is shown instead (15/09/2026, de gebruiker: this backs the "alternatief hoofdscherm"
 * toggle, which makes an optional plugin screen the app's real, always-used home screen; a bug in
 * that screen must not be able to take down the whole app).
 *
 * HOW IT WORKS: [SubcomposeLayout] lets us call [content] ourselves, inside a `try`/`catch`, instead
 * of letting Compose's normal composition machinery call it. If composing (or measuring) it throws,
 * we subcompose [fallback] instead and use that.
 *
 * KNOWN LIMITATION (be aware of this — it is not a 100% guarantee): this `try`/`catch` only runs
 * when [SubcomposeLayout] re-subcomposes [content], which happens on the first composition and on
 * every later remeasure. A change that only invalidates *drawing* (not layout/size) can, in rare
 * cases, recompose a child further down *without* going through our `try`/`catch` — so a crash
 * confined entirely to that path could still, in theory, escape this boundary. In practice, this
 * screen's own state changes (new BG values, new graph points, changing pill text) almost always
 * affect layout somewhere and so are caught. There is no fully bullet-proof way to catch every
 * possible Compose exception from inside Compose itself; this is the same best-effort technique
 * used elsewhere in the Compose community for this problem, not a perfect guarantee.
 */
@Composable
fun CrashSafeContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    fallback: @Composable () -> Unit
) {
    SubcomposeLayout(modifier) { constraints: Constraints ->
        val placeables = try {
            subcompose(CrashSafeSlot.Content, content).map { it.measure(constraints) }
        } catch (e: Exception) {
            subcompose(CrashSafeSlot.Fallback, fallback).map { it.measure(constraints) }
        }
        // Size to the incoming (bounded) constraints when available, not just to the content's own
        // natural size — otherwise a child that relies on getting a bounded height from its parent
        // to work (e.g. Modifier.verticalScroll(), which needs a finite viewport height to know how
        // much to clip/scroll) never gets one, and the screen either overflows or renders too small
        // instead of filling the space it was given.
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
            else placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight
            else placeables.maxOfOrNull { it.height } ?: constraints.minHeight
        layout(width, height) {
            placeables.forEach { it.placeRelative(0, 0) }
        }
    }
}
