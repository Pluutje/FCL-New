package app.aaps.desktop.shell.platform

import app.aaps.core.interfaces.overview.TempOverrideStatusProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Always inactive, because the FCLvNext plugin this feature belongs to is Android-only —
 * `FclTempOverrideSettings`/`FCLvNext.kt` live in `plugins:aps`' `androidMain` source set and never
 * build for desktop at all. Not grouped with the other classes in `DesktopNotPortedYet.kt`: those
 * are real AAPS features waiting to be moved to a shared source set, but there is nothing to port
 * here, since a desktop build never runs FCLvNext's dosing loop in the first place (same reasoning
 * as `IosTempOverrideStatusProvider` on the iOS side).
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class DesktopTempOverrideStatusProvider() : TempOverrideStatusProvider {

    override fun currentStatus(): TempOverrideStatusProvider.Snapshot =
        TempOverrideStatusProvider.Snapshot(active = false, targetPct = 100, effectiveMul = 1.0, remainingMinutes = -1)
}
