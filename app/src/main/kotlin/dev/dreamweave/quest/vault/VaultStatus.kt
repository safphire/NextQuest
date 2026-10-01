// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.vault

import android.content.Context
import android.content.SharedPreferences

/**
 * The last vault problem, for Settings. Kept in the vault package's own small
 * preferences file; it holds a user-facing message only, nothing secret.
 */
object VaultStatus {
    const val PREFS = "vault_status"
    const val KEY_ERROR = "last_error"
    private const val KEY_ERROR_AT = "last_error_at"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastError(context: Context): String? = prefs(context).getString(KEY_ERROR, null)

    fun recordError(context: Context, message: String, now: Long = System.currentTimeMillis()) {
        prefs(context).edit().putString(KEY_ERROR, message).putLong(KEY_ERROR_AT, now).apply()
    }

    fun clearError(context: Context) {
        prefs(context).edit().remove(KEY_ERROR).remove(KEY_ERROR_AT).apply()
    }

    /** A message fit for the user: our own wording, never a raw path or stack detail. */
    fun describe(what: String, e: Throwable): String = when (e) {
        is VaultException -> "$what: ${e.message}"
        is SecurityException -> "$what: folder access lost. Choose the vault folder again."
        else -> "$what: couldn't write to the vault folder. Is it still there?"
    }
}
