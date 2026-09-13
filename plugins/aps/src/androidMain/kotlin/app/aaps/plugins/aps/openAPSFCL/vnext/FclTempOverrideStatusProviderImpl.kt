package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
import app.aaps.core.interfaces.overview.TempOverrideStatusProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Real (Android) answer for [TempOverrideStatusProvider] (11/09/2026, de gebruiker) — a thin
 * wrapper around [FclTempOverrideSettings] so the Overview chips row (`ui` module) can show the
 * Temp Override status without `ui` depending on `plugins:aps` directly. See the kdoc on
 * [TempOverrideStatusProvider] for the full reasoning and the iOS/desktop stub siblings.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class FclTempOverrideStatusProviderImpl(
    private val context: Context
) : TempOverrideStatusProvider {

    override fun currentStatus(): TempOverrideStatusProvider.Snapshot {
        val s = FclTempOverrideSettings.status(context, System.currentTimeMillis())
        return TempOverrideStatusProvider.Snapshot(
            active = s.active,
            targetPct = s.targetPct,
            effectiveMul = s.effectiveMul,
            remainingMinutes = s.remainingMinutes
        )
    }

    override fun requestOpenSettings() {
        FclTempOverrideSettings.requestOpenSettings()
    }
}
