// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.dreamweave.quest.ui.UiEvent
import dev.dreamweave.quest.ui.components.LimeButton
import dev.dreamweave.quest.ui.theme.MonoNumber
import dev.dreamweave.quest.ui.theme.Quest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Top-of-screen toast for XP, achievements and calm messages. Announced to screen readers. */
@Composable
fun ToastHost(event: UiEvent?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = Quest.colors
    AnimatedVisibility(
        visible = event != null && event !is UiEvent.QuestCompleted,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier.statusBarsPadding().padding(top = 8.dp),
    ) {
        val e = event ?: return@AnimatedVisibility
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (e is UiEvent.Message) c.text else c.violet)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (e) {
                is UiEvent.XpGained -> {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, tint = c.onViolet, modifier = Modifier.size(18.dp))
                    Text("+${e.amount} XP", style = MonoNumber, color = c.onViolet)
                }
                is UiEvent.AchievementUnlocked -> {
                    Icon(Icons.Rounded.EmojiEvents, contentDescription = null, tint = c.onViolet, modifier = Modifier.size(18.dp))
                    Text("Unlocked: ${e.achievement.title}", style = MaterialTheme.typography.labelLarge, color = c.onViolet)
                }
                is UiEvent.Message -> {
                    Text(e.text, style = MaterialTheme.typography.bodyMedium, color = c.background)
                    if (e.actionLabel != null && e.action != null) {
                        TextButton(onClick = { e.action.invoke(); onDismiss() }) {
                            Text(e.actionLabel, color = c.background, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                is UiEvent.QuestCompleted, is UiEvent.UndoOffer -> Unit
            }
        }
    }
}

/** The celebratory moment for finishing a quest. */
@Composable
fun QuestCompleteCelebration(event: UiEvent.QuestCompleted, onDismiss: () -> Unit, onUndo: (() -> Unit)? = null) {
    val c = Quest.colors
    val t = rememberInfiniteTransition(label = "confetti")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.clip(RoundedCornerShape(24.dp)).background(c.surface)) {
            Canvas(Modifier.matchParentSize()) {
                val colors = listOf(c.lime, c.violet, c.violetTint)
                for (i in 0 until 36) {
                    val angle = (i / 36f) * 2 * PI.toFloat()
                    val r = (0.2f + 0.8f * ((phase + i * 0.037f) % 1f)) * size.minDimension / 2
                    drawCircle(
                        colors[i % colors.size],
                        radius = 5f + (i % 3) * 2f,
                        center = Offset(size.width / 2 + cos(angle) * r, size.height / 3 + sin(angle) * r),
                    )
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(40.dp))
                Icon(Icons.Rounded.EmojiEvents, contentDescription = null, tint = c.violet, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("Quest complete", style = MaterialTheme.typography.headlineMedium, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text(event.title, style = MaterialTheme.typography.bodyLarge, color = c.textSecondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text("+${event.xp} XP", style = MonoNumber, color = c.violet)
                Spacer(Modifier.height(20.dp))
                LimeButton("Onward", onClick = onDismiss, modifier = Modifier.width(200.dp))
                // The Undo bar would sit under this dialog, so the celebration carries it (B2).
                if (onUndo != null) {
                    TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(androidx.compose.ui.res.stringResource(dev.dreamweave.quest.R.string.undo), color = c.textSecondary)
                    }
                }
            }
        }
    }
}


/** After a completion: "Done · step" with Undo, for 10 seconds (B2). Announced to screen readers. */
@Composable
fun UndoBar(label: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val c = Quest.colors
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.text)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            androidx.compose.ui.res.stringResource(dev.dreamweave.quest.R.string.undo_offer, label),
            style = MaterialTheme.typography.bodyMedium, color = c.background, maxLines = 2,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(androidx.compose.ui.res.stringResource(dev.dreamweave.quest.R.string.undo), color = c.background, style = MaterialTheme.typography.labelLarge)
        }
    }
}
