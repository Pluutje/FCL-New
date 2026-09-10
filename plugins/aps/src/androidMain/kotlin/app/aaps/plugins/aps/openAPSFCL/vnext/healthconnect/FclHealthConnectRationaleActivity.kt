package app.aaps.plugins.aps.openAPSFCL.vnext.healthconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * FCLvNext Health Connect rationale-scherm (10/09/2026, de gebruiker).
 *
 * NIET optioneel — Google's eigen "Get started with Health Connect"-gids
 * (developer.android.com/health-and-fitness/health-connect/get-started,
 * sectie "Show your app's privacy policy dialog") zegt expliciet dat zonder
 * deze activity + de activity-alias hieronder in AndroidManifest.xml het
 * Health Connect-toestemmingsscherm HELEMAAL NIET opent. Eerst zonder deze
 * activity gebouwd (10/09/2026) — precies dat gebeurde: de "Toegang
 * verlenen"-knop in FCLSettingsScreen.kt reageerde niet, en de app verscheen
 * niet eens in Health Connects eigen "Verbonden apps"-lijst.
 *
 * Toont een korte, eigen uitleg (geen externe hosted privacybeleid-URL nodig
 * — dit is geen Play Store-app) van wat er met de stappen/hartslag-data
 * gebeurt. Wordt door Health Connect zelf geopend als de gebruiker op de
 * "privacybeleid"-link in het toestemmingsscherm klikt, of via de
 * "Meer info"/"Manage access"-koppeling in Health Connects instellingen.
 *
 * Manifest-registratie: app/src/main/AndroidManifest.xml (deze module heeft
 * zelf geen AndroidManifest.xml, zie de KMP-migratie-kdoc's elders).
 */
class FclHealthConnectRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RationaleScreen(onClose = { finish() }) }
    }
}

@Composable
private fun RationaleScreen(onClose: () -> Unit) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Health Connect — FCLvNext", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "FCLvNext leest je stappen en hartslag uit Health Connect, uitsluitend " +
                        "om de insulinedosering lokaal op je telefoon te verfijnen (AIGF-" +
                        "activiteitsherkenning). Deze gegevens verlaten je telefoon nooit " +
                        "automatisch — alleen als je zelf bewust een CSV-export deelt via de " +
                        "bestaande \"CSV delen\"-functie in de FCLvNext-instellingen, en dan " +
                        "zonder de ruwe Health Connect-gegevens zelf.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onClose) { Text("Sluiten") }
            }
        }
    }
}
