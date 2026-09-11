package app.aaps.plugins.aps.openAPSFCL.vnext.healthconnect

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateRecord.Sample
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.db.PersistenceLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FCLvNext Health Connect sync (10/09/2026, de gebruiker) — stappen en
 * hartslag voor horloges die deze data niet al via AAPS' eigen Wear-app
 * leveren (Garmin, of een Samsung Galaxy Watch via Samsung Health — zie
 * FclHealthConnectPermissions.kt voor de volledige uitleg waarom dit niet
 * simpelweg "Wear OS wel/niet" is).
 *
 * Zie FclHealthConnectPermissions.kt voor de aanleiding en scope (bewust
 * alleen stappen+hartslag, geen slaap/HRV/temperatuur).
 *
 * Zelfde DI-vrije, rate-gelimiteerde patroon als FclUpdateScheduler.kt:
 * geen AndroidManifest- of DI-wiring nodig, alleen een "is het lang genoeg
 * geleden"-check die vanuit de bestaande APS-cyclus
 * (DetermineBasalFCL.determine_basal()) wordt aangeroepen. runIfDue() keert
 * ALTIJD direct terug — de Health Connect-call en de database-schrijf
 * draaien nooit op de aanroepende (APS-doserings-)thread.
 *
 * Schrijft naar PRECIES dezelfde PersistenceLayer-methoden als het
 * bestaande Wear-sensor-pad (DataHandlerMobile.handleStepsCountBatch()/
 * handleHeartRateBatch()) — AIGF zelf hoeft dus niet te weten of de data
 * van het horloge of van Health Connect komt, en niets in FCLActivityModule/
 * EstimatedCaloriesCalculator/etc. hoeft aangepast te worden.
 *
 * Als Health Connect niet geïnstalleerd is, of de toestemming nog niet is
 * gegeven (zie FCLSettingsScreen.kt voor het koppelscherm): stille no-op,
 * geen crash, geen herhaalde foutmeldingen — precies zoals AIMI's
 * implementatie dit ook doet ("graceful degradation").
 */
object FclHealthConnectSync {

    private const val TAG = "FclHealthConnectSync"

    // Iets korter dan een normale loop-cyclus (~5 min), zodat elke cyclus
    // verse data heeft zonder Health Connect nodeloos vaak te bevragen.
    private val CHECK_INTERVAL = TimeUnit.MINUTES.toMillis(4)

    // Stappen-vensters — moeten exact overeenkomen met de velden van SC
    // (core/data/.../model/SC.kt), zelfde vensters als het Wear-sensorpad
    // al jaren gebruikt.
    private val STEP_WINDOWS_MIN = intArrayOf(5, 10, 15, 30, 60, 180)

    // Terugval-vensters voor hartslag (11/09/2026, Rick) — AANLEIDING: bij
    // stappen wordt met STEP_WINDOWS_MIN hierboven altijd een breed venster
    // (180 min) opgehaald, dus een trage Garmin-sync mist nooit een cyclus.
    // syncHeartRate() vroeg tot nu toe altijd maar een hard venster van 5
    // minuten op — kwam Garmin's HR-data net niet binnen dat venster, dan
    // werd er STIL niets geschreven (samples.isEmpty() -> return), cyclus na
    // cyclus, want horloge-apps schrijven hartslag doorgaans minder vaak naar
    // Health Connect weg dan de lopende stappenteller. Zelfde "één brede
    // read, lokaal versmallen"-aanpak als bij stappen, maar dan andersom: bij
    // verse data (5 min heeft al iets) verandert er niets aan de kwaliteit —
    // pas als dat venster leeg is, valt hij terug op een breder venster, tot
    // en met 60 minuten.
    private val HR_FALLBACK_WINDOWS_MIN = intArrayOf(5, 15, 30, 60)

