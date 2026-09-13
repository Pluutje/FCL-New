package app.aaps.ui.compose.navigation

import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.source.DexcomBoyda
import app.aaps.core.interfaces.source.XDripSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Centralized check for whether an [app.aaps.core.ui.compose.navigation.ElementType] is available in the current configuration.
 *
 * "Available" means the system supports the action (e.g., the required BG source plugin is enabled).
 * Unavailable elements should be hidden from the UI — if they're never rendered, the route is never called.
 *
 * This is NOT about user preference toggles (e.g., "show carbs button") — those are separate.
 */
@SingleIn(AppScope::class)
@Inject
class ElementAvailability(
    private val xDripSource: XDripSource,
    private val dexcomBoyda: DexcomBoyda,
    private val activePlugin: ActivePlugin
) {

    fun isAvailable(elementType: ElementType): Boolean = when (elementType) {
        ElementType.CALIBRATION      -> xDripSource.isEnabled() || isCalibrationOverrideActive()
        ElementType.CGM_XDRIP        -> xDripSource.isEnabled()
        ElementType.CGM_DEX          -> dexcomBoyda.isEnabled()
        ElementType.FCL_TEMP_OVERRIDE -> isFclVNextActive()

        else                         -> true
    }

    /**
     * True only when FCLvNext is the active APS plugin. Same string-based check as
     * `NavigationRequest.Plugin`/`ElementNavigator` (13/09/2026, de gebruiker) — `ui` has no gradle
     * dependency on `plugins:aps`, so the FCLvNext class can't be imported here directly.
     */
    private fun isFclVNextActive(): Boolean =
        (activePlugin.activeAPS as? PluginBase)?.let { it::class.simpleName == "OpenAPSFCLPlugin" } ?: false

    /**
     * True when the active calibration plugin is a real (non-default) override.
     * Used to bypass the user-pref gate on the calibration button: when a real
     * calibration plugin is enabled, the dialog is the only way to feed it
     * fingerstick entries, so the button must remain visible.
     */
    fun isCalibrationOverrideActive(): Boolean {
        val plugin = activePlugin.activeCalibration as? PluginBase ?: return false
        return !plugin.pluginDescription.defaultPlugin
    }
}
