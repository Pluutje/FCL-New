package app.aaps.plugins.aps.openAPSFCL.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FCLvNext update-checker — periodieke achtergrond-check (06/09/2026, de
 * gebruiker), zelfde bewust WorkManager-vrije patroon als
 * `FclAiAdvisorScheduler`: geen AndroidManifest- of DI-wiring nodig, alleen
 * een "is het lang genoeg geleden"-check die vanuit de bestaande APS-cyclus
 * (`DetermineBasalFCL.determine_basal()`) wordt aangeroepen. Ruim interval
 * (12u) — geen tijdkritische check, dus geen probleem als een cyclus 'm
 * overslaat.
 *
 * KRITIEK — zelfde reden als bij FclAiAdvisorScheduler: runIfDue() voert de
 * HTTP-call nooit synchroon uit op de aanroepende thread. determine_basal()
 * draait op de APS-doseringsthread en mag nooit op een netwerkaanroep
 * wachten; de daadwerkelijke check start op een eigen achtergrond-executor
 * en runIfDue() zelf keert direct terug.
 */
object FclUpdateScheduler {

    private val CHECK_INTERVAL = TimeUnit.HOURS.toMillis(12)

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FclUpdateScheduler").apply { isDaemon = true }
    }

    /** Aan te roepen vanuit elke APS-cyclus; goedkoop genoeg (leest alleen SharedPreferences) als het interval nog niet verstreken is. */
    fun runIfDue(context: Context) {
        val lastCheck = FclUpdatePrefs.lastCheckAtMs(context)
        if (lastCheck != null && System.currentTimeMillis() - lastCheck < CHECK_INTERVAL) return
        if (!running.compareAndSet(false, true)) return

        executor.submit {
            try {
                val result = FclUpdateChecker.checkForUpdate()
                FclUpdatePrefs.saveResult(context, result)
            } finally {
                running.set(false)
            }
        }
    }

    /** Voor de "Controleer nu"-knop — negeert het interval.
     *  [onDone] wordt altijd op de main/UI-thread aangeroepen (via een Handler),
     *  zodat de aanroeper 'm veilig kan gebruiken om Compose-state bij te
     *  werken zonder zelf iets terug te hoeven schakelen. */
    fun checkNow(context: Context, onDone: (FclUpdateChecker.Result) -> Unit = {}) {
        if (!running.compareAndSet(false, true)) return
        val mainHandler = Handler(Looper.getMainLooper())
        executor.submit {
            try {
                val result = FclUpdateChecker.checkForUpdate()
                FclUpdatePrefs.saveResult(context, result)
                mainHandler.post { onDone(result) }
            } finally {
                running.set(false)
            }
        }
    }
}
