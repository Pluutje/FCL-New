package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Diagnostiek voor de EXPLOSIVE_RISE-boost (3-4/9/2026) — eigen, kleine tabel
 * i.p.v. een uitbreiding van FCLCycleLogEntity, exact hetzelfde bewezen
 * patroon als PostHypoBrakeLogEntity (26/08/2026).
 *
 * AANLEIDING: FCLCycleLogEntity zit met 167+ velden al tegen de grens aan die
 * eerder (v82) een reproduceerbare java.lang.VerifyError-crash veroorzaakte
 * toen 2 nieuwe velden via "ALTER TABLE fcl_cycle_log ADD COLUMN ..." werden
 * toegevoegd — zie de uitgebreide kdoc bij PostHypoBrakeLogEntity voor de
 * volledige reproductie/A-B-test. Om FCLCycleLogEntity niet verder te laten
 * groeien, staan deze 4 nieuwe velden (uit het gesprek over een boost bij
 * uitzonderlijk snelle BG-stijging, zie EXPLOSIVE_RISE_LOWER in FCLvNext.kt)
 * daarom in deze eigen tabel.
 *
 * Bij CSV-export (FCLCycleLogRepository.exportCsvLast7DaysInternal()) wordt
 * deze tabel, net als post_hypo_brake_log, op timestampMs samengevoegd met
 * fcl_cycle_log tot één regel — de gebruiker blijft met precies één
 * CSV-bestand werken. fcl_cycle_log zelf blijft onaangeroerd.
 *
 * projectedMinNoInsulin wordt ELKE cyclus gelogd (ongeacht of de boost actief
 * was) — dit is dezelfde no-insulin-BG-projectie die elders in FCLvNext.kt al
 * de strongRisingWithIob-hypo-bypass bepaalt, hier ook los bewaard omdat hij
 * nu ook de nieuwe EXPLOSIVE_RISE-poort bepaalt en op zichzelf al nuttig is
 * voor latere analyse van de hypo-veiligheidsmarge in het algemeen.
 */
@Entity(
    tableName = "explosive_rise_log",
    indices = [
        Index(value = ["timestampMs"])
    ]
)
data class ExplosiveRiseLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val timestampMs: Long,
    val frac: Double,
    val mul: Double,
    val active: Boolean,
    val projectedMinNoInsulin: Double
)
