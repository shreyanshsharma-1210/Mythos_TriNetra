package com.trustmesh.app.vcd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustmesh.app.vcd.ui.theme.CallColors

/**
 * The shared visual language.
 *
 * Every screen draws from here so the app reads as one app rather than a set of tools that happen
 * to ship together. That is not only cosmetic: this app asks people to act on what it says about a
 * caller, and a screen that looks unlike the rest of the product is a screen they have less reason
 * to believe.
 *
 * Accessibility is built into these pieces rather than bolted on per screen. Touch targets meet the
 * 48 dp minimum, headings are marked as headings so a screen reader can jump between them, every
 * interactive icon carries a description, and status is never carried by colour alone — colour
 * always travels with an icon and a word, because roughly one man in twelve cannot separate the red
 * from the green that this app uses to mean "dangerous" and "fine".
 */

/** Minimum comfortable touch target. Below this, a control is hard to hit and fails WCAG 2.5.5. */
val MinTouchTarget = 48.dp

/** The blue bar every top-level screen wears. */
@Composable
fun VcdHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(CallColors.brand)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                    modifier = Modifier.semantics { heading() },
                )
                subtitle?.let {
                    Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                }
            }
            trailing()
        }
        content()
    }
}

/** A section heading, marked as a heading so screen readers can navigate by it. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
    )
}

/**
 * A list row with an avatar, a title, a subtitle and one trailing action.
 *
 * The whole row is the touch target rather than just the icon, so it is reachable without precise
 * aim, and the row carries a single merged description for screen readers instead of announcing
 * three fragments separately.
 */
@Composable
fun VcdListRow(
    avatarSeed: String,
    title: String,
    modifier: Modifier = Modifier,
    titleColour: Color = MaterialTheme.colorScheme.onBackground,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailingIcon: ImageVector? = null,
    trailingTint: Color = MaterialTheme.colorScheme.primary,
    trailingDescription: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    subtitle: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget + 16.dp)
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InitialAvatar(avatarSeed, size = 44)
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = titleColour, fontSize = 16.sp)
            subtitle()
        }
        trailingIcon?.let {
            Box(
                Modifier
                    .size(MinTouchTarget)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        if (enabled) trailingTint.copy(alpha = 0.12f) else Color.Transparent
                    )
                    .then(
                        // A destructive action gets its own target rather than sharing the row's,
                        // so tapping a name can never delete it by accident.
                        if (onTrailingClick != null) Modifier.clickable(onClick = onTrailingClick)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    it,
                    contentDescription = trailingDescription,
                    tint = if (enabled) trailingTint
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * A coloured disc with an initial.
 *
 * Marked as decorative — the name is already in the row beside it, and having a screen reader
 * announce "letter A" before every name would be noise, not information.
 */
@Composable
fun InitialAvatar(name: String?, size: Int) {
    // Plenty of real address books hold entries with no name, where the "name" is the number
    // itself. A "+" in a circle looks like a bug, so those fall back to a handset glyph.
    val initial = name?.trim()?.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString()
        ?: if (name?.any { it.isDigit() } == true) "☎" else "?"
    val palette = listOf(0xFF3F6FE0, 0xFF2E9E7B, 0xFF9A5BD1, 0xFFD1745B, 0xFF4FA3C7)
    val colour = Color(palette[((name ?: "").hashCode().let { if (it < 0) -it else it }) % palette.size])

    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(colour),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size / 2.4f).sp,
        )
    }
}

/** An explanatory block. Never the only place a fact appears if the fact matters. */
@Composable
fun InfoCard(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = accent?.copy(alpha = 0.12f)
                ?: MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                color = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            body()
        }
    }
}

/** An icon button that always meets the minimum touch target and always has a description. */
@Composable
fun VcdIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget),
    ) {
        Icon(icon, contentDescription = description, tint = tint)
    }
}
