package app.aaps.plugins.aps.openAPSFCL.update

import android.app.NotificationChannel
import android.app.NotificationManager as AndroidNotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationHandle
import app.aaps.plugins.aps.openAPSFCL.vnext.FclNotificationManagerBridge
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * ── FCLvNext Update-melding (08/09/2026, de gebruiker) ──────────────────────
 * Zelfde tweeledige patroon als FclAiNotificationHelper.kt: een native
 * AAPS-notificatie (zichtbaar zodra de app open is) plus een Android-
 * systeemnotificatie (vangnet als de app dicht is). Beide verschijnen zodra
 * FclUpdateScheduler een nieuwere versie vindt, en verdwijnen weer zodra een
 * volgende check UpToDate/Error/NotConfigured oplevert, of zodra de
 * gebruiker de "Updates"-sectie in FCLSettingsScreen daadwerkelijk opent.
 *
 * Tik op de melding → handleNotificationAction() in ComposeMainActivity.kt
 * navigeert naar de FCL-plugin (zelfde route als FCL_AI_ADVISOR_READY);
 * consumeNavigateRequest() wordt door FCLComposeContent.kt gelezen om
 * meteen op het Settings-tabblad te starten mét opengeklapte Updates-sectie,
 * i.p.v. het gebruiker zelf te laten zoeken.
 */
object FclUpdateNotificationHelper {

    // ── Android-systeemnotificatie ──────────────────────────────────────────
    private const val CHANNEL_ID   = "fcl_update"
    private const val CHANNEL_NAME = "FCLvNext Updates"
    private const val NOTIF_ID     = 0x46434C55   // "FCLU" als int

    // ── Native AAPS-notificatie ──────────────────────────────────────────────
    private val nativeHandle = AtomicReference<NotificationHandle?>(null)
    private val navigateRequested = AtomicBoolean(false)

    /** Aanroepen door FclUpdateScheduler na een check met resultaat UpdateAvailable. */
    fun showUpdateAvailable(context: Context, versionCode: Int) {
        showNativeAapsNotification(versionCode)
        showAndroidSystemNotification(context, versionCode)
    }

    /** Aanroepen door FclUpdateScheduler zodra een check UpToDate/Error/NotConfigured
     *  oplevert, en door FCLSettingsScreen zodra de gebruiker de Updates-sectie opent. */
    fun dismissUpdateNotice(context: Context) {
        FclNotificationManagerBridge.get()?.let { nm ->
            nativeHandle.getAndSet(null)?.let { handle -> nm.dismiss(handle) }
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager
        nm.cancel(NOTIF_ID)
    }

    /**
     * Aanroepen door FCLComposeContent bij het openen. Retourneert true (en
     * reset meteen naar false) als de gebruiker via de melding hier
     * naartoe wilde springen.
     */
    fun consumeNavigateRequest(): Boolean = navigateRequested.getAndSet(false)

    /** Aanroepen door handleNotificationAction() in ComposeMainActivity.kt zodra
     *  de gebruiker daadwerkelijk op de update-melding heeft getikt — anders dan
     *  bij FCL_AI_ADVISOR_READY (die de vlag via een aparte actieknop zet) heeft
     *  deze melding geen actieknop, dus zetten we de vlag hier, op het enige
     *  punt dat we zeker weten dat de tik plaatsvond. */
    fun requestNavigate() {
        navigateRequested.set(true)
    }

    private fun showNativeAapsNotification(versionCode: Int) {
        val nm = FclNotificationManagerBridge.get() ?: return

        // Vorige instantie eerst opruimen zodat er nooit twee tegelijk staan.
        nativeHandle.getAndSet(null)?.let { handle -> nm.dismiss(handle) }

        val handle = nm.post(
            id = NotificationId.FCL_UPDATE_AVAILABLE,
            text = "FCLvNext-update beschikbaar (versionCode $versionCode)",
            actions = listOf()
        )
        // Klik op de melding zelf navigeert al via handleNotificationAction()
        // in ComposeMainActivity.kt (zelfde patroon als FCL_AI_ADVISOR_READY) —
        // die zet daar de navigatievlag; hier hoeft geen actieknop bij.
        navigateRequested.set(false)
        nativeHandle.set(handle)
    }

    private fun showAndroidSystemNotification(context: Context, versionCode: Int) {
        ensureChannel(context)

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
            ?: Intent()

        val pi = PendingIntent.getActivity(
            context,
            NOTIF_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("🔄 FCLvNext-update beschikbaar")
            .setContentText("Tik om versionCode $versionCode te bekijken en te installeren.")
            .setAutoCancel(true)               // verdwijnt bij tikken
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)            // geen herhaald geluid bij updates
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // Paars accent (FCLvNext huisstijl)
            .setColor(0xFF5B3A8E.toInt())
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager
        nm.notify(NOTIF_ID, notif)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            AndroidNotificationManager.IMPORTANCE_DEFAULT   // geen geluid, wel statusbalk-icoon
        ).apply {
            description = "Informeert je als er een nieuwere FCLvNext-versie klaarstaat."
            enableVibration(false)
            setSound(null, null)                     // stil — niet urgent
        }
        nm.createNotificationChannel(channel)
    }
}
