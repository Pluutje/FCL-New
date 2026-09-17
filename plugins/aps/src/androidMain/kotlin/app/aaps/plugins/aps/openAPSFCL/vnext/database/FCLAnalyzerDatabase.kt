package app.aaps.plugins.aps.openAPSFCL.vnext.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.BasalProfileHistoryEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.EpisodeEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.NightWindowEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.ProfileAutoAdjustLogEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.IsfAutoAdjustLogEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.PostHypoBrakeLogEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.ExplosiveRiseLogEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.TempOverrideLogEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.VroegeStijgingLogEntity

// ── MIGRATION_16_17 (16/07/2026) ─────────────────────────────────────
// Zuiver additief: 6 nieuwe kolommen op de bestaande fcl_cycle_log-tabel.
// Kolomnamen zijn de Kotlin-veldnamen zelf (geen @ColumnInfo-overrides
// elders in FCLCycleLogEntity.kt, dus Room mapt 1-op-1). Type-mapping volgt
// Room's standaardconventie: String→TEXT, Boolean→INTEGER (0/1), Double→REAL.
// NOT NULL vereist een DEFAULT bij ADD COLUMN op een tabel met bestaande
// rijen — vandaar de expliciete defaults, die exact overeenkomen met de
// Kotlin-defaultwaarden in FCLCycleLogEntity.kt (val ... = "" / false / 0.0).
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE fcl_cycle_log ADD COLUMN codeVersion TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE fcl_cycle_log ADD COLUMN appRestartThisCycle INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE fcl_cycle_log ADD COLUMN aigfPct REAL NOT NULL DEFAULT 100.0")
        db.execSQL("ALTER TABLE fcl_cycle_log ADD COLUMN aigfActive INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE fcl_cycle_log ADD COLUMN aigfReason TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE fcl_cycle_log ADD COLUMN episodePeakCommitU REAL NOT NULL DEFAULT 0.0")
    }
}

// ── MIGRATION_17_18 (22/07/2026) ─────────────────────────────────────
// Zuiver additief: 1 nieuwe kolom (mealAggressionReason, TEXT) op de
// bestaande fcl_cycle_log-tabel — zelfde patroon als MIGRATION_16_17.
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE fcl_cycle_log ADD COLUMN mealAggressionReason TEXT NOT NULL DEFAULT ''")
    }
}

// ── MIGRATION_18_19 (24/07/2026) ─────────────────────────────────────
// Nieuwe, lege tabel voor FclNightBasalAutoAdjuster's logboek (dry-run en
// daadwerkelijk toegepaste automatische profielwijzigingen). Geen bestaande
// data betrokken — een gewone CREATE TABLE volstaat, geen ALTER TABLE nodig
// zoals bij de eerdere additieve migraties hierboven.
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `profile_auto_adjust_log` (" +
                "`id` INTEGER NOT NULL, " +
                "`timestampMs` INTEGER NOT NULL, " +
                "`localDate` TEXT NOT NULL, " +
                "`mode` TEXT NOT NULL, " +
                "`applied` INTEGER NOT NULL, " +
                "`skipReason` TEXT NOT NULL, " +
                "`oldBasalJson` TEXT NOT NULL, " +
                "`newBasalJson` TEXT NOT NULL, " +
                "`perHourShiftJson` TEXT NOT NULL, " +
                "`hoursAtCapCount` INTEGER NOT NULL, " +
                "`nightsAnalyzed` INTEGER NOT NULL, " +
                "`avgConfidence` REAL NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_auto_adjust_log_localDate` ON `profile_auto_adjust_log` (`localDate`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_auto_adjust_log_timestampMs` ON `profile_auto_adjust_log` (`timestampMs`)")
    }
}

