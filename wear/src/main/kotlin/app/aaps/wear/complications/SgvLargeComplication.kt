package app.aaps.wear.complications

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.R
import dev.zacsweers.metro.HasMemberInjections

/**
 * SGV Large Complication
 *
 * Shows BG value as large as possible with trend and auto-updating age.
 * Display format: "6.8" (large) with "3m ↗" above
 * - No delta — trend arrow conveys direction, age conveys freshness
 * - Time auto-updates every minute (battery efficient)
 *
 * 20/09/2026 (de gebruiker) — ook als afbeelding (SMALL_IMAGE/PHOTO_IMAGE), niet meer alleen als
 * SHORT_TEXT. Aanleiding: een aantal watchfaces (o.a. DMM16, github.com/sderaps/DMM) reserveren
 * hun grootste complicatieslot uitsluitend voor afbeelding-type complicaties — dezelfde reden
 * waarom daar al [BgGraphComplication] (SMALL_IMAGE,LARGE_IMAGE) en [RunningModeComplication]
 * (SMALL_IMAGE,ICON) wél in pasten, en deze niet. Zonder een eigen AAPS-complicatie in die
 * slot-vorm was daarvoor een losse app (GlucoseDataHandler) nodig, uitsluitend om een grote
 * Bg-weergave op zo'n gezicht te krijgen. Werkt op elke Wear OS-versie die complicaties
 * ondersteunt — in tegenstelling tot Watch Face Push (zie `WatchFacePushHelper`, min. Wear OS 6,
 * dus niet bruikbaar op oudere horloges) is hier geen nieuwere Wear OS-versie voor nodig.
 *
 * De inhoud (waarde + pijl) blijft identiek aan de SHORT_TEXT-tak hieronder — alleen de manier
 * van tonen (zelf een bitmap tekenen i.p.v. systeem-tekst) verschilt, plus een kleur naar
 * Bg-niveau (zelfde drie kleuren als de SimpleUi-watchface's `getBgColour`, R.color.dark_*Color).
 */
@HasMemberInjections
open class SgvLargeComplication : ModernBaseComplicationProviderService() {

