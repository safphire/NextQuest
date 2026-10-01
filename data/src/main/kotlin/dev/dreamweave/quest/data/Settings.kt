// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
    val lastSyncAt: Long? = null,
    val lastSyncError: String? = null,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

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
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastSyncError = stringPreferencesKey("last_sync_error")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
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
            lastSyncAt = p[K.lastSyncAt],
            lastSyncError = p[K.lastSyncError],
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val old = AppSettings(
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
                lastSyncAt = p[K.lastSyncAt],
                lastSyncError = p[K.lastSyncError],
            )
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
            if (n.lastSyncAt != null) p[K.lastSyncAt] = n.lastSyncAt else p.remove(K.lastSyncAt)
            if (n.lastSyncError != null) p[K.lastSyncError] = n.lastSyncError else p.remove(K.lastSyncError)
        }
    }
}
