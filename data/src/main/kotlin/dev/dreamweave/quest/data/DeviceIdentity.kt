// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data

import android.content.Context
import dev.dreamweave.quest.core.Ids

/**
 * This install's device id (UUIDv7), used in every event it writes. Stored in
 * `sync_secrets.xml`, which is excluded from Android backup: a restored copy of the app
 * on another phone gets its own id, so two live devices never share one.
 */
object DeviceIdentity {
    const val PREFS = "sync_secrets"
    private const val KEY = "device_id"

    @Synchronized
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val id = Ids.newId()
        prefs.edit().putString(KEY, id).commit()
        return id
    }
}