// ── MIGRATION_19_20 (16/08/2026) ─────────────────────────────────────
// Zuiver additief: 3 nieuwe kolommen (aigfBPct/aigfBActive/aigfBReason) op de
// bestaande fcl_cycle_log-tabel — zelfde patroon als MIGRATION_16_17 (die
// destijds de A-kolommen toevoegde). AANLEIDING: bij het toevoegen van deze
// velden aan FCLCycleLogEntity.kt (HERONTWERP van AIGF component B, zie
// kdoc bij lastSmoothedAigfBPct in FCLvNext.kt) werd de @Database-versie
// hieronder aanvankelijk NIET meegehoogd — dat was een fout, ontdekt via een
// terechte controlevraag van de gebruiker vóórdat het geleverd werd. Zonder deze
// Migration (en de bijbehorende versiebump naar 20) zou Room bij de eerste
// databasetoegang na deze update gecrasht zijn: bij een ONGEWIJZIGD
// versienummer maar een gewijzigd schema slaat fallbackToDestructiveMigration
// niet aan (die vangt alleen een versie-sprong zonder migratiepad op) — Room
// had dan "schema gewijzigd zonder versiebump" gedetecteerd en een
// IllegalStateException gegooid bij elke start.
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE fcl_cycle_log ADD COLUMN aigfBPct REAL NOT NULL DEFAULT 100.0")
        db.execSQL("ALTER TABLE fcl_cycle_log ADD COLUMN aigfBActive INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE fcl_cycle_log ADD COLUMN aigfBReason TEXT NOT NULL DEFAULT ''")
    }
}


// ── MIGRATION_20_21 (16/08/2026) ────────────────────────────────────────
// Nieuwe, lege tabel voor FclIsfAutoAdjuster's logboek (ISF-tegenhanger van
// profile_auto_adjust_log/MIGRATION_18_19 hierboven — zelfde reden, zelfde
// patroon: een gewone CREATE TABLE volstaat, geen bestaande data betrokken).
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `isf_auto_adjust_log` (" +
                "`id` INTEGER NOT NULL, " +
                "`timestampMs` INTEGER NOT NULL, " +
                "`localDate` TEXT NOT NULL, " +
                "`mode` TEXT NOT NULL, " +
                "`applied` INTEGER NOT NULL, " +
                "`skipReason` TEXT NOT NULL, " +
                "`oldIsfJson` TEXT NOT NULL, " +
                "`newIsfJson` TEXT NOT NULL, " +
                "`perHourShiftJson` TEXT NOT NULL, " +
                "`hoursAtCapCount` INTEGER NOT NULL, " +
                "`samplesAnalyzed` INTEGER NOT NULL, " +
                "`avgConfidence` REAL NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_isf_auto_adjust_log_localDate` ON `isf_auto_adjust_log` (`localDate`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_isf_auto_adjust_log_timestampMs` ON `isf_auto_adjust_log` (`timestampMs`)")
    }
}

// ── MIGRATION_21_22 (26/08/2026) ─────────────────────────────────────
// Nieuwe, lege tabel voor de post-hypo-brake-diagnostiek (postHypoBrakeActive/
// postHypoBrakeArmedMinutes) — zie kdoc bij PostHypoBrakeLogEntity voor de
// volledige aanleiding: dezelfde 2 velden rechtstreeks aan fcl_cycle_log
// toevoegen (via ALTER TABLE, het patroon van bijv. MIGRATION_19_20) gaf op
// het toestel een reproduceerbare VerifyError-crash bij het opstarten van de
// app, bevestigd via een gecontroleerde A/B-test op het toestel zelf. Zuiver
// additief (nieuwe tabel, geen wijziging aan bestaande tabellen) — zelfde,
// twee keer eerder bewezen patroon als MIGRATION_18_19/MIGRATION_20_21.
// fcl_cycle_log blijft in deze migratie volledig onaangeroerd.
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `post_hypo_brake_log` (" +
                "`id` INTEGER NOT NULL, " +
                "`timestampMs` INTEGER NOT NULL, " +
                "`active` INTEGER NOT NULL, " +
                "`armedMinutes` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_post_hypo_brake_log_timestampMs` ON `post_hypo_brake_log` (`timestampMs`)")
    }
}

