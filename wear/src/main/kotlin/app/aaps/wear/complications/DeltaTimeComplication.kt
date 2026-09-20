package app.aaps.wear.complications

import android.app.PendingIntent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.weardata.EventData
import dev.zacsweers.metro.HasMemberInjections

/**
 * Delta + Time Complication
 *
 * Shows only the delta and the time since the last reading, with no BG value and no arrow.
 * Display format: "3m" (auto-updating, above) with "+0.5" (delta, below).
 *
 * 20/09/2026 (de gebruiker) — losse, kleine complicatie voor naast de grote Bg-weergave (zie
 * [SgvLargeComplication]), die de waarde en de pijl al toont. Dit dekt het gat dat het
 * verwijderen van GlucoseDataHandler achterliet: GDH toonde in een klein rondje naast de grote
 * Bg-waarde precies dit (tijd + delta), en dat bleek nergens in AAPS te bestaan — [SgvComplication]
 * komt qua inhoud het dichtst in de buurt maar toont ook de waarde+pijl (overbodig naast de grote
 * cirkel) en is bovendien beperkt tot AAPS's eigen wfs/cwf-watchfaces (zie SAFE_WATCH_FACES in
 * AndroidManifest.xml) — dat is precies waarom "SGV" niet als keuze verscheen in de picker van
 * de gebruiker's (niet-AAPS) watchface. Deze complicatie heeft bewust GEEN SAFE_WATCH_FACES-
 * restrictie, net als [SgvLargeComplication], zodat hij wel in willekeurige watchfaces van
 * derden (bv. DMM16) gekozen kan worden.
 */
@HasMemberInjections
open class DeltaTimeComplication : ModernBaseComplicationProviderService() {

    override fun buildComplicationData(
        type: ComplicationType,
        data: app.aaps.wear.data.ComplicationData,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? {
        val bgData = data.bgData
        aapsLogger.debug(LTag.WEAR, "DeltaTimeComplication building: delta=${bgData.delta}")

        return when (type) {
            ComplicationType.SHORT_TEXT -> buildShortTextComplication(bgData, complicationPendingIntent)
            else                        -> {
                aapsLogger.warn(LTag.WEAR, "DeltaTimeComplication unexpected type: $type")
                null
            }
        }
    }

    private fun buildShortTextComplication(
        bgData: EventData.SingleBg,
        pendingIntent: PendingIntent
    ): ShortTextComplicationData {
        // Titel = alleen de tikkende tijd (geen suffix), tekst = alleen de delta -- precies de
        // twee regels uit het GDH-screenshot van de gebruiker, zonder waarde/pijl (die staan al
        // in de grote cirkel).
        val titleText = buildCountUpText(bgData.timeStamp, "^1")

        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text = bgData.delta).build(),
            contentDescription = PlainComplicationText.Builder(text = "Delta ${bgData.delta}").build()
        )
            .setTitle(titleText)
            .setTapAction(pendingIntent)
            .build()
    }

    override fun getComplicationAction(): ComplicationAction = ComplicationAction.BG_GRAPH

    override fun getProviderCanonicalName(): String = DeltaTimeComplication::class.java.canonicalName!!
}
