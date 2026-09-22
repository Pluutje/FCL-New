package app.aaps.plugins.aps.openAPSFCL.update

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * 22/09/2026 (de gebruiker) — Herkent of deze APK ondertekend is met de eigen release-keystore
 * van de FCLvNext-auteur. Bewust NIET gebruikt om de hele update-sectie te verbergen: controleren
 * op updates ("Controleer nu", de achtergrondcheck in FclUpdateScheduler) en "Wat is nieuw" moeten
 * altijd blijven werken, ook op een zelfgebouwde apk. Alleen het daadwerkelijk INSTALLEREN wordt
 * geblokkeerd (zie de "Installeren"-knop in FCLSettingsScreen.kt) met een duidelijke foutmelding,
 * want Android weigert een installatie met een andere handtekening toch altijd (zie §6.15 van de
 * handleiding) — beter een nette melding vooraf dan een cryptische Android-installatiefout.
 *
 * Dit is GEEN beveiliging tegen misbruik: de hash hieronder is publieke informatie (een SHA-256
 * van het publieke certificaat, geen geheime sleutel/wachtwoord), en iemand die de broncode zelf
 * bouwt kan deze check net zo makkelijk weer verwijderen. Vergelijkbaar met het al bestaande
 * patroon in SignatureVerifierPlugin (module plugins:constraints) — daar een DENYLIST van
 * ingetrokken certificaten, hier een ALLOWLIST van precies één vertrouwd certificaat. Bewust GEEN
 * nieuwe module-dependency op plugins:constraints toegevoegd voor zo'n kleine, op zichzelf staande
 * check (zie CLAUDE.md "Avoid adding new inter-module dependencies") — de handtekening-uitlees-
 * logica hieronder is een paar regels en simpelweg gedupliceerd.
 */
object FclTrustedBuild {

    // TODO(gebruiker): vul hier de SHA-256 fingerprint in van JOUW eigen release-keystore-
    // certificaat in (hoofdletterongevoelig, met of zonder ":"-scheidingstekens — beide werken).
    // Zo kom je aan die waarde, kies één van de twee:
    //  1) Vanaf een toestel waar de "echte", door jou ondertekende app al op staat:
    //       adb shell pm path <package-naam>          (vind het apk-pad op het toestel)
    //       adb pull <dat-pad> app-echt.apk
    //       keytool -printcert -jarfile app-echt.apk    (lees de regel "SHA256:" af)
    //  2) Rechtstreeks uit je keystore-bestand:
    //       keytool -list -v -keystore <pad-naar-keystore> -alias <jouw-alias>
    //       (lees de regel "SHA256:" af onder "Certificate fingerprints")
    // Zolang dit leeg ("") is, staat de check UIT: de update-UI blijft dan voor iedereen zichtbaar,
    // exact zoals voorheen (fail-open — er verandert dus niets totdat je de echte hash invult).
    // 22/09/2026 (de gebruiker) — ingevuld: SHA-256 van mynewkeystore.jks, alias "key0"
    // (Owner: CN=Ecko Schuil, geldig t/m 2047). Dit is het publieke certificaat-fingerprint,
    // GEEN wachtwoord of privésleutel — veilig om hier in bronvorm te staan.
    private const val TRUSTED_SHA256_FINGERPRINT = "BDA0817C5A3C0B2934907A11C5BB7458E008E8EF42B4B877D5C875810561C0C2"

    /**
     * True als deze APK ondertekend is met [TRUSTED_SHA256_FINGERPRINT], of als die constante nog
     * leeg is. Ook true bij een onverwachte fout tijdens het uitlezen (fail-open): een fout hier
     * mag de update-functie niet permanent verbergen voor de echte, vertrouwde build — het ergste
     * gevolg van fail-open is dat een niet-vertrouwde build de knop nog even ziet, niet meer dan
     * dat (zie kdoc hierboven: geen echte beveiliging, dus geen reden om fail-closed te gaan).
     */
    fun isTrustedBuild(context: Context): Boolean {
        if (TRUSTED_SHA256_FINGERPRINT.isBlank()) return true
        val target = TRUSTED_SHA256_FINGERPRINT.replace(":", "").replace(" ", "").lowercase()
        return try {
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")
            val signatures = context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures
            signatures?.any { signature ->
                val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                digest.toHex() == target
            } ?: true
        } catch (e: Exception) {
            true
        }
    }

    private fun ByteArray.toHex(): String {
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val i = b.toInt() and 0xFF
            sb.append(hexChars[i shr 4])
            sb.append(hexChars[i and 0x0F])
        }
        return sb.toString()
    }
}
