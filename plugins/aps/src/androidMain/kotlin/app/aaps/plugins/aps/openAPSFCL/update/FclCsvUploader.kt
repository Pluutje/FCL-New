package app.aaps.plugins.aps.openAPSFCL.update

import android.content.Context
import android.provider.Settings
import app.aaps.plugins.aps.GeneratedFclSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * FCLvNext CSV-upload (07/09/2026, de gebruiker) — stuurt het bestaande
 * rollende 7-dagen-analysebestand naar een door de gebruiker zelf
 * gedeployde Google Apps Script "Web App", zodat andere gebruikers hun
 * logboek makkelijk kunnen delen zonder los een bestand te hoeven mailen.
 *
 * WAAROM GEEN SERVICE-ACCOUNT-SLEUTEL (zoals bij het lezen in
 * FclUpdateApi.kt): een service-account krijgt sinds medio 2023 standaard
 * 0 GB eigen Drive-opslagquotum. Bij een gewoon (niet-Workspace)
 * Google-account faalt uploaden daardoor vaak met "Service Accounts do not
 * have storage quota" — ook als de doelmap met het account is gedeeld. Het
 * Apps Script hieronder draait onder het ECHTE Google-account van de
 * gebruiker (Execute as: Me), dus geen quotumprobleem. Zie
 * FCLvNext_CsvUpload_AppsScript.gs.txt voor het script zelf en de
 * deploy-instructies.
 *
 * FCL_CSV_UPLOAD_SECRET is bewust geen echte auth-laag — puur een simpel
 * gedeeld wachtwoord dat het script ook controleert, zodat iemand die de
 * deploy-URL zou raden niet zomaar kan uploaden. Ontbreken URL/secret, dan
 * geeft upload() gewoon NotConfigured terug — geen crash, zelfde patroon
 * als FclUpdateChecker.checkForUpdate().
 */
object FclCsvUploader {

    /** Zelfde map als FCLCycleLogRepository.exportCsvLast7DaysInternal(). */
    private val ANALYSE_DIR = File(
        android.os.Environment.getExternalStorageDirectory(),
        "Documents/AAPS/ANALYSE"
    )

    /** Matcht FCLvNext_Log_v<schemaversie>.csv — zie csvHeader()/toCsvLine() in
     *  FCLCycleLogRepository.kt. Bewust NIET hardgecodeerd op één versienummer
     *  (07/09/2026, de gebruiker): het CSV-schema wordt af en toe uitgebreid
     *  (v9->v10->v11->v12, ...) en zonder dit zou de upload-knop bij elke
     *  volgende schema-wijziging stilzwijgend het verkeerde/oude bestand
     *  blijven sturen totdat iemand deze regel handmatig bijwerkt. */
    private val CSV_NAME_REGEX = Regex("""FCLvNext_Log_v(\d+)\.csv""")

    /** Het bestand met het hoogste schemaversienummer in de ANALYSE-map, of
     *  null als er nog niets ligt. */
    private fun findLatestCsvFile(): File? =
        ANALYSE_DIR.listFiles { f -> CSV_NAME_REGEX.matches(f.name) }
            ?.mapNotNull { f ->
                CSV_NAME_REGEX.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()?.let { v -> f to v }
            }
            ?.maxByOrNull { (_, v) -> v }
            ?.first

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    sealed class Result {
        data class Success(val fileName: String) : Result()
        object NotConfigured : Result()
        object CsvNotFound : Result()
        data class Error(val message: String) : Result()
    }

    /** BLOCKING via withContext(Dispatchers.IO) — veilig aan te roepen vanuit de UI-thread. */
    suspend fun upload(context: Context): Result = withContext(Dispatchers.IO) {
        try {
            val url = GeneratedFclSecrets.FCL_CSV_UPLOAD_URL
            val secret = GeneratedFclSecrets.FCL_CSV_UPLOAD_SECRET
            if (url.isBlank() || secret.isBlank()) return@withContext Result.NotConfigured
            val csvFile = findLatestCsvFile() ?: return@withContext Result.CsvNotFound

            val csvContent = csvFile.readText(Charsets.UTF_8)
            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "onbekend"

            val payload = JSONObject().apply {
                put("secret", secret)
                put("deviceId", deviceId)
                put("csvContent", csvContent)
            }

            val responseBody = httpPostJson(url, payload.toString())
            val response = JSONObject(responseBody)
            if (response.optBoolean("ok", false)) {
                Result.Success(response.optString("fileName", csvFile.name))
            } else {
                Result.Error(response.optString("error", "onbekende fout"))
            }
        } catch (e: Exception) {
            Result.Error("${e::class.simpleName}: ${e.message ?: "(geen details)"}")
        }
    }

    private fun httpPostJson(url: String, jsonBody: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "POST"
        connection.doOutput = true
        // Apps Script Web Apps volgen bij POST vaak een 302 naar
        // script.googleusercontent.com — HttpURLConnection volgt dat niet
        // automatisch over hosts heen, dus expliciet uitzetten en zelf 1x
        // handmatig volgen (zie hieronder) i.p.v. een dependency toe te
        // voegen die dit al afhandelt.
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

            var status = connection.responseCode
            var effectiveConnection = connection
            if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM) {
                val redirectUrl = connection.getHeaderField("Location")
                    ?: throw IOException("Redirect zonder Location-header")
                effectiveConnection = (URL(redirectUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                }
                status = effectiveConnection.responseCode
            }

            if (status != HttpURLConnection.HTTP_OK) {
                val errorBody = try {
                    effectiveConnection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                } catch (e: Exception) {
                    null
                } ?: "(geen foutdetails)"
                throw IOException("Upload HTTP $status: $errorBody")
            }
            return effectiveConnection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
