package app.aaps.ios.shell.platform

import app.aaps.core.interfaces.overview.TempOverrideStatusProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Always inactive, because the FCLvNext plugin this feature belongs to is Android-only —
 * `FclTempOverrideSettings`/`FCLvNext.kt` live in `plugins:aps`' `androidMain` source set and never
 * build for iOS at all. This is not a placeholder waiting for a port: there is nothing to port,
 * since a client-only iOS build never runs FCLvNext's dosing loop in the first place (same
 * reasoning as [IosBgQualityCheck] a few lines up in this directory).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosTempOverrideStatusProvider() : TempOverrideStatusProvider {

    override fun currentStatus(): TempOverrideStatusProvider.Snapshot =
        TempOverrideStatusProvider.Snapshot(active = false, targetPct = 100, effectiveMul = 1.0, remainingMinutes = -1)
}