    private var lastRunAtMs: Long = 0L
    private val running = AtomicBoolean(false)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Aan te roepen vanuit elke APS-cyclus; goedkoop genoeg (alleen een tijdstempel-check) als het interval nog niet verstreken is. */
    fun runIfDue(context: Context, persistenceLayer: PersistenceLayer) {
        val now = System.currentTimeMillis()
        if (now - lastRunAtMs < CHECK_INTERVAL) return
        if (!running.compareAndSet(false, true)) return
        lastRunAtMs = now

        ioScope.launch {
            try {
                syncOnce(context, persistenceLayer)
            } catch (e: Exception) {
                Log.w(TAG, "Health Connect sync failed: ${e.message}")
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun syncOnce(context: Context, persistenceLayer: PersistenceLayer) {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return
        val client = try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            Log.d(TAG, "Health Connect client unavailable: ${e.message}")
            return
        }

        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            Log.d(TAG, "Could not read Health Connect permissions: ${e.message}")
            return
        }
        if (!FclHealthConnectPermissions.hasAllPermissions(granted)) return

        syncSteps(client, persistenceLayer)
        syncHeartRate(client, persistenceLayer)
    }

    private suspend fun syncSteps(client: HealthConnectClient, persistenceLayer: PersistenceLayer) {
        val now = Instant.now()
        val widestWindowMin = STEP_WINDOWS_MIN.max()

        // Eén request voor het breedste venster, de kleinere vensters worden
        // daaruit gefilterd — scheelt 5 aparte Health Connect-aanroepen.
        val records = try {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minusSeconds(widestWindowMin * 60L), now)
                )
            ).records
        } catch (e: Exception) {
            Log.d(TAG, "Steps read failed: ${e.message}")
            return
        }
        if (records.isEmpty()) return

        fun stepsInLastMinutes(minutes: Int): Int {
            val start = now.minusSeconds(minutes * 60L)
            return records
                .filter { !it.endTime.isBefore(start) }
                .sumOf { it.count.toInt() }
        }

        val sc = SC(
            duration = TimeUnit.MINUTES.toMillis(STEP_WINDOWS_MIN.min().toLong()),
            timestamp = now.toEpochMilli(),
            steps5min = stepsInLastMinutes(5),
            steps10min = stepsInLastMinutes(10),
            steps15min = stepsInLastMinutes(15),
            steps30min = stepsInLastMinutes(30),
            steps60min = stepsInLastMinutes(60),
            steps180min = stepsInLastMinutes(180),
            device = "HealthConnect"
        )
        persistenceLayer.insertOrUpdateStepsCounts(listOf(sc))
    }

    private suspend fun syncHeartRate(client: HealthConnectClient, persistenceLayer: PersistenceLayer) {
        val now = Instant.now()
        val widestWindowMin = HR_FALLBACK_WINDOWS_MIN.max()

        // Eén request voor het breedste venster, zelfde patroon als
        // syncSteps() hierboven — scheelt aparte Health Connect-aanroepen
        // per terugval-venster.
        val records = try {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minusSeconds(widestWindowMin * 60L), now)
                )
            ).records
        } catch (e: Exception) {
            Log.d(TAG, "Heart rate read failed: ${e.message}")
            return
        }
        if (records.isEmpty()) return

        // Smalste venster met data wint — zie kdoc bij HR_FALLBACK_WINDOWS_MIN
        // hierboven. Bij verse data (5 min heeft al iets) identiek aan het
        // oude gedrag; pas bij een leeg 5-min-venster valt dit terug op een
        // breder venster, i.p.v. die cyclus stil niets te schrijven.
        var usedWindowMin = -1
        var samples = emptyList<Sample>()
        for (windowMin in HR_FALLBACK_WINDOWS_MIN) {
            val start = now.minusSeconds(windowMin * 60L)
            val inWindow = records
                .filter { !it.endTime.isBefore(start) }
                .flatMap { it.samples }
            if (inWindow.isNotEmpty()) {
                usedWindowMin = windowMin
                samples = inWindow
                break
            }
        }
        if (samples.isEmpty()) return // geen fictieve waarde schrijven als er niets gemeten is, ook niet binnen 60 min

        val averageBpm = samples.map { it.beatsPerMinute }.average()
        val hr = HR(
            duration = TimeUnit.MINUTES.toMillis(usedWindowMin.toLong()),
            timestamp = now.toEpochMilli(),
            beatsPerMinute = averageBpm,
            device = "HealthConnect"
        )
        persistenceLayer.insertOrUpdateHeartRates(listOf(hr))
    }
}