    override fun buildComplicationData(
        type: ComplicationType,
        data: app.aaps.wear.data.ComplicationData,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? {
        val bgData = data.bgData
        aapsLogger.debug(LTag.WEAR, "SgvLargeComplication building: sgv=${bgData.sgvString} arrow=${bgData.slopeArrow}")

        return when (type) {
            ComplicationType.SHORT_TEXT  -> buildShortTextComplication(bgData, complicationPendingIntent)
            ComplicationType.SMALL_IMAGE -> buildSmallImageComplication(bgData, complicationPendingIntent)
            ComplicationType.PHOTO_IMAGE -> buildPhotoImageComplication(bgData, complicationPendingIntent)
            else                         -> {
                aapsLogger.warn(LTag.WEAR, "SgvLargeComplication unexpected type: $type")
                null
            }
        }
    }

    private fun buildShortTextComplication(
        bgData: EventData.SingleBg,
        pendingIntent: PendingIntent
    ): ShortTextComplicationData {
        val titleText = buildCountUpText(bgData.timeStamp, "^1 ${bgData.slopeArrow}︎")

        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text = bgData.sgvString).build(),
            contentDescription = PlainComplicationText.Builder(text = "Glucose ${bgData.sgvString}").build()
        )
            .setTitle(titleText)
            .setTapAction(pendingIntent)
            .build()
    }

    // 20/09/2026 (de gebruiker) -- LONG_TEXT hier bewust weer verwijderd. Op een watchface die
    // LONG_TEXT voor deze slot gebruikt, rendert het systeem titel+tekst samen in het normale,
    // kleine LONG_TEXT-lettertype, vlak tegen de grote Bg-waarde aan -- niet los ernaast en niet
    // groot, dus precies het "te klein lettertype, tegen de Bg aangeschoven" resultaat dat de
    // gebruiker afkeurde. Leeftijd+delta losstaand naast (niet IN) deze complicatie horen nu bij
    // [DeltaTimeComplication], een eigen, losse complicatie voor een eigen slot. Deze klasse blijft
    // puur voor de grote waarde: SHORT_TEXT voor tekst-slots, SMALL_IMAGE/PHOTO_IMAGE (zelf
    // getekend, groot lettertype) voor afbeelding-slots.
    private fun buildSmallImageComplication(
        bgData: EventData.SingleBg,
        pendingIntent: PendingIntent
    ): SmallImageComplicationData {
        val icon = Icon.createWithBitmap(renderSgvBitmap(bgData, SMALL_IMAGE_PX))
        return SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(image = icon, type = SmallImageType.PHOTO).build(),
            contentDescription = buildContentDescription(bgData)
        )
            .setTapAction(pendingIntent)
            .build()
    }

    private fun buildPhotoImageComplication(
        bgData: EventData.SingleBg,
        pendingIntent: PendingIntent
    ): PhotoImageComplicationData {
        val icon = Icon.createWithBitmap(renderSgvBitmap(bgData, LARGE_IMAGE_PX))
        return PhotoImageComplicationData.Builder(
            photoImage = icon,
            contentDescription = buildContentDescription(bgData)
        )
            .setTapAction(pendingIntent)
            .build()
    }

    private fun buildContentDescription(bgData: EventData.SingleBg): PlainComplicationText =
        PlainComplicationText.Builder(text = "Glucose ${bgData.sgvString} ${bgData.slopeArrow} ${bgData.delta}").build()

    // Bg-waarde + trendpijl als plaatje, voor watchface-slots die alleen afbeelding-complicaties
    // accepteren (zie kdoc hierboven). Pijl boven de waarde, waarde zo groot mogelijk — "een
    // grote Bg met daarboven de trendpijl", precies zoals gevraagd. Transparante achtergrond: de
    // watchface erachter bepaalt de kleur, net als BgGraphComplication doet.
    //
    // 20/09/2026 (de gebruiker) -- leeftijd+delta hier NIET meer mee-tekenen. Eerdere versie
    // tekende die als kleine tekst rechts in ditzelfde plaatje (zoals GDH deed), maar dat gaf twee
    // problemen: (1) een statische bitmap kan de klok niet zelf laten meetellen zoals GDH's live
    // tikkende tijd wel deed, en (2) alles in EEN afbeelding proppen betekent noodgedwongen een
    // klein lettertype vlak tegen de grote waarde aan. Leeftijd+delta horen nu bij de losse
    // [DeltaTimeComplication], die zijn eigen slot krijgt en via de systeem-eigen
    // TimeDifferenceComplicationText wel elke minuut live tikt.
    private fun renderSgvBitmap(bgData: EventData.SingleBg, sizePx: Int): Bitmap {
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val bgColor = bitmapColor(bgData.sgvLevel)

        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.34f // 20/09/2026 (de gebruiker) -- een maatje groter (was 0.28f)
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = sizePx * 0.42f
        }

        val centerX = sizePx * 0.5f
        canvas.drawText(bgData.slopeArrow, centerX, sizePx * 0.36f, arrowPaint)
        canvas.drawText(bgData.sgvString, centerX, sizePx * 0.80f, valuePaint)

        return bitmap
    }

    // Zelfde drie kleuren/indeling als SimpleUi.kt's private getBgColour — hier gedupliceerd
    // omdat die niet gedeeld is, bewust geen nieuwe afhankelijkheid tussen die twee losse
    // complicatie-/watchface-klassen voor 3 regels kleurlogica.
    //
    // 20/09/2026 (de gebruiker) -- open (en internal, i.p.v. protected, zodat de test dit direct
    // kan aanroepen) gemaakt zodat SgvLargeWhiteComplication dit kan overschrijven: sommigen
    // vinden de kleur op hun watchface niet mooi (GDH biedt om die reden ook een losse witte
    // variant naast de gekleurde) -- zie kdoc bij SgvLargeWhiteComplication.
    internal open fun bitmapColor(sgvLevel: Long): Int =
        when (sgvLevel) {
            1L   -> ContextCompat.getColor(this, R.color.dark_highColor)
            0L   -> ContextCompat.getColor(this, R.color.dark_midColor)
            else -> ContextCompat.getColor(this, R.color.dark_lowColor)
        }

    override fun getComplicationAction(): ComplicationAction = ComplicationAction.BG_GRAPH

    override fun getProviderCanonicalName(): String = SgvLargeComplication::class.java.canonicalName!!

    companion object {
        private const val SMALL_IMAGE_PX = 96
        private const val LARGE_IMAGE_PX = 320
    }
}