// ── MIGRATION_22_23 (3-4/9/2026) ─────────────────────────────────────
// Nieuwe, lege tabel voor de EXPLOSIVE_RISE-boost-diagnostiek (frac/mul/
// active/projectedMinNoInsulin) — zelfde bewezen patroon als
// MIGRATION_21_22 hierboven (post_hypo_brake_log): een gewone CREATE TABLE,
// geen wijziging aan fcl_cycle_log zelf, dus geen risico op de VerifyError
// die MIGRATION_21_22's aanleiding was. Zie kdoc bij ExplosiveRiseLogEntity.
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `explosive_rise_log` (" +
                "`id` INTEGER NOT NULL, " +
                "`timestampMs` INTEGER NOT NULL, " +
                "`frac` REAL NOT NULL, " +
                "`mul` REAL NOT NULL, " +
                "`active` INTEGER NOT NULL, " +
                "`projectedMinNoInsulin` REAL NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_explosive_rise_log_timestampMs` ON `explosive_rise_log` (`timestampMs`)")
    }
}

// ── MIGRATION_23_24 (11/09/2026) ─────────────────────────────────────
// Nieuwe, lege tabel voor de Temp Override-diagnostiek (targetPct/
// effectiveMul/remainingMinutes) — zelfde bewezen patroon als MIGRATION_22_23
// hierboven (explosive_rise_log): een gewone CREATE TABLE, geen wijziging aan
// fcl_cycle_log zelf, dus geen risico op de VerifyError die MIGRATION_21_22's
// aanleiding was. Zie kdoc bij TempOverrideLogEntity / FclTempOverrideSettings.
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `temp_override_log` (" +
                "`id` INTEGER NOT NULL, " +
                "`timestampMs` INTEGER NOT NULL, " +
                "`active` INTEGER NOT NULL, " +
                "`targetPct` INTEGER NOT NULL, " +
                "`effectiveMul` REAL NOT NULL, " +
                "`remainingMinutes` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_temp_override_log_timestampMs` ON `temp_override_log` (`timestampMs`)")
    }
}

// ── MIGRATION_24_25 (18/09/2026) ─────────────────────────────────────
// Nieuwe, lege tabel voor de vroegeStijgingBevestigd-diagnostiek (bevestigd/
// usedThisEpisode/rampFrac/reentryActive/recentSensorNoise/
// accelDecliningFromRisePeak/curveConfirmtOmslag) — zelfde bewezen patroon
// als MIGRATION_23_24 hierboven (temp_override_log): een gewone CREATE
// TABLE, geen wijziging aan fcl_cycle_log zelf, dus geen risico op de
// VerifyError die MIGRATION_21_22's aanleiding was. Zie kdoc bij
// VroegeStijgingLogEntity.
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `vroege_stijging_log` (" +
                "`id` INTEGER NOT NULL, " +
                "`timestampMs` INTEGER NOT NULL, " +
                "`bevestigd` INTEGER NOT NULL, " +
                "`usedThisEpisode` INTEGER NOT NULL, " +
                "`rampFrac` REAL NOT NULL, " +
                "`reentryActive` INTEGER NOT NULL, " +
                "`recentSensorNoise` INTEGER NOT NULL, " +
                "`accelDecliningFromRisePeak` INTEGER NOT NULL, " +
                "`curveConfirmtOmslag` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_vroege_stijging_log_timestampMs` ON `vroege_stijging_log` (`timestampMs`)")
    }
}

