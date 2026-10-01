// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dreamweave.quest.core.CalendarItem
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.core.Views
import dev.dreamweave.quest.ui.QuestViewModel
import dev.dreamweave.quest.ui.RootActions
import dev.dreamweave.quest.ui.components.EmptyState
import dev.dreamweave.quest.ui.components.MetaLabel
import dev.dreamweave.quest.ui.components.QuestCard
import dev.dreamweave.quest.ui.theme.MonoNumber
import dev.dreamweave.quest.ui.theme.Quest
import dev.dreamweave.quest.util.Format
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Instant
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

private fun LocalDate.startOfWeek(): LocalDate = this.minus(DatePeriod(days = this.dayOfWeek.ordinal)) // Monday first

@Composable
fun CalendarScreen(vm: QuestViewModel, state: QuestState, actions: RootActions) {
    val c = Quest.colors
    val now by vm.now.collectAsStateWithLifecycle()
    val zone = vm.zone
    val today = remember(now) { Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date }
    var month by rememberSaveable { mutableStateOf(false) }
    var selectedIso by rememberSaveable { mutableStateOf(today.toString()) }
    var anchorIso by rememberSaveable { mutableStateOf(today.toString()) }
    val selected = LocalDate.parse(selectedIso)
    val anchor = LocalDate.parse(anchorIso)

    val (from, to) = if (month) {
        val first = LocalDate(anchor.year, anchor.month, 1)
        val gridStart = first.startOfWeek()
        val next = first.plus(DatePeriod(months = 1))
        gridStart to next.minus(DatePeriod(days = 1)).let { last -> last.plus(DatePeriod(days = 6 - last.dayOfWeek.ordinal)) }
    } else {
        val start = anchor.startOfWeek()
        start to start.plus(DatePeriod(days = 6))
    }
    val items = remember(state, from, to) { Views.calendar(state, from, to, zone) }
    val byDay = remember(items) { items.groupBy { it.date } }
    val dayItems = byDay[selected].orEmpty()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Calendar", style = MaterialTheme.typography.headlineMedium, color = c.text, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !month, onClick = { month = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Week") }
                SegmentedButton(
                    selected = month, onClick = { month = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Month") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    anchorIso = (if (month) anchor.minus(DatePeriod(months = 1)) else anchor.minus(DatePeriod(days = 7))).toString()
                }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = if (month) "Previous month" else "Previous week", tint = c.text) }
                Text(
                    if (month) "${Format.month(anchor)} ${anchor.year}" else "${Format.shortDate(from)} – ${Format.shortDate(to)}",
                    style = MaterialTheme.typography.titleSmall, color = c.text, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    anchorIso = (if (month) anchor.plus(DatePeriod(months = 1)) else anchor.plus(DatePeriod(days = 7))).toString()
                }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = if (month) "Next month" else "Next week", tint = c.text) }
            }
            Row(Modifier.fillMaxWidth()) {
                DayOfWeek.values().forEach { d ->
                    Text(
                        d.name.take(1), style = MaterialTheme.typography.labelSmall, color = c.textSecondary,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            var day = from
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                while (day <= to) {
                    val weekStart = day
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (i in 0 until 7) {
                            val d = weekStart.plus(DatePeriod(days = i))
                            DayCell(
                                date = d,
                                count = byDay[d]?.size ?: 0,
                                isToday = d == today,
                                isSelected = d == selected,
                                dim = month && d.month != anchor.month,
                                onClick = { selectedIso = d.toString() },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    day = weekStart.plus(DatePeriod(days = 7))
                }
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            MetaLabel(if (selected == today) "Today" else Format.shortDate(selected))
        }
        if (dayItems.isEmpty()) {
            item {
                EmptyState(Icons.Rounded.EventAvailable, "Nothing scheduled", "Give a step a date from its quest to see it here.")
            }
        }
        items(dayItems, key = { it.step.id }) { item -> CalendarRow(item, zone = vm.zone, onOpen = { actions.openQuest(item.root.id) }) }
    }
}

@Composable
private fun DayCell(date: LocalDate, count: Int, isToday: Boolean, isSelected: Boolean, dim: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Quest.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (isSelected) c.violetTint else c.surface)
            .border(1.dp, if (isToday) c.violet else c.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                selected = isSelected
                contentDescription = "${Format.shortDate(date)}, " + if (count == 0) "nothing scheduled" else "$count scheduled"
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("${date.dayOfMonth}", style = MonoNumber, color = if (dim) c.textSecondary else c.text)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(minOf(count, 3)) { Box(Modifier.size(5.dp).clip(CircleShape).background(c.violet)) }
        }
    }
}

@Composable
private fun CalendarRow(item: CalendarItem, zone: kotlinx.datetime.TimeZone, onOpen: () -> Unit) {
    val c = Quest.colors
    val time = item.step.dueAt?.let { Format.time(Instant.fromEpochMilliseconds(it).toLocalDateTime(zone)) }.orEmpty()
    QuestCard(onClick = onOpen, onClickLabel = "Open quest") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(time, style = MonoNumber, color = c.violet, modifier = Modifier.width(52.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.step.text, style = MaterialTheme.typography.bodyLarge,
                    color = if (item.step.isDone) c.textSecondary else c.text,
                )
                Text(
                    if (item.quest.id == item.root.id) item.root.title else "${item.root.title} · ${item.quest.title}",
                    style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                )
            }
            Icon(
                if (item.step.isDone) Icons.Rounded.CheckCircle else Icons.Rounded.Schedule,
                contentDescription = if (item.step.isDone) "Done" else "Scheduled",
                tint = if (item.step.isDone) c.violet else c.textSecondary,
            )
        }
    }
}
