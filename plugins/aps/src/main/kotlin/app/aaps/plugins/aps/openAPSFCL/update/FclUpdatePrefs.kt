package app.aaps.plugins.aps.openAPSFCL.update

import android.content.Context

/**
 * Eigen, geïsoleerde opslag voor het laatst bekende update-checkresultaat
 * (06/09/2026, de gebruiker) — bewust een losse SharedPreferences, zelfde
 * patroon als `fcl_ai_advisor_prefs`/`FCL_Activity_State` elders in
 * `openAPSFCL`. GEEN AAPS-kern-Preferences/keys-systeem: deze instelling
 * hoort puur bij de update-checker en moet niet meegroeien met de reguliere
 * FCLvNext-instellingen-export/-import.
 *
 * Bewaart alleen het LAATST bekende resultaat, zodat het instellingenscherm
 * meteen iets kan tonen zonder bij elke schermopening een nieuwe
 * netwerkaanroep te doen — de daadwerkelijke check gebeurt via
 * FclUpdateScheduler (periodiek) of de "Controleer nu"-knop (handmatig).
 */
object FclUpdatePrefs {

    private const val PREFS_NAME = "fcl_update_prefs"

    private const val KEY_AVAILABLE_VERSION_CODE = "available_version_code"
    private const val KEY_AVAILABLE_FILE_ID = "available_file_id"
    private const val KEY_AVAILABLE_FILE_NAME = "available_file_name"
    private const val KEY_LAST_CHECK_AT_MS = "last_check_at_ms"
    private const val KEY_LAST_ERROR = "last_error"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveResult(context: Context, result: FclUpdateChecker.Result) {
        val editor = prefs(context).edit()
        editor.putLong(KEY_LAST_CHECK_AT_MS, System.currentTimeMillis())
        when (result) {
            is FclUpdateChecker.Result.UpdateAvailable -> {
                editor.putInt(KEY_AVAILABLE_VERSION_CODE, result.versionCode)
                editor.putString(KEY_AVAILABLE_FILE_ID, result.fileId)
                editor.putString(KEY_AVAILABLE_FILE_NAME, result.fileName)
                editor.remove(KEY_LAST_ERROR)
            }
            is FclUpdateChecker.Result.UpToDate -> {
                editor.remove(KEY_AVAILABLE_VERSION_CODE)
                editor.remove(KEY_AVAILABLE_FILE_ID)
                editor.remove(KEY_AVAILABLE_FILE_NAME)
                editor.remove(KEY_LAST_ERROR)
            }
            is FclUpdateChecker.Result.Error -> editor.putString(KEY_LAST_ERROR, result.message)
            FclUpdateChecker.Result.NotConfigured -> { /* niets te bewaren */ }
        }
        editor.apply()
    }

    /** Laatst bekende gevonden update, of null als er (voor zover bekend) geen is. */
    fun availableUpdate(context: Context): FclUpdateChecker.Result.UpdateAvailable? {
        val p = prefs(context)
        val versionCode = p.getInt(KEY_AVAILABLE_VERSION_CODE, -1)
        val fileId = p.getString(KEY_AVAILABLE_FILE_ID, null)
        val fileName = p.getString(KEY_AVAILABLE_FILE_NAME, null)
        if (versionCode <= 0 || fileId == null || fileName == null) return null
        return FclUpdateChecker.Result.UpdateAvailable(versionCode, fileId, fileName)
    }

    fun lastCheckAtMs(context: Context): Long? =
        prefs(context).getLong(KEY_LAST_CHECK_AT_MS, -1L).takeIf { it > 0 }

    fun lastError(context: Context): String? =
        prefs(context).getString(KEY_LAST_ERROR, null)
}
