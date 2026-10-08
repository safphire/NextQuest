// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Device-local preferences. Anything that must be identical on every device (the slot
 * cap) is an event instead, see docs/DECISIONS.md D-107.
 */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val digestEnabled: Boolean = true,
    /** Minutes after local midnight. Default 08:00. */
    val digestMinutes: Int = 8 * 60,
    val streakGuardEnabled: Boolean = true,
    /** Default 21:00. */
    val streakGuardMinutes: Int = 21 * 60,
    val vaultUri: String? = null,
    val lastVaultSnapshotAt: Long? = null,
    val onboardingDone: Boolean = false,
    val resumeDismissedAt: Long = 0,
    val notificationPermissionAsked: Boolean = false,
    val serverUrl: String? = null,
    val syncCursor: Long = 0,
    /** Id of the event at [syncCursor] on the server: a different one there means the server was reset or restored. */
    val syncCursorEventId: String? = null,
    /**
     * The highest server cursor this phone's pushes reached, and the id of the event the
     * server holds there. A server restored to a backup taken between our last pull and
     * those pushes no longer has them: its latest cursor is lower, or a different event sits
     * at this cursor. The phone then re-sends its log (D-1102).
     */
    val syncPushedThrough: Long = 0,
    val syncPushedThroughEventId: String? = null,
    val lastSyncAt: Long? = null,
    val lastSyncError: String? = null,
    // ---- v1.1 (device-local; nothing here changes quest state) ----
    /** Focus timer preset in minutes (10 / 25 / 50), remembered. */
    val focusMinutes: Int = 25,
    /** "Stuck?" after this many days without touching a step (2–7). */
    val stuckDays: Int = 3,
    /** Local day (ISO) of the last time the app was opened: fresh-start offers happen on the first open of a day. */
    val lastOpenDay: String? = null,
    /** The day a fresh start is on offer (ISO) and why (AWAY / NEW_WEEK / NEW_MONTH); cleared once answered. */
    val freshStartOfferDay: String? = null,
    val freshStartReason: String? = null,
    /** Offers dismissed in a row; two pause the offer until [freshStartPausedUntil] (ISO day). */
    val freshStartDismissals: Int = 0,
    val freshStartPausedUntil: String? = null,
    /** The forgiveness line was dismissed on this day (ISO). */
    val forgivenessDismissedOn: String? = null,
)

