// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data.sync

import android.content.Context
import dev.dreamweave.quest.data.DeviceIdentity

/** Where the device token lives. An interface so tests don't need SharedPreferences. */
interface TokenStore {
    var token: String?
}

/**
 * The device token in `sync_secrets.xml`, next to the device id and likewise excluded
 * from Android backup: a restored install must pair again rather than reuse a token.
 */
class SyncCredentials(context: Context) : TokenStore {
    private val prefs = context.applicationContext.getSharedPreferences(DeviceIdentity.PREFS, Context.MODE_PRIVATE)

    override var token: String?
        get() = prefs.getString(KEY, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.commit()
        }

    private companion object {
        const val KEY = "device_token"
    }
}
