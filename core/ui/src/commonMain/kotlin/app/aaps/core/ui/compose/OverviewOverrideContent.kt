package app.aaps.core.ui.compose

import androidx.compose.runtime.Composable

/**
 * Optional replacement content for the standard overview (home) screen, contributed by the active
 * APS plugin (15/09/2026, de gebruiker — "alternatief hoofdscherm" toggle for FCLvNext) via
 * `app.aaps.core.interfaces.aps.APS.overviewOverride`.
 *
 * Deliberately much smaller than [ComposablePluginContent]: this content becomes the app's home
 * screen itself (shown directly on launch, no push navigation to reach it), so it has no toolbar
 * to configure and no "back" destination, unlike a plugin screen reached through a nav route.
 *
 * A plain `@Composable () -> Unit` type alias rather than a `fun interface` — a SAM-converted
 * lambda on a `fun interface` whose single abstract method is `@Composable` is a known-fragile
 * combination with the Compose compiler plugin. A composable function-type alias is the standard,
 * well-supported way to pass a "content slot" in Compose.
 *
 * Rendered inside [CrashSafeContent] by `app.aaps.ui.compose.overview.OverviewScreen`, so an
 * exception here falls back to the standard overview screen instead of crashing the app.
 */
typealias OverviewOverrideContent = @Composable () -> Unit
