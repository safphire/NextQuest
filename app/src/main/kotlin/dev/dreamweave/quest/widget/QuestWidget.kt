// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.dreamweave.quest.MainActivity
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.core.TodayCard
import dev.dreamweave.quest.core.Views
import dev.dreamweave.quest.ui.theme.Tokens

/** Home-screen widget: each tracked quest's next step with Done, plus a capture button. */
class QuestWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = (context.applicationContext as QuestApp).container
        val state = c.repository.current()
        val today = Views.today(state, System.currentTimeMillis(), c.repository.zone)
        provideContent { WidgetBody(context, today.cards, today.slotsUsed, today.slotCap) }
    }
}

class QuestWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuestWidget()
}

private fun cp(light: Long, dark: Long) = ColorProvider(day = Color(light), night = Color(dark))

private val WBg = cp(Tokens.Light.background, Tokens.Dark.background)
private val WSurface = cp(Tokens.Light.surface, Tokens.Dark.surface)
private val WText = cp(Tokens.Light.text, Tokens.Dark.text)
private val WTextSecondary = cp(Tokens.Light.textSecondary, Tokens.Dark.textSecondary)
private val WViolet = cp(Tokens.Light.violet, Tokens.Dark.violet)
private val WLime = cp(Tokens.Light.lime, Tokens.Dark.lime)
private val WOnLime = cp(Tokens.Light.onLime, Tokens.Dark.onLime)

@Composable
private fun WidgetBody(context: Context, cards: List<TodayCard>, used: Int, cap: Int) {
    Column(GlanceModifier.fillMaxSize().background(WBg).cornerRadius(16.dp).padding(12.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$used/$cap SLOTS", style = TextStyle(color = WViolet, fontSize = 11.sp, fontWeight = FontWeight.Medium), modifier = GlanceModifier.defaultWeight())
            Box(
                GlanceModifier.size(48.dp).background(WLime).cornerRadius(14.dp)
                    .clickable(actionStartActivity(Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
                    .semantics { contentDescription = "Capture" },
                contentAlignment = Alignment.Center,
            ) { Text("+", style = TextStyle(color = WOnLime, fontSize = 22.sp, fontWeight = FontWeight.Bold)) }
        }
        Spacer(GlanceModifier.height(8.dp))
        if (cards.isEmpty()) {
            Text("Nothing tracked. Tap + to capture.", style = TextStyle(color = WTextSecondary, fontSize = 13.sp))
        } else {
            LazyColumn {
                items(cards, itemId = { it.quest.id.hashCode().toLong() }) { card ->
                    Column(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(
                            GlanceModifier.fillMaxWidth().background(WSurface).cornerRadius(12.dp).padding(10.dp)
                                .clickable(actionStartActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_QUEST_ID, card.quest.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(GlanceModifier.defaultWeight()) {
                                Text(card.quest.title, style = TextStyle(color = WText, fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                                Text(
                                    card.step?.text ?: "Needs next step",
                                    style = TextStyle(color = if (card.step == null) WViolet else WTextSecondary, fontSize = 12.sp),
                                    maxLines = 2,
                                )
                            }
                            Spacer(GlanceModifier.width(8.dp))
                            val step = card.step
                            if (step != null) {
                                Box(
                                    GlanceModifier.size(48.dp).background(WLime).cornerRadius(12.dp)
                                        .clickable(
                                            actionStartActivity(
                                                Intent(context, WidgetDoneActivity::class.java)
                                                    .putExtra(WidgetDoneActivity.EXTRA_STEP_ID, step.id)
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                                            ),
                                        )
                                        .semantics { contentDescription = "Done: ${step.text}" },
                                    contentAlignment = Alignment.Center,
                                ) { Text("✓", style = TextStyle(color = WOnLime, fontSize = 18.sp, fontWeight = FontWeight.Bold)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
