package app.aaps.core.interfaces.aps

import app.aaps.core.interfaces.profile.Profile

interface APS {

    /**
     * Algorithm used
     */
    val algorithm: APSResult.Algorithm

    /**
     * Result of last invocation
     */
    val lastAPSResult: APSResult?

    /**
     * Timestamp of last invocation
     */
    val lastAPSRun: Long

    /**
     * Is APS actually using variable ISF calculation?
     * @return true if yes
     */
    fun usingDynamicIsf(): Boolean = false

    /**
     * Does this algorithm offer the "Use dynamic sensitivity" option at all
     * (static capability, independent of the preference value)?
     * Unlike [usingDynamicIsf] this does not depend on whether the user enabled it.
     * @return true if yes
     */
    fun offersDynamicSensitivity(): Boolean = false

    /**
     * Is APS providing variable IC calculation?
     * @return true if yes
     */
    fun supportsDynamicIc(): Boolean = false

    /**
     * Dedicated string for Sensitivity OKDialog in overview on ISF calculation ?
     * @return string or null if nothing to show
     */
    fun getSensitivityOverviewString(): String? = null

    /**
     * Calculate current ISF
     * @param profile Actual profile to get multiplier form [ProfileSealed.EPS]
     * @param caller Caller identification for logging purposes
     * @return isf or null if not available
     *
     * Remember calculation must be as fast as possible. It's called very often
     */
    fun getIsfMgdl(profile: Profile, caller: String): Double? = error("Not implemented")

    /**
     * Calculate ISF to specified timestamp
     * @param timestamp time
     * @param caller Caller identification for logging purposes
     * @return isf or null if not available
     *
     * Remember calculation must be as fast as possible. It's called very often
     */
    fun getAverageIsfMgdl(timestamp: Long, caller: String): Double? = error("Not implemented")

    /**
     * Calculate current IC
     * @param profile Actual profile to get multiplier form [ProfileSealed.EPS]
     * @return ic or null if not available
     */
    fun getIc(profile: Profile): Double? = error("Not implemented")

    /**
     * Calculate IC to specified timestamp
     * @param timestamp time
     * @param profile Actual profile to get multiplier form [ProfileSealed.EPS]
     * @return ic or null if not available
     */
    fun getIc(timestamp: Long, profile: Profile): Double? = error("Not implemented")

    /**
     * Is plugin enabled?
     * Overlap with [PluginBase::isEnabled] to avoid type conversion
     */
    fun isEnabled(): Boolean

    /**
     * Invoke algorithm
     * @param initiator caller
     * @param tempBasalFallback if true previous enact of SMB failed. Try calculation without SMB
     */
    suspend fun invoke(initiator: String, tempBasalFallback: Boolean)

    /**
     * Provide glucose status calculation
     * @param allowOldData if true non current data will be allowed
     * @return [GlucoseStatus]
     */
    fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus?

    /**
     * Optional replacement for the standard overview screen content, chosen by the active APS
     * plugin (15/09/2026, de gebruiker — "alternatief hoofdscherm" toggle for FCLvNext).
     *
     * Typed `Any?` instead of a real UI type on purpose: `core:interfaces` cannot depend on
     * `core:ui` (where the actual content-wrapper type lives), the same module-boundary reason
     * `PluginBase.getComposeContent()` also returns `Any?`. The `ui` module reads this property,
     * casts it back to its own wrapper type, and renders it (inside a crash-safe boundary) instead
     * of the standard overview content when it is non-null.
     *
     * Default `null` means every other APS plugin, on every platform, is completely unaffected.
     */
    val overviewOverride: Any? get() = null
}