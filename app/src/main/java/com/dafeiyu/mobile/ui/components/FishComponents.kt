package com.dafeiyu.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun FishCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val interaction = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Surface(
        modifier = modifier.fillMaxWidth().applyButtonDeformation(enabled = onClick != null, consumeDrag = false).clip(shape).then(interaction),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    }
}

@Composable
fun SectionLabel(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (action != null) {
            TextButton(onClick = { onAction?.invoke() }, enabled = onAction != null,
                modifier = Modifier.applyButtonDeformation(enabled = onAction != null).clip(RoundedCornerShape(24.dp))) {
                Text(action, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    val haptics = LocalGlassHaptics.current
    val interactionSource = remember { MutableInteractionSource() }
    val horizontalInset = if (LocalSettingsGroupGeometry.current != null) 18.dp else 0.dp
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, interactionSource = interactionSource, indication = null,
                role = Role.Switch, onValueChange = {
                if (enabled && onCheckedChange != null) {
                    haptics.confirm()
                    onCheckedChange(it)
                }
            }, enabled = enabled)
            .heightIn(min = 60.dp)
            .padding(horizontal = horizontalInset, vertical = 10.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = SwitchDefaults.colors(
            checkedThumbColor = if (MaterialTheme.colorScheme.background.red < .3f) Color(0xFF202020) else Color.White,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedTrackColor = Color.Transparent,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
        ))
    }
}

@Composable
fun FishDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** A glass control layer; accent colour is transmitted beneath the optical rim. */
@Composable
fun FishButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = true,
    enabled: Boolean = true,
    consumeDrag: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    val deformation = rememberGlassButtonDeformationState()
    val tint = if (accent && enabled) palette.primary.copy(alpha = .86f) else palette.surface.copy(alpha = .28f)
    val interactionSource = remember { MutableInteractionSource() }
    GlassSurface(buttonDeformation = deformation, modifier = modifier.applyButtonDeformation(enabled, deformation, consumeDrag = consumeDrag)
        .clickable(enabled = enabled, interactionSource = interactionSource, indication = null,
            role = Role.Button, onClick = onClick), cornerRadius = 24.dp, tint = tint,
        materialProfile = if (accent && enabled) GlassMaterialProfile.AccentButton else GlassMaterialProfile.Button) {
        androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides (
            if (!enabled) palette.onSurfaceVariant.copy(alpha = .5f) else if (accent) Color.White else palette.onSurface
        )) {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

@Composable
fun FishNavigationRow(
    title: String, glyph: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val palette = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().applyButtonDeformation(consumeDrag = false).clip(RoundedCornerShape(18.dp))
        .background(if (selected) palette.primary.copy(alpha = .10f) else Color.Transparent)
        .selectable(selected = selected, role = Role.Tab, onClick = onClick)
        .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        FishGlyph(glyph, Modifier.size(22.dp), if (selected) palette.primary else palette.onSurface)
        Text(title, style = MaterialTheme.typography.bodyLarge,
            color = if (selected) palette.primary else palette.onSurface)
    }
}

/** User-selected converted SF SVG assets. No Canvas drawing; source terms recorded separately. */
@Composable
fun FishGlyph(name: String, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val symbol = when (name.lowercase()) {
        "search" -> com.dafeiyu.mobile.R.drawable.sf_search
        "pause" -> com.dafeiyu.mobile.R.drawable.sf_pause
        "chevron" -> com.dafeiyu.mobile.R.drawable.sf_chevron
        "photo" -> com.dafeiyu.mobile.R.drawable.sf_photo
        "document" -> com.dafeiyu.mobile.R.drawable.sf_document
        "close" -> com.dafeiyu.mobile.R.drawable.sf_close
        "trash" -> com.dafeiyu.mobile.R.drawable.sf_trash
        "chevrondown" -> com.dafeiyu.mobile.R.drawable.sf_chevrondown
        "add" -> com.dafeiyu.mobile.R.drawable.sf_add
        "arrow" -> com.dafeiyu.mobile.R.drawable.sf_arrow
        "folder" -> com.dafeiyu.mobile.R.drawable.sf_folder
        "edit" -> com.dafeiyu.mobile.R.drawable.sf_edit
        "download" -> com.dafeiyu.mobile.R.drawable.sf_download
        "scan" -> com.dafeiyu.mobile.R.drawable.sf_viewfinder
        "check" -> com.dafeiyu.mobile.R.drawable.sf_check
        "moon" -> com.dafeiyu.mobile.R.drawable.sf_moon
        "settings" -> com.dafeiyu.mobile.R.drawable.sf_settings
        "back" -> com.dafeiyu.mobile.R.drawable.sf_back
        "grid" -> com.dafeiyu.mobile.R.drawable.sf_grid
        "stop" -> com.dafeiyu.mobile.R.drawable.sf_stop
        "more" -> com.dafeiyu.mobile.R.drawable.sf_more
        "attach" -> com.dafeiyu.mobile.R.drawable.sf_attach
        "sun" -> com.dafeiyu.mobile.R.drawable.sf_sun
        "link" -> com.dafeiyu.mobile.R.drawable.sf_link
        "play" -> com.dafeiyu.mobile.R.drawable.sf_play
        "refresh" -> com.dafeiyu.mobile.R.drawable.fluent_refresh
        "chat" -> com.dafeiyu.mobile.R.drawable.sf_chat
        "groupchat" -> com.dafeiyu.mobile.R.drawable.sf_groupchat
        "info", "info-circle" -> com.dafeiyu.mobile.R.drawable.sf_info_circle
        "archive" -> com.dafeiyu.mobile.R.drawable.sf_archive
        "app-icon" -> com.dafeiyu.mobile.R.drawable.ic_whale_512
        "menu" -> com.dafeiyu.mobile.R.drawable.sf_menu
        "bell" -> com.dafeiyu.mobile.R.drawable.sf_bell
        "model" -> com.dafeiyu.mobile.R.drawable.sf_model
        "usage" -> com.dafeiyu.mobile.R.drawable.sf_usage
        "timer", "stopwatch" -> com.dafeiyu.mobile.R.drawable.sf_stopwatch
        "terminal" -> com.dafeiyu.mobile.R.drawable.sf_terminal
        "camera" -> com.dafeiyu.mobile.R.drawable.sf_camera
        "send" -> com.dafeiyu.mobile.R.drawable.sf_send
        "plus" -> com.dafeiyu.mobile.R.drawable.sf_add
        "sidebar" -> com.dafeiyu.mobile.R.drawable.sf_menu
        "flashlightoff" -> com.dafeiyu.mobile.R.drawable.sf_flashlightoff
        "folderedit" -> com.dafeiyu.mobile.R.drawable.sf_folderedit
        "flashlight" -> com.dafeiyu.mobile.R.drawable.sf_flashlight
        "unlock" -> com.dafeiyu.mobile.R.drawable.sf_unlock
        "viewfinder" -> com.dafeiyu.mobile.R.drawable.sf_viewfinder
        "pair" -> com.dafeiyu.mobile.R.drawable.sf_pair
        "lock" -> com.dafeiyu.mobile.R.drawable.sf_lock
        "whale" -> com.dafeiyu.mobile.R.drawable.ic_whale
        "deepseek" -> com.dafeiyu.mobile.R.drawable.ic_deepseek
        else -> com.dafeiyu.mobile.R.drawable.sf_document
    }
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(symbol), contentDescription = null,
        colorFilter = if (name.equals("app-icon", ignoreCase = true) || name.equals("deepseek", ignoreCase = true)) null
            else androidx.compose.ui.graphics.ColorFilter.tint(tint),
        modifier = modifier.size(24.dp),
    )
}
