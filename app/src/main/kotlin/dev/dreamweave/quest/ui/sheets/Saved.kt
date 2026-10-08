// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import dev.dreamweave.quest.core.CoreJson
import kotlinx.serialization.KSerializer

/** Saves a nullable value as JSON in the saved-instance state; an unreadable value restores as null. */
fun <T : Any> jsonSaver(serializer: KSerializer<T>): Saver<T?, String> = Saver(
    save = { value -> value?.let { CoreJson.encodeToString(serializer, it) } },
    restore = { saved -> runCatching { CoreJson.decodeFromString(serializer, saved) }.getOrNull() },
)

/**
 * Which dialog or sheet a screen has open. Kept in the saved-instance state, so an open
 * dialog (and, through its own rememberSaveable, its typed text) survives rotation and
 * process death.
 */
@Composable
fun <T : Any> rememberSavedDialog(serializer: KSerializer<T>): MutableState<T?> =
    rememberSaveable(stateSaver = jsonSaver(serializer)) { mutableStateOf(null) }
