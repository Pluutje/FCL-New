package app.aaps.plugins.aps.openAPSFCL.vnext.persist

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Eén regel per cyclus — sinds 21/09/2026 (de gebruiker) ELKE cyclus, niet meer
 * alleen die met `active == true`. Aanleiding: een 2+ uur durend Bg-plateau
 * (21/9 7:54-10:04, ruim boven target) waarbij nauwelijks werd bijgedoseerd.
 * Vanuit alleen de hoofd-CSV was niet te zien of de persistent controller
 * daar wel/niet bevestigde, en waarom niet — de "building (x/y)"-cycli
 * (persistentCandidate nog niet lang genoeg aaneengesloten) werden helemaal
 * niet gelogd, dus het aftellen/resetten van persistentCounter was
 * onzichtbaar. `active`/`accel`/`consistency`/`reason` zijn daarom toegevoegd
 * zodat een volgend vergelijkbaar plateau direct te herleiden is: bouwt de
 * teller op, wordt hij ergens teruggezet, en door welke voorwaarde precies.
 *
 * Losstaande tabel/database t.o.v. fcl_analyzer.db: een toekomstige schema-
 * wijziging hier mag de hoofd-CSV-data (7 dagen cyclus-log) niet wegvallen.
 *
 * De V-learner leest deze tabel, groepeert opeenvolgende rijen (geen gat
 * groter dan cooldownCycles+1 cycli) tot clusters, en evalueert per cluster
 * het FORWARD/BACK/NONE-signaal op basis van de slope 10 minuten na elke fire.
 * Rijen met active=false (buiten een cluster) worden door die groepering
 * vanzelf genegeerd — deze uitbreiding raakt de V-learner dus niet.
 */
@Entity(tableName = "fcl_persist_event")
data class FCLPersistEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val timestampMs: Long,

    // ── Toestand op het moment van deze cyclus ─────────────────────────
    val bgMmol: Double,
    val targetMmol: Double,
    val deltaToTarget: Double,
    val slope: Double,
    val iobRatio: Double,

    // 21/09/2026 (de gebruiker) — nodig om persistentCandidate's abs(accel)<=
    // stableAccelAbs en consistency>=minConsistency zelf te kunnen narekenen;
    // zonder deze twee was alleen slope/deltaToTarget/iobRatio te toetsen.
    val accel: Double = 0.0,
    val consistency: Double = 0.0,

    // ── Resultaat van tickAndMaybeFire() ────────────────────────────────
    val fired: Boolean,
    val doseU: Double,
    val cooldownLeft: Int,
    val persistentCounter: Int,
    val escalationFactor: Double,

    // 21/09/2026 (de gebruiker) — active apart opgeslagen (i.p.v. impliciet
    // "elke rij is active", nu ook niet-actieve/bouwende cycli gelogd worden),
    // plus de letterlijke reason-tekst uit Result (bv. "PERSIST: building
    // (1/2)") zodat de teller-opbouw en -reset direct afleesbaar zijn zonder
    // alle drempels handmatig te moeten narekenen.
    val active: Boolean = false,
    val reason: String = "",

    // ── Effectieve detectieparameters op dit moment (voor V-learner) ───
    // Nodig om de respons-drempel (-stableSlopeAbs * responseFactor en -0.60)
    // te herberekenen zonder afhankelijk te zijn van de actuele config.
    val effectiveMinDelta: Double,
    val stableSlopeAbs: Double,

    // ── Huidige geleerde V-waarde op moment van deze cyclus ─────────────
    // (voor traceability: welke vExtra was actief toen dit gevuurd werd)
    val vExtraAtFire: Double
)
