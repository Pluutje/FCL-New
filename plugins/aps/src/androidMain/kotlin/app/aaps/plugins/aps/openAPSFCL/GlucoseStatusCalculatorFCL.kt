package app.aaps.plugins.aps.openAPSFCL

import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.aps.openAPS.DeltaCalculator
import dev.zacsweers.metro.Inject

// Was @Reusable (Dagger) - Metro heeft geen equivalent ("Warning: Dagger's special-case @Reusable
// ... are not supported in Metro", zie de Metro-documentatie). @Reusable betekende toch al alleen
// "mag hergebruikt worden binnen dezelfde graaf, geen garantie" - geen echte singleton - dus een
// ongescopede @Inject constructor (nieuwe instantie per injectiepunt) is het dichtstbijzijnde
// equivalent, geen gedragswijziging die uitmaakt voor deze stateloze calculator.
class GlucoseStatusCalculatorFCL @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val iobCobCalculator: IobCobCalculator,
    private val dateUtil: DateUtil,
    private val deltaCalculator: DeltaCalculator
) {

    fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatusAutoIsf? {

        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return null
        if (data.isEmpty()) return null

        if (data[0].timestamp < dateUtil.now() - 7 * 60 * 1000L && !allowOldData)
            return null

        val now = data[0]
        val nowDate = now.timestamp

        if (data.size == 1) {
            return GlucoseStatusAutoIsf(
                glucose = now.recalculated,
                date = nowDate
            )
        }

        val deltaResult = deltaCalculator.calculateDeltas(data)

        return GlucoseStatusAutoIsf(
            glucose = now.recalculated,
            delta = deltaResult.delta,
            shortAvgDelta = deltaResult.shortAvgDelta,
            longAvgDelta = deltaResult.longAvgDelta,
            date = nowDate
        ).also {
            aapsLogger.debug(LTag.GLUCOSE, "FCL AutoISF GlucoseStatus=$it")
        }
    }
}

