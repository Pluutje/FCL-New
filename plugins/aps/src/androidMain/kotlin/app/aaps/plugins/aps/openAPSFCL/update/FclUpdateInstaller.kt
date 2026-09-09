package app.aaps.plugins.aps.openAPSFCL.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.aaps.plugins.aps.GeneratedFclSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloadt de nieuwe apk en opent Android's eigen installatiebevestiging
 * (06/09/2026, de gebruiker). Nooit automatisch/stil — dit opent altijd
 * alleen de systeem-installer; de gebruiker moet daar zelf op "Installeren"
 * tikken.
 *
 * Hergebruikt de bestaande FileProvider uit app/src/main/AndroidManifest.xml
 * (authorities="${applicationId}.fileprovider") — die dekt via zijn
 * `cache-path path="."` in res/xml/filepaths.xml al de hele cache-map, dus
 * geen aparte provider of filepaths-wijziging nodig voor `cacheDir/updates/`.
 *
 * KRITIEK (valkuil uit het FCLGlucoLink-overdrachtsdocument, §7): de download
 * MOET binnen withContext(Dispatchers.IO) draaien. Zonder dat geeft Android
 * een NetworkOnMainThreadException zodra dit vanuit de UI-thread (bv. een
 * knop-klik) wordt aangeroepen.
 */
object FclUpdateInstaller {

    private const val CACHE_SUBDIR = "updates"
    private const val APK_FILE_NAME = "update.apk"

    sealed class Result {
        object LaunchedInstaller : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun downloadAndLaunchInstall(context: Context, fileId: String): Result =
        withContext(Dispatchers.IO) {
            try {
                val apiKey = GeneratedFclSecrets.FCL_UPDATE_DRIVE_API_KEY
                if (apiKey.isBlank()) return@withContext Result.Error("Update-checker niet geconfigureerd")

                val destination = File(File(context.cacheDir, CACHE_SUBDIR), APK_FILE_NAME)
                FclUpdateApi.downloadToFile(fileId, apiKey, destination)

                val apkUri: Uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", destination
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
                Result.LaunchedInstaller
            } catch (e: Exception) {
                Result.Error("${e::class.simpleName}: ${e.message ?: "(geen details)"}")
            }
        }
}
