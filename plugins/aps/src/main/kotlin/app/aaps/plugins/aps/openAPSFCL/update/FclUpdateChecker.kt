package app.aaps.plugins.aps.openAPSFCL.update

import app.aaps.plugins.aps.BuildConfig
import app.aaps.plugins.aps.openAPSFCL.vnext.FCL_STATUS_VERSION

/**
 * FCLvNext update-checker — leest de Drive-map en vergelijkt met de
 * geïnstalleerde versie (06/09/2026, de gebruiker; patroon overgenomen uit
 * het FCLGlucoLink-overdrachtsdocument).
 *
 * Bestandsnaamconventie (het hele mechanisme leunt hierop):
 *  - `FCL-V7_v<versienummer>.apk` — <versienummer> is FCL_STATUS_VERSION
 *    (zie FCLvNextStatusFormatter.kt), NIET het Android-versionCode uit
 *    Versions.kt (dat is sinds v98 bevroren, zie kdoc daar) en NIET de
 *    vrije-tekst versionName.
 *  - `FCL-V7_v<versienummer>_whatsnew.txt` — optioneel, zie FclWhatsNewChecker.
 *  - Geen datumcontrole (Drive's "laatst gewijzigd" verandert ook zonder een
 *    echte nieuwe versie) en geen downloaden van de apk zelf om 'm te
 *    controleren — puur de bestandsnaam bevat het versienummer. Bij meerdere
 *    (oude, niet-opgeruimde) builds in de map wordt altijd de HOOGSTE
 *    gevonden waarde gebruikt.
 *
 * BuildConfig.FCL_UPDATE_DRIVE_FOLDER_ID/FCL_UPDATE_DRIVE_API_KEY komen uit
 * `local.properties` (zie plugins/aps/build.gradle.kts) — nooit in broncode.
 * Zijn ze leeg (niet ingesteld), dan geeft checkForUpdate() gewoon
 * NotConfigured terug: geen crash, de rest van de plugin werkt door.
 *
 * Bewust een sealed class i.p.v. nullable: een netwerkfout mag nooit per
 * ongeluk als "geen update" behandeld worden.
 */
object FclUpdateChecker {

    /** Regex exact zoals in het FCLGlucoLink-overdrachtsdocument: underscore, v/V, dan alleen cijfers. */
    private val VERSION_REGEX = Regex("""_[vV](\d+)""")
    private const val APK_PREFIX = "FCL-V7"

    sealed class Result {
        data class UpdateAvailable(
            val versionCode: Int,
            val fileId: String,
            val fileName: String
        ) : Result()

        data class UpToDate(val currentVersionCode: Int) : Result()
        object NotConfigured : Result()
        data class Error(val message: String) : Result()
    }

    /** Eén regel in de "Versie wijzigen"-lijst (07/09/2026, de gebruiker). */
    data class VersionEntry(
        val versionCode: Int,
        val fileId: String,
        val fileName: String
    )

    sealed class VersionListResult {
        data class Success(val versions: List<VersionEntry>) : VersionListResult()
        object NotConfigured : VersionListResult()
        data class Error(val message: String) : VersionListResult()
    }

    /** BLOCKING — aanroeper moet dit binnen withContext(Dispatchers.IO) draaien.
     *  07/09/2026, de gebruiker: vergelijkt tegen FCL_STATUS_VERSION i.p.v.
     *  het (nu bevroren) Android-versionCode — zie kdoc bij die constante in
     *  FCLvNextStatusFormatter.kt. Geen Context meer nodig, dus niet meer
     *  als parameter (was alleen voor PackageManager). */
    fun checkForUpdate(): Result {
        val folderId = BuildConfig.FCL_UPDATE_DRIVE_FOLDER_ID
        val apiKey = BuildConfig.FCL_UPDATE_DRIVE_API_KEY
        if (folderId.isBlank() || apiKey.isBlank()) return Result.NotConfigured

        val currentVersionCode = FCL_STATUS_VERSION

        return try {
            val files = FclUpdateApi.listFiles(folderId, apiKey)
            val newest = files
                .filter { it.name.startsWith(APK_PREFIX) && it.name.endsWith(".apk") }
                .mapNotNull { f ->
                    VERSION_REGEX.find(f.name)?.groupValues?.get(1)?.toIntOrNull()?.let { vc -> f to vc }
                }
                .maxByOrNull { (_, vc) -> vc }

            when {
                newest == null -> Result.Error("Geen geldig FCL-V7_v<versionCode>.apk bestand gevonden in de map")
                newest.second > currentVersionCode -> Result.UpdateAvailable(
                    versionCode = newest.second,
                    fileId = newest.first.id,
                    fileName = newest.first.name
                )
                else -> Result.UpToDate(currentVersionCode)
            }
        } catch (e: Exception) {
            Result.Error("${e::class.simpleName}: ${e.message ?: "(geen details)"}")
        }
    }

    /** Alle geldige FCL-V7_v<versionCode>.apk-bestanden in de Drive-map,
     *  aflopend gesorteerd op versionCode (07/09/2026, de gebruiker —
     *  "versie wijzigen"/terugzetten). Zelfde bestandsnaam-parsing als
     *  checkForUpdate() hierboven, maar zonder de "nieuwer dan huidige"-
     *  vergelijking: de UI laat de gebruiker zelf kiezen (nieuwste als
     *  default), dus ook expliciet terugzetten naar een oudere versie moet
     *  hier gewoon in de lijst staan. Hoeveel versies daadwerkelijk
     *  beschikbaar zijn, bepaalt de gebruiker zelf door oude builds uit de
     *  Drive-map te verwijderen (bv. na een database-wijziging die geen
     *  downgrade toestaat). BLOCKING — zelfde aanroep-eis als checkForUpdate(). */
    fun listAllVersions(): VersionListResult {
        val folderId = BuildConfig.FCL_UPDATE_DRIVE_FOLDER_ID
        val apiKey = BuildConfig.FCL_UPDATE_DRIVE_API_KEY
        if (folderId.isBlank() || apiKey.isBlank()) return VersionListResult.NotConfigured

        return try {
            val files = FclUpdateApi.listFiles(folderId, apiKey)
            val versions = files
                .filter { it.name.startsWith(APK_PREFIX) && it.name.endsWith(".apk") }
                .mapNotNull { f ->
                    VERSION_REGEX.find(f.name)?.groupValues?.get(1)?.toIntOrNull()
                        ?.let { vc -> VersionEntry(versionCode = vc, fileId = f.id, fileName = f.name) }
                }
                .sortedByDescending { it.versionCode }
            VersionListResult.Success(versions)
        } catch (e: Exception) {
            VersionListResult.Error("${e::class.simpleName}: ${e.message ?: "(geen details)"}")
        }
    }
}