@Database(
    entities = [
        FCLCycleLogEntity::class,
        EpisodeEntity::class,
        NightWindowEntity::class,
        BasalProfileHistoryEntity::class,
        ProfileAutoAdjustLogEntity::class,
        IsfAutoAdjustLogEntity::class,
        PostHypoBrakeLogEntity::class,
        ExplosiveRiseLogEntity::class,
        TempOverrideLogEntity::class,
        VroegeStijgingLogEntity::class
    ],
    // v13→v15 (05/07/2026): +curveFitR2/+curveAcceleration/+toppingOutBoost
    // (in TrendsFields), en FCLCycleLogEntity herstructureerd in @Embedded-
    // groepen om de eerdere VerifyError-crash (registerlimiet op de platte
    // ~150-parameter constructor) structureel uit te sluiten.
    //
    // BEWUST v15, NIET v14: een eerdere, teruggedraaide poging declareerde al
    // versie 14 (met de kapotte platte constructor). Room's schema-creatie
    // (CREATE TABLE) gebeurt via annotatie-metadata, niet via de Kotlin-
    // constructor — dus die stap kan toen best geslaagd zijn vóórdat de
    // VerifyError bij het eerste schrijfmoment optrad. Het toestel kan dus al
    // ergens op user_version=14 staan. Room vergelijkt alleen het versienummer
    // met wat er nu gedeclareerd is; bij een match slaat het de migratie
    // (en dus fallbackToDestructiveMigration) gewoon OVER. v15 garandeert een
    // echte version-mismatch en dus een gegarandeerde schone migratie,
    // ongeacht de staat waarin het toestel nu verkeert.
    //
    // v15→v16 (11/07/2026): +bgStijgtNogFors/+commitNrUsed (in
    // ForensicFields) — twee nieuwe, puur diagnostische kolommen. Ook een
    // toevoeging met default-waarden vereist een versiebump: fallback-
    // ToDestructiveMigration werkt op VERSIENUMMER-mismatch, niet op een
    // inhoudelijke schema-diff — bij een ongewijzigd versienummer had Room de
    // migratie simpelweg overgeslagen en het oude, incompatibele schema laten
    // staan (zie de uitleg bij v15 hierboven — exact hetzelfde risico).
    // GEVOLG: bij de eerste start na deze update wordt de cycle-log/episode/
    // night-window/basal-profile-geschiedenis in de Room-database gewist
    // (dropAllTables=true) en leeg opnieuw opgebouwd.
    //
    // CORRECTIE (11/07/2026): eerdere aanname dat de CSV-bestanden
    // hierbuiten zouden staan was ONJUIST — exportCsvLast7Days() in
    // FCLCycleLogRepository.kt bouwt FCLvNext_Log_v8.csv bij elke export
    // volledig opnieuw op vanuit dao.getSince() (Room als bron, niet als
    // spiegel). Na deze migratie is Room dus leeg, en de eerstvolgende
    // exportronde na de wipe overschrijft de bestaande CSV met (aanvankelijk
    // vrijwel) niets — de meerdaagse geschiedenis in die CSV gaat feitelijk
    // ook verloren, niet alleen de Room-tabellen zelf.
    //
    // v16→v17 (16/07/2026): +codeVersion/+appRestartThisCycle/+aigfPct/
    // +aigfActive/+aigfReason/+episodePeakCommitU in FCLCycleLogEntity — zie
    // de kdoc daar. ANDERS DAN alle eerdere bumps hierboven: dit is zuiver
    // ADDITIEF (alleen nieuwe kolommen, niets hernoemd/verwijderd/van type
    // veranderd), dus deze keer een ECHTE Migration (MIGRATION_16_17
    // hieronder, ALTER TABLE ... ADD COLUMN) i.p.v. destructieve wipe — de
    // 90 dagen cycle-log/episode/night-window/basalprofiel-geschiedenis
    // blijft nu dus intact. Historische rijen van vóór v17 krijgen de
    // DEFAULT-waarden uit de ALTER TABLE-statements (lege string/0/false)
    // voor de 6 nieuwe kolommen — geen dataverlies, wel logisch dat oude
    // rijen op "geen AIGF-data"/"onbekende code-versie" uitkomen.
    // RISICO (de gebruiker, expliciet): deze Migration kon niet op een echt toestel
    // getest worden. fallbackToDestructiveMigration hieronder blijft daarom
    // als vangnet staan — als de Migration onverhoopt niet toepasbaar blijkt
    // (bijv. door een OEM-SQLite-eigenaardigheid), valt Room automatisch
    // terug op de bekende, werkende destructieve wipe i.p.v. te crashen.
    // v18→v19 (24/07/2026): +profile_auto_adjust_log (nieuwe, lege tabel
    // voor FclNightBasalAutoAdjuster). Zuiver additief (nieuwe tabel, geen
    // wijziging aan bestaande tabellen) — MIGRATION_18_19 hierboven, geen
    // dataverlies voor de bestaande geschiedenis.
    // v19→v20 (16/08/2026): +aigfBPct/+aigfBActive/+aigfBReason in
    // FCLCycleLogEntity — zie MIGRATION_19_20 hierboven. Zuiver additief,
    // geen dataverlies voor de bestaande geschiedenis.
    // v20→v21 (16/08/2026): +isf_auto_adjust_log (nieuwe, lege tabel voor
    // FclIsfAutoAdjuster). Zuiver additief (nieuwe tabel, geen wijziging aan
    // bestaande tabellen) — MIGRATION_20_21 hierboven, geen dataverlies voor
    // de bestaande geschiedenis. Ditmaal WEL meteen de Migration + versiebump
    // in dezelfde stap toegevoegd (zie MIGRATION_19_20 hierboven voor de
    // eerdere keer dat dit werd vergeten — dat mag niet nog een keer gebeuren).
    // v21→v22 (26/08/2026): +post_hypo_brake_log (nieuwe, lege tabel voor de
    // post-hypo-brake-diagnostiek). Zuiver additief (nieuwe tabel, geen
    // wijziging aan bestaande tabellen, dus ook fcl_cycle_log blijft exact
    // zoals in v21) — MIGRATION_21_22 hierboven. Zie kdoc bij
    // PostHypoBrakeLogEntity voor de aanleiding (VerifyError-crash toen deze
    // velden via een ALTER TABLE op fcl_cycle_log zelf gingen, in de nooit
    // uitgeleverde v82-poging).
    // v22->v23 (3-4/9/2026): +explosive_rise_log (nieuwe, lege tabel voor de
    // EXPLOSIVE_RISE-boost-diagnostiek). Zuiver additief (nieuwe tabel, geen
    // wijziging aan bestaande tabellen, dus ook fcl_cycle_log blijft exact
    // zoals in v22) — MIGRATION_22_23 hierboven, zelfde bewezen patroon als
    // v21->v22. Zie kdoc bij ExplosiveRiseLogEntity voor de aanleiding.
    // v23->v24 (11/09/2026): +temp_override_log (nieuwe, lege tabel voor de
    // Temp Override-diagnostiek). Zuiver additief (nieuwe tabel, geen
    // wijziging aan bestaande tabellen, dus ook fcl_cycle_log blijft exact
    // zoals in v23) — MIGRATION_23_24 hierboven, zelfde bewezen patroon als
    // v22->v23. Zie kdoc bij TempOverrideLogEntity voor de aanleiding.
    // v24->v25 (18/09/2026): +vroege_stijging_log (nieuwe, lege tabel voor de
    // vroegeStijgingBevestigd-diagnostiek). Zuiver additief (nieuwe tabel,
    // geen wijziging aan bestaande tabellen, dus ook fcl_cycle_log blijft
    // exact zoals in v24) — MIGRATION_24_25 hierboven, zelfde bewezen
    // patroon als v23->v24. Zie kdoc bij VroegeStijgingLogEntity voor de
    // aanleiding (Rick's csv 17/9 19:13, Ecko's csv 17/9 18:29).
    version = 25,
    exportSchema = false
)
abstract class FCLAnalyzerDatabase : RoomDatabase() {

    abstract fun cycleLogDao(): FCLCycleLogDao
    abstract fun episodeDao(): app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.EpisodeDao
    abstract fun nightWindowDao(): app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.NightWindowDao
    abstract fun basalProfileHistoryDao(): app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.BasalProfileHistoryDao
    abstract fun profileAutoAdjustLogDao(): app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.ProfileAutoAdjustLogDao
    abstract fun isfAutoAdjustLogDao(): app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.IsfAutoAdjustLogDao
    abstract fun postHypoBrakeLogDao(): app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.PostHypoBrakeLogDao
    abstract fun explosiveRiseLogDao(): app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.ExplosiveRiseLogDao
    abstract fun tempOverrideLogDao(): app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.TempOverrideLogDao
    abstract fun vroegeStijgingLogDao(): app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.VroegeStijgingLogDao

    companion object {
        private const val DB_NAME = "fcl_analyzer.db"

        @Volatile
        private var INSTANCE: FCLAnalyzerDatabase? = null

        fun getInstance(context: Context): FCLAnalyzerDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FCLAnalyzerDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }

        fun cutoffMs(): Long =
            System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000L
    }
}