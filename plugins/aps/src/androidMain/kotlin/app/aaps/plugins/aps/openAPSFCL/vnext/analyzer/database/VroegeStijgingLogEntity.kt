package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Diagnostiek voor vroegeStijgingBevestigd (18/09/2026) — eigen, kleine tabel
 * i.p.v. een uitbreiding van FCLCycleLogEntity, exact hetzelfde bewezen
 * patroon als PostHypoBrakeLogEntity/ExplosiveRiseLogEntity/TempOverrideLogEntity.
 *
 * AANLEIDING: bij het analyseren van vertraagd vurende afbouw-resets (Rick's
 * csv 17/9 19:13, Ecko's csv 17/9 18:29) bleken alle 6 losse voorwaarden van
 * vroegeStijgingBevestigd (FCLvNext.kt) op basis van de bestaande CSV-kolommen
 * al voldaan te lijken, ruim voordat de reset daadwerkelijk vuurde. Zonder
 * zicht op de 6 losse boolean-waarden zelf, EN het "al gebruikt deze
 * episode"-vlag, EN het reentry-signaal (isReentrySignal()) kon de precieze
 * blokkerende voorwaarde niet met zekerheid worden vastgesteld. Deze tabel
 * legt daarom alle 6 voorwaarden plus 2 gerelateerde signalen elke cyclus
 * vast waarin het commit-blok draait (dus niet op cycli waar de
 * commit-cooldown het hele blok overslaat — zie kdoc bij
 * vroegeStijgingBevestigdThisCycle in FCLvNext.kt voor hoe dat in de CSV
 * zichtbaar blijft als een ontbrekende/neutrale rij).
 *
 * Bij CSV-export (FCLCycleLogRepository.exportCsvLast7DaysInternal()) wordt
 * deze tabel, net als de andere 3 losse tabellen, op timestampMs samengevoegd
 * met fcl_cycle_log tot één regel — de gebruiker blijft met precies één
 * CSV-bestand werken. fcl_cycle_log zelf blijft onaangeroerd.
 *
 * LET OP: dit is puur diagnostisch, geen enkel veld hier beïnvloedt de
 * dosering — het is een kopie van waarden die al elders in FCLvNext.kt
 * berekend worden, alleen nu ook zichtbaar in de CSV.
 */
@Entity(
    tableName = "vroege_stijging_log",
    indices = [
        Index(value = ["timestampMs"])
    ]
)
data class VroegeStijgingLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val timestampMs: Long,
    // De volledige AND van alle 6 voorwaarden (sustained-duur, consistency,
    // acceleratie>0, !curveConfirmtOmslag, !recentSensorNoise,
    // !accelDecliningFromRisePeak) — zie vroegeStijgingBevestigd in FCLvNext.kt.
    val bevestigd: Boolean,
    // vroegeStijgingBevestigdUsedThisEpisode: was de reset al eerder deze
    // episode (volledig) toegekend, dus kan hij nu niet nogmaals vuren.
    val usedThisEpisode: Boolean,
    // vroegeStijgingRampFrac: 0.0 (nog niet/niet van toepassing) tot 1.0
    // (volledig opgebouwd) — zie de v120/v120b-kdoc bij lateDecayMul.
    val rampFrac: Double,
    // isReentrySignal() — apart mechanisme, geen onderdeel van
    // vroegeStijgingBevestigd zelf, maar wel een concurrerende verklaring
    // voor eenzelfde soort lateDecayMul-reset. Altijd gezet (buiten het
    // commit-cooldown-blok berekend), dus nooit een "ontbrekende" waarde.
    val reentryActive: Boolean,
    // De 3 losse sub-voorwaarden die vroegeStijgingBevestigd kunnen blokkeren
    // (naast sustained-duur/consistency/acceleratie>0, die al los in de
    // hoofd-CSV staan als sustained_high_slope_min/consistency/accel).
    val recentSensorNoise: Boolean,
    val accelDecliningFromRisePeak: Boolean,
    val curveConfirmtOmslag: Boolean
)
