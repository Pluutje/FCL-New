import java.util.Properties

plugins {
    id("kmp-test-defaults")
    kotlin("multiplatform")
    // NOT com.android.library. AGP 9 refuses that plugin together with the multiplatform plugin.
    // Same reason as the :core modules and the other converted plugins.
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    // Metro, so this module can wire its own Android entry points.
    alias(libs.plugins.metro)
    // FCLvNext's own analyzer/persist Room databases live in androidMain (see the dependencies
    // block at the bottom) — this plugin runs Room's annotation processor for that target.
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.incremental", "true")
}

// ── FCLvNext update-checker + CSV-upload geheimen: local.properties inlezen ──
// Drive-map-ID + API-key + CSV-upload-URL/secret komen NOOIT in broncode: alleen uit
// local.properties (staat al buiten versiebeheer). Hoe ze daarna bij de plugin komen
// (zonder klassieke BuildConfig, want dit is een KMP-module) staat bij generateFclSecrets
// hieronder.
//
// BUGFIX: "java.util.Properties()" gaf hier "Unresolved reference 'util'" —
// een van de toegepaste plugins/convention-plugins registreert kennelijk een
// "java"-extensie op Project (JavaPluginExtension), waardoor "java" in dit
// script naar díe extensie resolveert i.p.v. het java-package. Met een
// expliciete import speelt dat niet.
val fclUpdateLocalProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// ── FCLvNext update-checker + CSV-upload geheimen (06-07/09/2026, de gebruiker; herzien
// 09/09/2026 voor de KMP-migratie) ──────────────────────────────────────────────────────
// De klassieke AGP `buildFeatures { buildConfig = true }` / `defaultConfig { buildConfigField }`
// bestaan niet in de KMP `kotlin { android { ... } }`-DSL (zie GenerateFclSecretsTask voor de
// volledige uitleg). Zelfde patroon als generateApsStrings hieronder: een klein gegenereerd
// Kotlin-object i.p.v. BuildConfig. Geheimen komen NOOIT in broncode: alleen uit
// local.properties (staat al buiten versiebeheer). Ontbreken beide, dan blijven de velden leeg
// en geeft FclUpdateChecker.checkForUpdate() gewoon NotConfigured terug — geen crash.
val generateFclSecrets = tasks.register<GenerateFclSecretsTask>("generateFclSecrets") {
    driveUpdateFolderId.set(fclUpdateLocalProperties.getProperty("driveUpdateFolderId", ""))
    driveUpdateApiKey.set(fclUpdateLocalProperties.getProperty("driveUpdateApiKey", ""))
    csvUploadUrl.set(fclUpdateLocalProperties.getProperty("fclCsvUploadUrl", ""))
    csvUploadSecret.set(fclUpdateLocalProperties.getProperty("fclCsvUploadSecret", ""))
    packageName.set("app.aaps.plugins.aps")
    outputDir.set(layout.buildDirectory.dir("generated/fclSecrets/android"))
}

// Same generator as the other converted plugins, pointed at this module's strings.
val generateApsStrings = tasks.register<GenerateKeyStringsTask>("generateApsStrings") {
    resDir.set(layout.projectDirectory.dir("src/androidMain/res"))
    packageName.set("app.aaps.plugins.aps")
    owner.set("aps")
    objectName.set("ApsStrings")
    idsObjectName.set("ApsStringIds")
    reportFile.set(layout.buildDirectory.file("reports/apsStrings/translations.txt"))
    // Set explicitly: addGeneratedSourceDirectory derives its convention from the task name, so both
    // properties would land on one directory and the second file written would delete the first.
    commonOutputDir.set(layout.buildDirectory.dir("generated/apsStrings/common"))
    androidOutputDir.set(layout.buildDirectory.dir("generated/apsStrings/android"))
}

