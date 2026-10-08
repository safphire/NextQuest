// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dreamweave.quest.core.Ids
import dev.dreamweave.quest.data.ThemeMode
import dev.dreamweave.quest.sync.SyncWorker
import dev.dreamweave.quest.ui.AppRoot
import dev.dreamweave.quest.ui.ExternalRequest
import dev.dreamweave.quest.ui.QuestViewModel
import dev.dreamweave.quest.ui.settings.SyncStatus
import dev.dreamweave.quest.ui.theme.QuestTheme

class MainActivity : ComponentActivity() {
    private var external by mutableStateOf<ExternalRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) external = parse(intent)
        setContent {
            val vm: QuestViewModel = viewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()
            QuestTheme(settings?.theme ?: ThemeMode.SYSTEM) {
                AppRoot(vm, external, onExternalHandled = { external = null })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Opening the app is when fresh AI proposals matter most.
        if ((application as QuestApp).container.sync.isPaired) SyncWorker.now(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        external = parse(intent)
    }

    companion object {
        const val ACTION_CAPTURE = "dev.dreamweave.quest.action.CAPTURE"
        const val ACTION_OPEN_PROPOSALS = "dev.dreamweave.quest.action.OPEN_PROPOSALS"
        const val EXTRA_QUEST_ID = "dev.dreamweave.quest.extra.QUEST_ID"
        const val PAIR_SCHEME = "dwquest"

        fun parse(intent: Intent?): ExternalRequest? {
            intent ?: return null
            return when {
                intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true -> {
                    // CharSequence: styled text from some apps isn't a String extra.
                    val text = listOfNotNull(
                        intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
                        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
                    ).joinToString(" — ").trim()
                    ExternalRequest.Capture(text.ifEmpty { null })
                }
                intent.action == ACTION_CAPTURE -> ExternalRequest.Capture(null)
                intent.action == ACTION_OPEN_PROPOSALS -> ExternalRequest.OpenProposals
                // The launcher activity is exported: only well-formed ids reach navigation.
                intent.getStringExtra(EXTRA_QUEST_ID)?.let(Ids::isValid) == true -> ExternalRequest.OpenQuest(intent.getStringExtra(EXTRA_QUEST_ID)!!)
                intent.data?.scheme == PAIR_SCHEME && intent.data?.host == "pair" -> {
                    val server = intent.data?.getQueryParameter("server")
                    val code = intent.data?.getQueryParameter("code")
                    // Only pre-fills the pairing form; the user confirms the server (SyncSection).
                    if (server != null && code != null && SyncStatus.addressAllowed(server)) ExternalRequest.Pair(server.take(300), code.take(20)) else null
                }
                else -> null
            }
        }
    }
}
