/*
MIT License

Copyright (c) 2020 Microsoft Corporation

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

package com.dafeiyu.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.LocalGlassHaptics
import com.dafeiyu.mobile.ui.components.LocalSettingsGroupGeometry
import com.dafeiyu.mobile.ui.components.LocalSettingsPageTransition

// Original Microsoft Fluent paths at a347af95478f989038cccbf085978865264c048a.
// Source assets: Puzzle Piece/SVG/ic_fluent_puzzle_piece_24_regular.svg,
// Lightbulb/SVG/ic_fluent_lightbulb_24_regular.svg. No path geometry was redrawn.
private val pluginPageIcon by lazy {
    ImageVector.Builder("Plugin", 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString("M13 2.00423C14.5977 2.00423 15.9037 3.25315 15.994 4.82327L15.999 4.999L18.25 5C19.1172 5 19.837 5.63072 19.9758 6.45858L19.9942 6.60663L20 6.75018L19.9991 10.5012L18.0015 10.5013C17.2579 10.5013 16.6353 11.0445 16.5215 11.7403L16.5062 11.8725L16.5015 12.0013C16.5015 12.7449 17.0446 13.3675 17.7405 13.4813L17.8727 13.4965L18.0014 13.5013L19.9991 13.5012L20 17.2526C20 18.1708 19.2929 18.9238 18.3934 18.9968L18.2499 19.0026L15.999 19.002L15.9949 19.1747C15.9106 20.6305 14.7881 21.8078 13.3567 21.9774L13.1763 21.9933L13 21.9984C11.4023 21.9984 10.0963 20.7495 10.0051 19.1779L10 19.002L7.75 19.0026C6.88283 19.0026 6.16298 18.3719 6.02417 17.5443L6.0058 17.3963L6 17.2528L5.999 15.001L5.836 14.9962C4.38017 14.9119 3.20285 13.7894 3.03326 12.3581L3.01736 12.1776L3.01227 12.0013C3.01227 10.4036 4.26119 9.09765 5.83593 9.0064L5.999 9.001L6 6.75C6 5.88283 6.63072 5.16298 7.45831 5.02418L7.60631 5.0058L7.74981 5L9.999 4.999L10.0051 4.82796C10.0894 3.37214 11.2119 2.19482 12.6432 2.02523L12.8237 2.00933L13 2.00423ZM13 3.50423C12.2203 3.50423 11.5795 4.09912 11.5069 4.86039L11.5 5.00497L11.4985 6.49904L7.75 6.5C7.63165 6.5 7.53251 6.58223 7.5066 6.69279L7.5 6.75018L7.49909 10.5012L6.01227 10.5013C5.18384 10.5013 4.51227 11.1729 4.51227 12.0013C4.51227 12.781 5.10716 13.4218 5.86775 13.4944L6.01219 13.5013L7.49909 13.5012L7.5 17.2526C7.5 17.371 7.58223 17.4701 7.69261 17.496L7.7499 17.5026L11.4985 17.5021L11.5 18.9984C11.5 19.8268 12.1716 20.4984 13 20.4984C13.7797 20.4984 14.4204 19.9035 14.4931 19.1434L14.5 18.9991L14.4985 17.5021L18.25 17.5026C18.3683 17.5026 18.4675 17.4204 18.4934 17.3101L18.5 17.2528L18.499 15L17.9762 15.0009L17.7968 14.9944C16.2855 14.8922 15.0927 13.6769 15.0068 12.1532L15.0019 11.976L15.0084 11.7967C15.1105 10.2854 16.3258 9.09259 17.827 9.00631L18.0014 9.00131L18.499 9.001L18.5 6.75C18.5 6.65532 18.4474 6.57294 18.3697 6.5305L18.3072 6.5066L18.2498 6.5L14.4985 6.49904L14.5 5.00423C14.5 4.17581 13.8284 3.50423 13 3.50423Z").toNodes(), fill = SolidColor(Color.Black))
        .build()
}
private val skillPageIcon by lazy {
    ImageVector.Builder("Skill", 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString("M12 2.00098C16.0041 2.00098 19.25 5.24691 19.25 9.25098C19.25 11.347 18.3493 13.2707 16.5869 14.9929C16.51 15.0681 16.4504 15.1586 16.4118 15.2582L16.3804 15.3605L15.2493 20.2561C15.0266 21.22 14.2035 21.9183 13.2302 21.993L13.057 21.9996H10.9433C9.95374 21.9996 9.08791 21.3545 8.79629 20.4228L8.75088 20.2555L7.62132 15.3607C7.58904 15.2208 7.51728 15.0931 7.41456 14.9928C5.73515 13.3526 4.83778 11.5297 4.75613 9.54923L4.75 9.25098L4.75388 9.01166C4.88014 5.11837 8.07601 2.00098 12 2.00098ZM14.115 18.499H9.884L10.2125 19.9182C10.2831 20.2245 10.5357 20.4504 10.8401 20.4925L10.9433 20.4996H13.057C13.3713 20.4996 13.6481 20.3044 13.7577 20.0174L13.7878 19.9184L14.115 18.499ZM12 3.50098C8.89821 3.50098 6.37006 5.95699 6.25415 9.03042L6.25 9.25098L6.25672 9.52799C6.33286 11.0913 7.05722 12.5471 8.46262 13.9197C8.72675 14.1777 8.92265 14.496 9.03422 14.846L9.08291 15.0235L9.538 16.999H14.461L14.9189 15.0228C15.0019 14.6634 15.1718 14.3309 15.4124 14.0539L15.5386 13.9201C16.9432 12.5475 17.6672 11.0916 17.7433 9.52803L17.75 9.25098L17.7458 9.03042C17.6299 5.95699 15.1018 3.50098 12 3.50098Z").toNodes(), fill = SolidColor(Color.Black))
        .build()
}

@Composable
internal fun ExtensionPageIcon(category: String, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(if (category == "skill") skillPageIcon else pluginPageIcon, contentDescription = null,
        modifier = modifier.size(24.dp), tint = tint)
}

@Composable
internal fun ExtensionSettingsRow(category: String, title: String, subtitle: String, route: String, onDetail: (String) -> Unit) {
    val navigation = LocalSettingsPageTransition.current
    val group = LocalSettingsGroupGeometry.current
    val haptics = LocalGlassHaptics.current
    val interaction = remember { MutableInteractionSource() }
    var rowBounds by remember { mutableStateOf<Rect?>(null) }
    Row(Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
        val position = coordinates.positionInWindow()
        rowBounds = Rect(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height)
    }.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = {
        rowBounds?.let { navigation?.recordSource(route, it, group?.boundsInWindow, it.height / 2f) }
        haptics.confirm()
        onDetail(route)
    }).heightIn(min = 72.dp).padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            ExtensionPageIcon(category, Modifier.size(23.dp), MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FishGlyph("chevron", Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

