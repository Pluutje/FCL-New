package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSFCL.vnext.FclProfileBridge
import app.aaps.plugins.aps.openAPSFCL.vnext.InsulinBudget
import app.aaps.plugins.aps.openAPSFCL.vnext.PostHypoEarlyRelease
import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextProfileParameterSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime

/**
 * One JSON file with the settings that are needed to analyse a CSV log. It is made when the user
 * sends the CSV, and a copy is kept next to the CSV in Documents/AAPS/ANALYSE.
 *
 * To add a new setting or test later: add one line to [PLAIN_STORES] (a SharedPreferences file)
 * or to [buildSections]. Nothing else has to change.
 *
 * Secrets are never included. The AI advisor store (API keys) is not in the list on purpose.
 */
object FclSettingsExport {

    private const val SCHEMA_VERSION = 1

    /** A text value longer than this is a history blob, not a setting. It is skipped. */
    private const val MAX_VALUE_LENGTH = 400

    /** SharedPreferences files that are copied as plain key/value pairs. No API keys here. */
    private val PLAIN_STORES = listOf(
        "df_learner_prefs",        // D/F, nfLevel, aggressiveness, learner state
        "v_learner_prefs",         // persistence learner
        "fl_prefs",                // frontload learner
        "fcl_expert_prefs",        // expert mode
        "fcl_ai_param_store",      // approved parameter overrides
        "fcl_cover_trial",         // cover brake trial
        InsulinBudget.PREFS_NAME,  // limit per rise: level and personal reference
        PostHypoEarlyRelease.PREFS_NAME // brake lets go earlier: level (events are too long and skipped)
    )

    /** Internal tuning values (Double). Add a key here and it is exported. */
    private val TUNING_DOUBLE_KEYS = listOf(
        DoubleKey.fcl_vnext_peak_prediction_threshold,
        DoubleKey.fcl_vnext_watching_frontload_frac,
        DoubleKey.fcl_vnext_watching_min_delta,
        DoubleKey.fcl_vnext_peak_prediction_horizon_h,
        DoubleKey.fcl_vnext_iob_start,
        DoubleKey.fcl_vnext_peak_iob_brake_suppress,
        DoubleKey.fcl_vnext_early_boost_factor,
        DoubleKey.fcl_vnext_early_boost_min_confidence,
        DoubleKey.fcl_vnext_early_rise_frac_min,
        DoubleKey.fcl_vnext_peak_max_slope_weight,
        DoubleKey.fcl_vnext_late_commit_decay_factor,
        DoubleKey.fcl_vnext_late_commit_decay_threshold,
        DoubleKey.fcl_vnext_sustained_rise_slope_min,
        DoubleKey.fcl_vnext_early_peak_bias_mmol,
    )

    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    private fun analyseDir() =
        File(android.os.Environment.getExternalStorageDirectory(), "Documents/AAPS/ANALYSE")

    // ── Change log of the user settings ─────────────────────────────────────
    // The CSV is made from the database, so it cannot show what a setting was on an earlier day.
    // This log keeps every change (time, name, old value, new value) for CHANGE_KEEP_DAYS days.

    private const val LOG_PREFS = "fcl_settings_log"
    private const val KEY_LAST = "last_v1"
    private const val KEY_CHANGES = "changes_v1"
    private const val CHANGE_KEEP_DAYS = 28
    private const val MAX_CHANGES = 400

    /** Cheap. Call every cycle. Writes only when a watched setting differs from the last seen value. */
    fun recordChanges(context: Context, preferences: Preferences, nowMs: Long = System.currentTimeMillis()) {
        try {
            val current = FCLvNextProfileParameterSnapshot.collect(preferences)
                .mapValues { (_, v) -> if (v is Double) "%.3f".format(java.util.Locale.US, v) else v.toString() }
                .toMutableMap()
            current["insulin_budget_level"] = InsulinBudget.level(context).name
            current["brake_early_level"] = PostHypoEarlyRelease.level(context).name
            val prefs = context.getSharedPreferences(LOG_PREFS, Context.MODE_PRIVATE)
            val last = (prefs.getString(KEY_LAST, "") ?: "").split("\n").filter { it.contains("=") }
                .associate { it.substringBefore("=") to it.substringAfter("=") }
            val newLines = mutableListOf<String>()
            for ((key, value) in current) {
                if (last[key] != value) newLines += "$nowMs|$key|${last[key] ?: ""}|$value"
            }
            if (newLines.isEmpty()) return
            val cutoff = nowMs - CHANGE_KEEP_DAYS * 24L * 60 * 60 * 1000
            val kept = (prefs.getString(KEY_CHANGES, "") ?: "").split("\n")
                .filter { it.isNotBlank() && (it.substringBefore("|").toLongOrNull() ?: 0L) >= cutoff }
            val all = (kept + newLines).takeLast(MAX_CHANGES)
            prefs.edit()
                .putString(KEY_LAST, current.entries.joinToString("\n") { "${it.key}=${it.value}" })
                .putString(KEY_CHANGES, all.joinToString("\n"))
                .apply()
        } catch (e: Exception) {
            android.util.Log.w("FclSettingsExport", "recordChanges failed", e)
        }
    }

    private fun changesJson(context: Context): JSONArray {
        val arr = JSONArray()
        val text = context.getSharedPreferences(LOG_PREFS, Context.MODE_PRIVATE).getString(KEY_CHANGES, "") ?: ""
        for (line in text.split("\n")) {
            val p = line.split("|")
            if (p.size < 4) continue
            arr.put(
                JSONObject()
                    .put("time", java.time.Instant.ofEpochMilli(p[0].toLongOrNull() ?: 0L)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString())
                    .put("setting", p[1])
                    .put("old", p[2])
                    .put("new", p[3])
            )
        }
        return arr
    }

