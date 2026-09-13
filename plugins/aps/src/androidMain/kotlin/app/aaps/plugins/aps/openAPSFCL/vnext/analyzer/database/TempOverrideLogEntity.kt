package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Diagnostiek voor de Temp Override ("Tijdelijke aanpassing", 11/09/2026) —
 * eigen, kleine tabel i.p.v. een uitbreiding van FCLCycleLogEntity, exact
 * hetzelfde bewezen patroon als PostHypoBrakeLogEntity/ExplosiveRiseLogEntity.
 *
 * AANLEIDING: FCLCycleLogEntity zit met 167+ velden al tegen de grens aan die
 * eerder (v82) een reproduceerbare java.lang.VerifyError-crash veroorzaakte
 * toen 2 nieuwe velden via "ALTER TABLE fcl_cycle_log ADD COLUMN ..." werden
 * toegevoegd — zie de uitgebreide kdoc bij PostHypoBrakeLogEntity voor de
 * volledige reproductie/A-B-test. Om FCLCycleLogEntity niet verder te laten
 * groeien, staan deze nieuwe velden (zie kdoc bij FclTempOverrideSettings.kt)
 * daarom in deze eigen tabel.
 *
 * Bij CSV-export (FCLCycleLogRepository.exportCsvLast7DaysInternal()) wordt
 * deze tabel, net als post_hypo_brake_log/explosive_rise_log, op timestampMs
 * samengevoegd met fcl_cycle_log tot één regel — de gebruiker blijft met
 * precies één CSV-bestand werken. fcl_cycle_log zelf blijft onaangeroerd.
 *
 * targetPct/effectiveMul worden ELKE cyclus gelogd (ook als de override niet
 * actief is: dan targetPct=100, effectiveMul=1.0, remainingMinutes=-1) — zo
 * blijft in de CSV altijd zichtbaar of, en hoe sterk, de override deze
 * cyclus daadwerkelijk heeft meegewogen.
 */
@Entity(
    tableName = "temp_override_log",
    indices = [
        Index(value = ["timestampMs"])
    ]
)
data class TempOverrideLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val timestampMs: Long,
    val active: Boolean,
    val targetPct: Int,
    val effectiveMul: Double,
    val remainingMinutes: Int
)
