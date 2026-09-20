package app.aaps.wear.complications

import android.graphics.Color

/**
 * SGV Large Complication (White)
 *
 * 20/09/2026 (de gebruiker) — identiek aan [SgvLargeComplication] (grote Bg-waarde met trendpijl
 * erboven, als afbeelding voor slots die alleen afbeelding-types accepteren), maar zonder kleur
 * naar Bg-niveau: altijd wit. Aanleiding: op sommige watchfaces (o.a. DMM16) valt de gekleurde
 * variant qua stijl niet mooi — GlucoseDataHandler biedt om diezelfde reden ook een losse witte
 * variant naast de gekleurde. Alleen de kleur verschilt; tekst, lay-out en pijl zijn hetzelfde.
 *
 * Alleen als afbeelding geregistreerd (zie AndroidManifest.xml, SUPPORTED_TYPES zonder
 * SHORT_TEXT) — voor SHORT_TEXT-slots bepaalt de watchface zelf al de tekstkleur, daar bestaat
 * dit "te fel gekleurd"-probleem niet en is [SgvLargeComplication] zelf al voldoende.
 */
class SgvLargeWhiteComplication : SgvLargeComplication() {

    internal override fun bitmapColor(sgvLevel: Long): Int = Color.WHITE

    override fun getProviderCanonicalName(): String = SgvLargeWhiteComplication::class.java.canonicalName!!
}