    /** Builds the JSON text and writes a copy to the ANALYSE folder. Never throws. */
    suspend fun buildAndSave(context: Context, preferences: Preferences): String? = withContext(Dispatchers.IO) {
        try {
            val json = build(context, preferences).toString(2)
            val dir = analyseDir()
            dir.mkdirs()
            val shortId = deviceId(context).take(6)
            File(dir, "FCLvNext_Settings_$shortId.json").writeText(json, Charsets.UTF_8)
            json
        } catch (e: Exception) {
            android.util.Log.w("FclSettingsExport", "Settings file failed, upload goes on without it", e)
            null
        }
    }

    suspend fun build(context: Context, preferences: Preferences): JSONObject {
        val root = JSONObject()
        root.put("schema_version", SCHEMA_VERSION)
        root.put("written_at", LocalDateTime.now().toString())
        buildSections(context, preferences).forEach { (name, section) -> root.put(name, section) }
        return root
    }

    private suspend fun buildSections(context: Context, preferences: Preferences): List<Pair<String, JSONObject>> {
        val sections = mutableListOf<Pair<String, JSONObject>>()

        sections += "app" to runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            JSONObject()
                .put("version_name", info.versionName ?: "")
                .put("device_id", deviceId(context))
        }.getOrElse { JSONObject() }

        // Safety limits and the internal tuning values. They explain why the algorithm acted as it did.
        sections += "tuning" to runCatching {
            val t = JSONObject()
            t.put("safety_max_bolus", preferences.get(DoubleKey.SafetyMaxBolus))
            t.put("aaps_multiplier_day", preferences.get(DoubleKey.fcl_aaps_mulitplier_day))
            t.put("aaps_multiplier_night", preferences.get(DoubleKey.fcl_aaps_mulitplier_night))
            t.put("commit_cooldown_minutes", preferences.get(IntKey.fcl_vnext_commit_cooldown_minutes))
            t.put("early_boost_max_commits", preferences.get(IntKey.fcl_vnext_early_boost_max_commits))
            t.put("sustained_rise_min_target", preferences.get(IntKey.fcl_vnext_sustained_rise_min_target))
            t.put("night_response_style", preferences.get(StringKey.fcl_vnext_night_response_style))
            for (key in TUNING_DOUBLE_KEYS) t.put(key.key, preferences.get(key))
            t
        }.getOrElse { JSONObject().put("error", it.message ?: "failed") }

        // User settings (S/T/V/N, max bolus day and night, max IOB, day/night times).
        sections += "settings" to runCatching {
            toJson(FCLvNextProfileParameterSnapshot.collectForHistory(preferences))
        }.getOrElse { JSONObject().put("error", it.message ?: "failed") }

        // Max SMB and IOB brake in use (the stored "learned" keys are not what the algorithm uses).
        sections += "effective" to runCatching {
            val manual = preferences.get(DoubleKey.max_bolus_day)
            val s = preferences.get(IntKey.fcl_vnext_sterkte) / 100.0
            JSONObject()
                .put("max_smb_day", (manual * s).coerceIn(manual * 0.5, manual * 1.5))
                .put("max_smb_night", preferences.get(DoubleKey.max_bolus_night))
                .put("max_iob", preferences.get(DoubleKey.fcl_vnext_MaxIOB))
                .put("iob_brake_threshold", preferences.get(DoubleKey.fcl_vnext_peak_iob_brake_suppress))
        }.getOrElse { JSONObject().put("error", it.message ?: "failed") }

        // Insulin type, peak time and DIA: needed for timing advice.
        sections += "insulin" to runCatching {
            val icfg = FclProfileBridge.getProfileFunction()?.getProfile()?.iCfg
            if (icfg == null) JSONObject().put("note", "no profile available")
            else JSONObject()
                .put("label", icfg.insulinLabel)
                .put("peak_min", icfg.peak)
                .put("dia_h", icfg.dia)
                .put("concentration", icfg.concentration)
        }.getOrElse { JSONObject().put("error", it.message ?: "failed") }

        // Every change of a user setting in the last CHANGE_KEEP_DAYS days.
        val log = JSONObject().put("changes", changesJson(context))
        sections += "settings_changes" to log

        // Limit per rise: level, personal reference and how many meals it is based on.
        sections += "insulin_budget" to runCatching {
            JSONObject()
                .put("level", InsulinBudget.level(context).name)
                .put("ref_u_per_mmol", InsulinBudget.reference(context).let { if (it.isNaN()) JSONObject.NULL else it })
                .put("ref_episodes", InsulinBudget.referenceEpisodes(context))
        }.getOrElse { JSONObject().put("error", it.message ?: "failed") }

        // Learner and trial state.
        val stores = JSONObject()
        for (name in PLAIN_STORES) {
            stores.put(name, serialize(context.getSharedPreferences(name, Context.MODE_PRIVATE)))
        }
        sections += "stores" to stores
        return sections
    }

    private fun toJson(map: Map<String, Any>): JSONObject {
        val obj = JSONObject()
        for ((key, value) in map) obj.put(key, value)
        return obj
    }

    private fun serialize(prefs: SharedPreferences): JSONObject {
        val obj = JSONObject()
        for ((key, value) in prefs.all) {
            when (value) {
                is Boolean, is Int, is Long -> obj.put(key, value)
                is Float -> obj.put(key, value.toDouble())
                is String -> if (value.length <= MAX_VALUE_LENGTH) obj.put(key, value)
                is Set<*> -> if (value.size <= 50) obj.put(key, JSONArray(value.map { it.toString() }))
                else -> Unit
            }
        }
        return obj
    }
}
