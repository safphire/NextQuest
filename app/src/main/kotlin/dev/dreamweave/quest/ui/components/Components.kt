// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dreamweave.quest.ui.theme.MonoNumber
import dev.dreamweave.quest.ui.theme.Quest

/** The primary action (Done, Accept, Track). The only place lime is used. */
@Composable
fun LimeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    large: Boolean = false,
) {
    val c = Quest.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = if (large) 56.dp else 48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = c.lime, contentColor = c.onLime,
            disabledContainerColor = c.surfaceAlt, disabledContentColor = c.textSecondary,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A secondary action: bordered, never lime. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val c = Quest.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, c.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A flat card: 1dp border, 16dp corners, no shadow. [dashed] marks "needs a next step". */
@Composable
fun QuestCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    dashed: Boolean = false,
    onClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val c = Quest.colors
    val shape = RoundedCornerShape(16.dp)
    val base = modifier.fillMaxWidth().clip(shape)
    val bordered = if (dashed) {
        base.background(c.surface).drawBehind {
            drawRoundRect(
                color = c.violet,
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)),
                cornerRadius = CornerRadius(16.dp.toPx()),
            )
        }
    } else {
        base.background(c.surface).border(1.dp, c.border, shape)
    }
    val clickable = if (onClick != null) bordered.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else bordered
    Box(clickable.padding(16.dp)) { content() }
}

/** Violet progress bar on a violet-tint track. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val c = Quest.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height))
            .background(c.violetTint),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(height))
                .background(c.violet),
        )
    }
}

/** Mono, uppercase, violet meta label ("NEXT", "FOCUSED", "4/5 SLOTS"). */
@Composable
fun MetaLabel(text: String, modifier: Modifier = Modifier, secondary: Boolean = false) {
    val c = Quest.colors
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = if (secondary) c.textSecondary else c.violet,
    )
}

/** "●●●●○ 4/5 SLOTS". */
@Composable
fun SlotPips(used: Int, cap: Int, modifier: Modifier = Modifier) {
    val c = Quest.colors
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "$used of $cap slots used" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(cap) { i ->
            Box(
                Modifier
                    .size(width = 18.dp, height = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (i < used) c.violet else c.violetTint),
            )
        }
        Spacer(Modifier.width(6.dp))
        MetaLabel("$used/$cap slots")
    }
}

/** Round violet badge with the level number; opens Settings. */
@Composable
fun LevelBadge(level: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Quest.colors
    Box(
        modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = "Open settings", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Level $level. Opens settings." },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.violet), contentAlignment = Alignment.Center) {
            Text("$level", style = MonoNumber.copy(fontWeight = FontWeight.SemiBold), color = c.onViolet)
        }
    }
}

@Composable
fun StreakChip(current: Int, modifier: Modifier = Modifier) {
    val c = Quest.colors
    Pill(modifier.semantics(mergeDescendants = true) { contentDescription = "$current day streak" }) {
        Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null, tint = c.violet, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("$current", style = MonoNumber, color = c.text)
    }
}

/** Small rounded neutral container used for chips (due date, source, counts). */
@Composable
fun Pill(modifier: Modifier = Modifier, tinted: Boolean = false, content: @Composable () -> Unit) {
    val c = Quest.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (tinted) c.violetTint else c.surfaceAlt,
        contentColor = c.text,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
fun ChipText(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, tinted: Boolean = false) {
    val c = Quest.colors
    Pill(modifier, tinted) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (tinted) c.violet else c.textSecondary)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = Quest.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(c.violetTint), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.violet, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Quest.colors.text, modifier = modifier.padding(top = 8.dp, bottom = 8.dp))
}