// A corrupt settings file resets settings rather than crashing every launch. The quest log
// and the sync token live elsewhere, so nothing but preferences is lost.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class SettingsStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.dataStore)

    private object K {
        val theme = stringPreferencesKey("theme")
        val digestEnabled = booleanPreferencesKey("digest_enabled")
        val digestMinutes = intPreferencesKey("digest_minutes")
        val guardEnabled = booleanPreferencesKey("streak_guard_enabled")
        val guardMinutes = intPreferencesKey("streak_guard_minutes")
        val vaultUri = stringPreferencesKey("vault_uri")
        val lastVaultSnapshotAt = longPreferencesKey("last_vault_snapshot_at")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val resumeDismissedAt = longPreferencesKey("resume_dismissed_at")
        val notifAsked = booleanPreferencesKey("notification_permission_asked")
        val serverUrl = stringPreferencesKey("server_url")
        val syncCursor = longPreferencesKey("sync_cursor")
        val syncCursorEventId = stringPreferencesKey("sync_cursor_event_id")
        val syncPushedThrough = longPreferencesKey("sync_pushed_through")
        val syncPushedThroughEventId = stringPreferencesKey("sync_pushed_through_event_id")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastSyncError = stringPreferencesKey("last_sync_error")
        val focusMinutes = intPreferencesKey("focus_minutes")
        val stuckDays = intPreferencesKey("stuck_days")
        val lastOpenDay = stringPreferencesKey("last_open_day")
        val freshStartOfferDay = stringPreferencesKey("fresh_start_offer_day")
        val freshStartReason = stringPreferencesKey("fresh_start_reason")
        val freshStartDismissals = intPreferencesKey("fresh_start_dismissals")
        val freshStartPausedUntil = stringPreferencesKey("fresh_start_paused_until")
        val forgivenessDismissedOn = stringPreferencesKey("forgiveness_dismissed_on")
    }

    val settings: Flow<AppSettings> = store.data.map(::read)

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val old = read(p)
            val n = transform(old)
            p[K.theme] = n.theme.name
            p[K.digestEnabled] = n.digestEnabled
            p[K.digestMinutes] = n.digestMinutes
            p[K.guardEnabled] = n.streakGuardEnabled
            p[K.guardMinutes] = n.streakGuardMinutes
            if (n.vaultUri != null) p[K.vaultUri] = n.vaultUri else p.remove(K.vaultUri)
            if (n.lastVaultSnapshotAt != null) p[K.lastVaultSnapshotAt] = n.lastVaultSnapshotAt else p.remove(K.lastVaultSnapshotAt)
            p[K.onboardingDone] = n.onboardingDone
            p[K.resumeDismissedAt] = n.resumeDismissedAt
            p[K.notifAsked] = n.notificationPermissionAsked
            if (n.serverUrl != null) p[K.serverUrl] = n.serverUrl else p.remove(K.serverUrl)
            p[K.syncCursor] = n.syncCursor
            if (n.syncCursorEventId != null) p[K.syncCursorEventId] = n.syncCursorEventId else p.remove(K.syncCursorEventId)
            p[K.syncPushedThrough] = n.syncPushedThrough
            if (n.syncPushedThroughEventId != null) p[K.syncPushedThroughEventId] = n.syncPushedThroughEventId else p.remove(K.syncPushedThroughEventId)
            if (n.lastSyncAt != null) p[K.lastSyncAt] = n.lastSyncAt else p.remove(K.lastSyncAt)
            if (n.lastSyncError != null) p[K.lastSyncError] = n.lastSyncError else p.remove(K.lastSyncError)
            p[K.focusMinutes] = n.focusMinutes
            p[K.stuckDays] = n.stuckDays
            p.putOrRemove(K.lastOpenDay, n.lastOpenDay)
            p.putOrRemove(K.freshStartOfferDay, n.freshStartOfferDay)
            p.putOrRemove(K.freshStartReason, n.freshStartReason)
            p[K.freshStartDismissals] = n.freshStartDismissals
            p.putOrRemove(K.freshStartPausedUntil, n.freshStartPausedUntil)
            p.putOrRemove(K.forgivenessDismissedOn, n.forgivenessDismissedOn)
        }
    }

    private fun read(p: Preferences) = AppSettings(
        theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        digestEnabled = p[K.digestEnabled] ?: true,
        digestMinutes = p[K.digestMinutes] ?: (8 * 60),
        streakGuardEnabled = p[K.guardEnabled] ?: true,
        streakGuardMinutes = p[K.guardMinutes] ?: (21 * 60),
        vaultUri = p[K.vaultUri],
        lastVaultSnapshotAt = p[K.lastVaultSnapshotAt],
        onboardingDone = p[K.onboardingDone] ?: false,
        resumeDismissedAt = p[K.resumeDismissedAt] ?: 0,
        notificationPermissionAsked = p[K.notifAsked] ?: false,
        serverUrl = p[K.serverUrl],
        syncCursor = p[K.syncCursor] ?: 0,
        syncCursorEventId = p[K.syncCursorEventId],
        syncPushedThrough = p[K.syncPushedThrough] ?: 0,
        syncPushedThroughEventId = p[K.syncPushedThroughEventId],
        lastSyncAt = p[K.lastSyncAt],
        lastSyncError = p[K.lastSyncError],
        focusMinutes = p[K.focusMinutes] ?: 25,
        stuckDays = p[K.stuckDays] ?: 3,
        lastOpenDay = p[K.lastOpenDay],
        freshStartOfferDay = p[K.freshStartOfferDay],
        freshStartReason = p[K.freshStartReason],
        freshStartDismissals = p[K.freshStartDismissals] ?: 0,
        freshStartPausedUntil = p[K.freshStartPausedUntil],
        forgivenessDismissedOn = p[K.forgivenessDismissedOn],
    )

    private fun androidx.datastore.preferences.core.MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value != null) this[key] = value else remove(key)
    }
}
