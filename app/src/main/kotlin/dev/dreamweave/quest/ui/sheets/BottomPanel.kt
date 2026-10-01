// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.sheets

import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.dreamweave.quest.ui.theme.Quest

/**
 * A bottom-anchored panel in its own dialog window. Used instead of Material's
 * ModalBottomSheet for the capture and "What's next?" sheets: that sheet closes through an
 * animated state and could stay on screen after a quick save (seen on an API 34 emulator).
 * Here the panel is open exactly while it is in composition, so closing is synchronous.
 * The window resizes for the keyboard (Compose dialogs use adjustResize).
 */
@Composable
fun BottomPanel(
    onDismissRequest: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setGravity(Gravity.BOTTOM) }
        val c = Quest.colors
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = c.surface,
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                // Drag-handle look, for familiarity; the panel itself doesn't drag.
                Box(
                    Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .background(c.textSecondary.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.height(4.dp))
                content()
            }
        }
    }
}
