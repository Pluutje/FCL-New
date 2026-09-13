package app.aaps.core.interfaces.overview

/**
 * Bridge for the FCLvNext "Tijdelijke aanpassing" (Temp Override) plugin-local setting
 * (11/09/2026, de gebruiker) — read by the Overview COB chip (see `CobChip`/`IobCobChips` in the `ui`
 * module) without the `ui` module depending on `plugins:aps` directly.
 *
 * `ui` and `plugins:aps` have no gradle dependency edge in either direction — this interface lives
 * in `core:interfaces`, which both already depend on, and each platform binds its own answer with
 * `@ContributesBinding`, exactly like `BgQualityCheck` does. See:
 * - `FclTempOverrideStatusProviderImpl` (androidMain, `plugins:aps`) — the real answer, wraps
 *   `FclTempOverrideSettings`.
 * - `IosTempOverrideStatusProvider` / `DesktopTempOverrideStatusProvider` — always inactive, because
 *   the FCLvNext plugin itself is Android-only and never runs there (same reasoning as
 *   `IosBgQualityCheck`/`DesktopBgQualityCheck`).
 */
interface TempOverrideStatusProvider {

    data class Snapshot(
        val active: Boolean,
        val targetPct: Int,
        val effectiveMul: Double,
        val remainingMinutes: Int
    )

    /**
     * Called from the Overview chips' existing ~2.5-minute ticker (see `ChipsViewModel.cobUiState`)
     * — a cheap SharedPreferences read plus a wall-clock comparison, not a heavy calculation, so no
     * separate polling loop is needed for this.
     */
    fun currentStatus(): Snapshot

    /**
     * Requests that FCLvNext's settings screen open with the "Tijdelijke aanpassing" section already
     * expanded, next time it opens (13/09/2026, de gebruiker) — the Treatments-sheet shortcut button
     * (`ui` module, via `ElementNavigator` in `appshell`) calls this right before navigating to the
     * FCLvNext plugin screen. Same cross-module bridge reasoning as [currentStatus] above: `appshell`
     * and `plugins:aps` have no gradle dependency edge, so the real one-shot flag
     * (mirrors `FclUpdateNotificationHelper.requestNavigate()`) has to live behind this interface.
     *
     * No-op by default: FCLvNext is Android-only (see class kdoc), so iOS/desktop never call this and
     * don't need to override it.
     */
    fun requestOpenSettings() {}
}
