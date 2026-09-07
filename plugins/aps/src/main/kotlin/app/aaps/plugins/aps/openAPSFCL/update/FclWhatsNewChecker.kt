package app.aaps.plugins.aps.openAPSFCL.update

import android.content.Context
import app.aaps.plugins.aps.BuildConfig

/**
 * "Wat is nieuw" — leest alle `_whatsnew.txt`-bestanden voor versies STRIKT
 * HOGER dan de huidige, geïnstalleerde versie (06/09/2026, de gebruiker).
 *
 * Kan nooit met terugwerkende kracht werken: een versie die deze knop nog
 * niet had, kan de bijbehorende changelog-check ook niet uitvoeren. Pas de
 * EERSTVOLGENDE versie ná introductie van deze functie heeft er echt iets
 * aan — changelog-bestanden voor oudere versies zijn dode data en hoeven
 * niet aangemaakt te worden (zie het FCLGlucoLink-overdrachtsdocument, §7).
 */
object FclWhatsNewChecker {

    data class Entry(val versionCode: Int, val text: String)

    private val VERSION_REGEX = Regex("""_[vV](\d+)""")
    private const val SUFFIX = "_whatsnew.txt"

    /** BLOCKING — aanroeper moet dit binnen withContext(Dispatchers.IO) draaien. */
    fun fetchSince(context: Context, sinceVersionCode: Int): List<Entry> {
        val folderId = BuildConfig.FCL_UPDATE_DRIVE_FOLDER_ID
        val apiKey = BuildConfig.FCL_UPDATE_DRIVE_API_KEY
        if (folderId.isBlank() || apiKey.isBlank()) return emptyList()

        val files = FclUpdateApi.listFiles(folderId, apiKey)
        return files
            .filter { it.name.endsWith(SUFFIX) }
            .mapNotNull { f ->
                VERSION_REGEX.find(f.name)?.groupValues?.get(1)?.toIntOrNull()?.let { vc -> f to vc }
            }
            .filter { (_, vc) -> vc > sinceVersionCode }
            .sortedByDescending { (_, vc) -> vc }
            .map { (f, vc) -> Entry(versionCode = vc, text = FclUpdateApi.downloadText(f.id, apiKey)) }
    }
}
