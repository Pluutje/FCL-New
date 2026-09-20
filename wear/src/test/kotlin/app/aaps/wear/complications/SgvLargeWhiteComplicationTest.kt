package app.aaps.wear.complications

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import app.aaps.wear.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class SgvLargeWhiteComplicationTest {

    private fun sut(): SgvLargeWhiteComplication =
        Robolectric.buildService(SgvLargeWhiteComplication::class.java).get().also { it.aapsLogger = AAPSLoggerTest() }

    private fun pendingIntent(sut: SgvLargeWhiteComplication): PendingIntent =
        PendingIntent.getActivity(sut, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)

    @Test
    fun `preview builds a small-image complication`() {
        val data = sut().getPreviewData(ComplicationType.SMALL_IMAGE)

        assertThat(data).isInstanceOf(SmallImageComplicationData::class.java)
    }

    @Test
    fun `preview builds a photo-image complication`() {
        val data = sut().getPreviewData(ComplicationType.PHOTO_IMAGE)

        assertThat(data).isInstanceOf(PhotoImageComplicationData::class.java)
    }

    // 20/09/2026 (de gebruiker) -- het hele bestaansrecht van deze klasse: altijd wit, ongeacht
    // Bg-niveau, in tegenstelling tot SgvLargeComplication zelf.
    @Test
    fun `the bitmap color is always white, regardless of glucose level`() {
        val sut = sut()

        assertThat(sut.bitmapColor(sgvLevel = 1L)).isEqualTo(Color.WHITE)
        assertThat(sut.bitmapColor(sgvLevel = 0L)).isEqualTo(Color.WHITE)
        assertThat(sut.bitmapColor(sgvLevel = -1L)).isEqualTo(Color.WHITE)
    }

    @Test
    fun `the provider canonical name identifies this complication`() {
        assertThat(sut().getProviderCanonicalName()).contains("SgvLargeWhiteComplication")
    }
}
