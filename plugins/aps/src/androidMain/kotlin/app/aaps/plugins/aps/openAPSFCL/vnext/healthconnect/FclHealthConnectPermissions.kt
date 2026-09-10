package app.aaps.plugins.aps.openAPSFCL.vnext.healthconnect

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord

/**
 * FCLvNext Health Connect permissions (10/09/2026, de gebruiker).
 *
 * AANLEIDING: voor een horloge waar AAPS' eigen Wear-app op draait (zoals een
 * OnePlus Watch) komen stappen/hartslag al lang binnen via die app rechtstreeks
 * (zie EstimatedCaloriesCalculator.kt voor de volledige geschiedenis van die
 * beslissing). Een Garmin-horloge draait geen Wear OS en kan die AAPS-app dus
 * NOOIT draaien — voor Garmin is Health Connect de ENIGE weg, via de
 * fabrikants-app (Garmin Connect) die er zelf naartoe schrijft.
 *
 * LET OP — dit is niet strikt "Wear OS wel/niet": ook een Samsung Galaxy
 * Watch (dat wél Wear OS draait) kan hierbij horen, namelijk als Samsung
 * Health de gegevens naar Health Connect stuurt in plaats van dat de AAPS-app
 * rechtstreeks de sensoren uitleest. De echte scheidslijn is dus "komt de
 * data al via de AAPS-Wear-app binnen, of niet" — niet het merk/OS van het
 * horloge. Zie FclHealthConnectSync.kt voor de leeskant.
 *
 * BEWUST MINIMAAL — alleen wat FCLvNext nu echt gebruikt:
 *  - StepsRecord  → PersistenceLayer.insertOrUpdateStepsCounts() (SC)
 *  - HeartRateRecord → PersistenceLayer.insertOrUpdateHeartRates() (HR)
 *
 * Bewust GEEN slaap/HRV/rustpols/huidtemperatuur, ook al biedt Health
 * Connect die net zo makkelijk aan (zie AIMI's bredere implementatie:
 * MTR93600/OpenApsAIMI, plugins/aps/.../openAPSAIMI/physio/
 * AIMIHealthConnectPermissions.kt). Reden: FCLvNext heeft daar op dit
 * moment geen consument voor — geen enkel AIGF-onderdeel leest die
 * signalen. Het project heeft een expliciete regel tegen het alvast
 * bouwen van ongebruikte infrastructuur ("ship een generator/interface/
 * registratie pas als er ook iets is dat 'm gebruikt"), en dat weegt hier
 * extra zwaar omdat het NIET alleen "een paar extra permissies aanvragen"
 * zou zijn — zie hieronder waarom.
 *
 * TOEKOMSTIGE UITBREIDING — als er ooit een AIGF-toepassing komt voor
 * slaap/HRV/rustpols/temperatuur, is dit de weg:
 *  1) Permissies: voeg toe aan een nieuwe `PHYSIO_PERMISSIONS`-set, bijv.
 *     `HealthPermission.getReadPermission(SleepSessionRecord::class)`,
 *     `HeartRateVariabilityRmssdRecord::class`, `RestingHeartRateRecord::class`,
 *     `SkinTemperatureRecord::class`/`BasalBodyTemperatureRecord::class` —
 *     zelfde patroon als STEPS_AND_HR_PERMISSIONS hieronder.
 *  2) LET OP — dit is de eigenlijke horde, niet de permissies: AAPS'
 *     `PersistenceLayer`/Room-database heeft momenteel HELEMAAL GEEN opslag
 *     voor slaap/HRV/rustpols/temperatuur (geverifieerd 10/09/2026 — alleen
 *     `HR`/`SC` bestaan, in core/data/.../model/HR.kt en SC.kt, met
 *     insertOrUpdateHeartRates()/insertOrUpdateStepsCounts() in
 *     PersistenceLayer.kt). Voor elk nieuw signaal moet eerst een nieuwe
 *     Room-entity + DAO + database-migratie + PersistenceLayer-methode
 *     bij komen, in `core/data`/`database/impl` — dat is een echte
 *     schema-wijziging, geen kleine toevoeging.
 *  3) Uitleeslogica als referentie (niet 1-op-1 te kopiëren, andere
 *     architectuur): AIMI's `AIMIHealthConnectPermissions.kt` en
 *     `AIMIHealthConnectPermissionActivityMTR.kt` (zelfde repo/pad als
 *     hierboven) laten zien hoe `client.readRecords(ReadRecordsRequest(...))`
 *     voor elk van deze record-types werkt.
 *  4) Omdat Health Connect's toestemmingsscherm alle aangevraagde
 *     permissies in ÉÉN keer toont: als dit ooit gebouwd wordt, moet de
 *     gebruiker opnieuw een (bredere) toestemming geven — de nu al
 *     gegeven steps/HR-toestemming blijft gewoon staan, dit is een
 *     aanvulling, geen vervanging.
 */
object FclHealthConnectPermissions {

    /** De enige permissies die FCLvNext nu aanvraagt en gebruikt. */
    val STEPS_AND_HR_PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )

    /** Mensleesbare namen voor UI/logging. */
    private val PERMISSION_NAMES = mapOf(
        HealthPermission.getReadPermission(StepsRecord::class) to "Stappen",
        HealthPermission.getReadPermission(HeartRateRecord::class) to "Hartslag",
    )

    fun displayName(permission: String): String =
        PERMISSION_NAMES[permission] ?: permission.substringAfterLast('.')

    fun hasAllPermissions(grantedPermissions: Set<String>): Boolean =
        grantedPermissions.containsAll(STEPS_AND_HR_PERMISSIONS)

    fun getMissingPermissions(grantedPermissions: Set<String>): Set<String> =
        STEPS_AND_HR_PERMISSIONS.filter { it !in grantedPermissions }.toSet()
}
