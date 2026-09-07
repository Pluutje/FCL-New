package app.aaps.plugins.aps.openAPSFCL.update

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * FCLvNext update-checker — kale Google Drive REST-laag (06/09/2026, de gebruiker).
 *
 * Overgenomen van het FCLGlucoLink-overdrachtsdocument ("FCLGlucoLink —
 * Update-mechanisme via Google Drive"): bewust GEEN Drive SDK, alleen twee
 * kale REST-aanroepen (`files.list` en het media-downloadendpoint) via
 * `HttpURLConnection`. Geen extra dependency, geen OAuth-flow — een simpele
 * API-key volstaat voor een publiek-leesbare map.
 *
 * Belangrijke valkuilen (uit het overdrachtsdocument, §7):
 *  - Gebruik het media-downloadendpoint (`files/<id>?alt=media&key=...`), NIET
 *    de publieke "uc?export=download"-link — die laatste toont bij een groter
 *    bestand een "kan niet scannen op virussen"-tussenpagina (HTML) i.p.v. de
 *    echte bytes.
 *  - Lees bij een non-200 status altijd de errorStream (niet de gewone
 *    inputStream) — Google's eigen foutmelding staat daarin.
 *  - Elke functie hier is bewust BLOCKING I/O — de aanroeper (FclUpdateChecker/
 *    FclUpdateInstaller) moet dit zelf binnen `withContext(Dispatchers.IO)`
 *    aanroepen, nooit vanuit de APS-doseringsthread of de UI-thread.
 */
object FclUpdateApi {

    data class DriveFile(val id: String, val name: String)

    private const val DRIVE_BASE = "https://www.googleapis.com/drive/v3/files"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Lijst van bestanden in [folderId], zonder mappen/subfolders. */
    fun listFiles(folderId: String, apiKey: String): List<DriveFile> {
        val query = URLEncoder.encode("'$folderId' in parents and trashed=false", "UTF-8")
        val url = "$DRIVE_BASE?q=$query&fields=files(id,name)&pageSize=200&key=$apiKey"
        val body = httpGetText(url)
        val files = JSONObject(body).optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).map { i ->
            val obj = files.getJSONObject(i)
            DriveFile(id = obj.getString("id"), name = obj.getString("name"))
        }
    }

    /** Leest een klein tekstbestand (bv. een `_whatsnew.txt`) volledig in als String. */
    fun downloadText(fileId: String, apiKey: String): String =
        httpGetText(mediaUrl(fileId, apiKey))

    /** Downloadt een (mogelijk groot) bestand rechtstreeks naar [destination]. */
    fun downloadToFile(fileId: String, apiKey: String, destination: File) {
        val connection = openConnection(mediaUrl(fileId, apiKey))
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("Drive download HTTP $status: ${readErrorBody(connection)}")
            }
            destination.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun mediaUrl(fileId: String, apiKey: String) =
        "$DRIVE_BASE/$fileId?alt=media&key=$apiKey"

    private fun httpGetText(url: String): String {
        val connection = openConnection(url)
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("Drive request HTTP $status: ${readErrorBody(connection)}")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        return connection
    }

    /** Google's eigen foutmelding (bv. "API key not valid") staat in de errorStream, niet inputStream. */
    private fun readErrorBody(connection: HttpURLConnection): String =
        try {
            connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "(geen foutdetails)"
        } catch (e: Exception) {
            "(kon foutdetails niet lezen: ${e::class.simpleName})"
        }
}
