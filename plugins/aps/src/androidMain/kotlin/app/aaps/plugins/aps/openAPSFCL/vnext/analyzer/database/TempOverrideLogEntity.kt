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
 *
 * 24/09/2026 (de gebruiker, ronde 3) — +portionAmountU/+pendingExtraU/
 * +presetName (MIGRATION_25_26): naast het percentage nu ook de
 * preset-extra-insuline zichtbaar in de CSV. portionAmountU is de
 * hoeveelheid van een preset-portie die DEZE cyclus daadwerkelijk is
 * afgeleverd (0.0 als er deze cyclus geen portie viel); pendingExtraU is de
 * som van de nog NIET afgeleverde porties op dat moment (context: hoeveel
 * er nog "in de wachtrij" staat); presetName is de naam van het preset
 * waarmee de override is gestart, of null bij een handmatige start via de
 * percentage/duur-sliders zelf.
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
    val remainingMinutes: Int,
    val portionAmountU: Double = 0.0,
    val pendingExtraU: Double = 0.0,
    val presetName: String? = null
)
