import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

@Suppress("ConstPropertyName")
object Versions {

    // On change edit aaps-ci.yml
    // 09/09/2026 (de gebruiker) — nieuw schema: "4.0<letter>-v<generatie>-v<buildnummer>".
    // Het buildnummer loopt voortaan gelijk op met FCL_STATUS_VERSION (FCLvNextStatusFormatter.kt)
    // en FCL_CODE_VERSION (FCLvNext.kt) — alle drie samen ophogen bij elke FCLvNext-wijziging die
    // het waard is. Zichtbaar op het hoofdscherm via VersionOverlay.kt (config.VERSION_NAME).
    const val appVersion = "4.0C-v8-v108"

    // BEVROREN OP 1598 (07/09/2026, de gebruiker) — NIET MEER OPHOGEN.
    //
    // Was tot v98 gekoppeld aan het FCLvNext-versienummer (1500 +
    // FCLvNext-v, zie git-historie van dit bestand voor het oude schema).
    // AANLEIDING VOOR DE OMMEZWAAI: Android weigert principieel een apk met
    // een LAGER versionCode te installeren dan wat al staat
    // (INSTALL_FAILED_VERSION_DOWNGRADE, zichtbaar als "App niet
    // geïnstalleerd") — daardoor kon de "Versie wijzigen"-terugzet-knop
    // (FCLSettingsScreen.kt) nooit naar een oudere FCLvNext-versie
    // terugschakelen zodra er al een nieuwere geinstalleerd was, ook al was
    // dat precies het hele doel van die knop.
    //
    // OPLOSSING: dit versionCode blijft vanaf nu voor ALTIJD 1598. Elke
    // toekomstige FCLvNext-apk is voor Android dan exact "dezelfde versie"
    // (nooit een downgrade), dus mag altijd overheen geinstalleerd worden —
    // vooruit ÉN terug. Het FCLvNext-eigen versienummer (voor de
    // statusregel, de update-checker-vergelijking, en de
    // Drive-bestandsnaamconventie FCL-V7_v<nummer>.apk — GEEN +1500-offset
    // meer) leeft nu uitsluitend in FCL_STATUS_VERSION in
    // FCLvNextStatusFormatter.kt, en beweegt dus niet meer mee met dit getal.
    //
    // LET OP BIJ TOEKOMSTIG WERK: als een heel andere, niet-FCLvNext-
    // gerelateerde reden ooit een echte Android-versionCode-ophoging vereist
    // (bv. een Play Store-eis), mag dat — maar verhoog dit NOOIT meer als
    // routine-stap bij een gewone FCLvNext-codewijziging, dat zet deze hele
    // downgrade-fix weer teniet.
    const val versionCode = 1598

    const val compileSdk = 37
    const val minSdk = 30
    const val targetSdk = 35
    const val wearMinSdk = 30
    const val wearTargetSdk = 30

    val javaVersion = JavaVersion.VERSION_21
    val jvmTarget = JvmTarget.JVM_21
    const val jacoco = "0.8.11"
}
