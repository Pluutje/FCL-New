package app.aaps.plugins.aps.openAPSFCL.vnext

import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory bridge for the AAPS settings store (the same one the FCL settings screen writes to).
 *
 * The AI advisor screen is not injected, but it must be able to read and write the settings the
 * user can change himself (max SMB, max IOB). OpenAPSFCLPlugin sets the store once in its init
 * block. Same pattern as FclNotificationManagerBridge.
 */
object FclSettingsStoreBridge {

    private val current = AtomicReference<KeyValueStore?>(null)

    fun set(store: KeyValueStore) {
        current.set(store)
    }

    /** Can be null before the plugin is created. */
    fun get(): KeyValueStore? = current.get()
}