kotlin {
    android {
        namespace = "app.aaps.plugins.aps"
        compileSdk = Versions.compileSdk
        minSdk = Versions.minSdk
        androidResources { enable = true }
        // isIncludeAndroidResources is what makes Robolectric work - see :core:ui for the detail.
        withHostTest {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        compilerOptions { jvmTarget.set(Versions.jvmTarget) }

        lint {
            checkReleaseBuilds = false
            disable += "MissingTranslation"
            disable += "ExtraTranslation"
        }
    }

    iosArm64()
    iosSimulatorArm64()

    // Desktop (Windows/macOS/Linux). Compose Multiplatform resolves its `desktop` variant from a
    // plain jvm() target, so no special target name is needed.
    jvm()

    sourceSets {
        commonMain {
            kotlin.srcDir(generateApsStrings.flatMap { it.commonOutputDir })
            dependencies {
                implementation(project(":core:data"))
                implementation(project(":core:interfaces"))
                implementation(project(":core:keys"))
                implementation(project(":core:nssdk"))
                implementation(project(":core:objects"))
                implementation(project(":core:utils"))
                implementation(project(":core:ui"))

                implementation(libs.androidx.collection)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.cmp.runtime)
                api(kotlin("reflect"))
            }
        }

        androidMain {
            // Android only: the string name to R.string id map.
            kotlin.srcDir(generateApsStrings.flatMap { it.androidOutputDir })
            // Android only: the update-checker/CSV-upload secrets (stand-in for BuildConfig).
            kotlin.srcDir(generateFclSecrets.flatMap { it.outputDir })
            dependencies {
                implementation(project(":core:graph"))
                implementation(libs.androidx.compose.ui.tooling.preview)
                implementation(libs.androidx.work.runtime)
                implementation(libs.org.slf4j.api)
                // APS (it should be androidTestImplementation but it doesn't work)
                runtimeOnly(libs.org.mozilla.rhino)

                // FCLvNext's own analyzer/persist databases (vnext/database, vnext/persist,
                // vnext/analyzer/database) — Android only, no iOS/desktop target needed, so this
                // stays out of commonMain. See the kspAndroid processor at the bottom of this file.
                api(libs.androidx.room.runtime)
            }
        }

        // Hand written rather than taken from test-module-dependencies, which applies
        // com.android.library and so cannot be used here. Same approach as :plugins:main.
        // Tests of commonMain classes belong here, not in androidHostTest: that source set runs on the
        // JVM only, so code that ships to iOS would be verified on Android alone. Mockito is JVM
        // only, so anything moved here uses hand written fakes instead.
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        getByName("androidHostTest") {
            dependencies {
                implementation(project(":shared:tests"))
                implementation(project(":pump:virtual"))
                implementation(libs.org.junit.jupiter)
                implementation(libs.org.junit.jupiter.api)
                implementation(libs.org.mockito.junit.jupiter)
                implementation(libs.org.mockito.kotlin)
                implementation(libs.com.google.truth)
                implementation(libs.kotlinx.coroutines.test)
                // Compose UI tests (AutotuneScreenTest). Restated from compose-test-module-dependencies,
                // which applies com.android.library and so cannot be used here.
                implementation(project.dependencies.platform(libs.androidx.compose.bom))
                implementation(libs.androidx.compose.ui.test.junit4)
                implementation(libs.androidx.compose.ui.test.manifest)
                implementation(libs.org.robolectric)
                // The real org.json: isReturnDefaultValues makes the platform stub answer null rather
                // than throwing, which NPEs the shared profile fixtures.
                implementation(libs.org.json.android)
                runtimeOnly(libs.org.mozilla.rhino)
                runtimeOnly(libs.org.junit.vintage.engine)
                runtimeOnly(libs.org.junit.platform.launcher)
            }
        }
    }
}

dependencies {
    // Room generates the database initialiser for FCLvNext's own analyzer/persist databases.
    // Android only: those entities/DAOs live in src/androidMain, not commonMain (see above).
    add("kspAndroid", libs.androidx.room.compiler)
}
