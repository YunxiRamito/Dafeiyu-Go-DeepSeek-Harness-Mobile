package com.dafeiyu.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.ui.components.FishButton
import com.dafeiyu.mobile.ui.components.FishCard
import com.dafeiyu.mobile.ui.components.FishDivider
import com.dafeiyu.mobile.ui.components.SectionLabel
import com.dafeiyu.mobile.ui.theme.APP_FONT_FAMILIES
import com.dafeiyu.mobile.ui.theme.DEFAULT_APP_FONT_FAMILY
import kotlin.math.roundToInt

/** Dedicated typography controls. Preview changes are local to this page; no fonts are installed. */
@Composable
fun FontSettingsScreen(repository: DemoRepository, onBack: () -> Unit) {
    val fontScale = repository.fontSizeScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
    val selectedFontFamily = FONT_FAMILIES.firstOrNull { it.key == repository.fontFamily }
    val selectedWeight = FONT_WEIGHTS.firstOrNull { it.key == repository.fontWeight }
    val previewFamily = selectedFontFamily?.fontFamily ?: FontFamily.Default
    val previewWeight = selectedWeight?.fontWeight ?: FontWeight.Normal
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = previewFamily,
        fontWeight = previewWeight,
        fontSize = MaterialTheme.typography.bodyLarge.fontSize * fontScale,
        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * fontScale,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FishButton(
            onClick = onBack,
            accent = false,
            modifier = Modifier.semantics {
                contentDescription = "返回个性化设置"
            },
        ) {
            Text("← 返回")
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "调整字体族、字号和字重，并在下方即时预览。不会安装新字体，也不会更改 Android 系统字体。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FishCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionLabel("字体大小")
                Text(
                    "${(fontScale * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Slider(
                    value = fontScale,
                    onValueChange = repository::updateFontSizeScale,
                    valueRange = MIN_FONT_SCALE..MAX_FONT_SCALE,
                    steps = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "字体大小"
                            stateDescription = "${(fontScale * 100).roundToInt()}%"
                        },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("较小 · 85%", style = MaterialTheme.typography.labelMedium)
                    Text("较大 · 120%", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        FishCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionLabel("字重")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FONT_WEIGHTS.forEach { option ->
                        WeightOption(
                            title = option.title,
                            selected = repository.fontWeight == option.key,
                            onSelect = { repository.updateFontWeight(option.key) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (selectedWeight == null) {
                    Text(
                        "当前保存的字重选项无法识别；请选择上方一项以更新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        FishCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionLabel("即时预览")
                Text(
                    "让想法，轻轻展开。",
                    style = bodyStyle.copy(
                        fontSize = 24.sp * fontScale,
                        lineHeight = 32.sp * fontScale,
                    ),
                )
                Text(
                    "这是字体预览文字。更改字体族、大小或字重时，这里的文字会立即更新。此预览不代表 Android 系统字体或其他应用发生变化。",
                    style = bodyStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FishDivider()
                Text(
                    "${selectedFontFamily?.title ?: "未识别字体族"} · ${(fontScale * 100).roundToInt()}% · ${selectedWeight?.title ?: "未识别字重"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        TextButton(
            onClick = {
                repository.updateFontFamily(DEFAULT_FONT_FAMILY)
                repository.updateFontSizeScale(DEFAULT_FONT_SCALE)
                repository.updateFontWeight(DEFAULT_FONT_WEIGHT)
            },
            modifier = Modifier
                .align(Alignment.End)
                .heightIn(min = 48.dp),
        ) {
            Text("恢复字体默认值")
        }
    }
}

@Composable
private fun FontFamilyOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun WeightOption(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val foreground = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = modifier
            .heightIn(min = 52.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .semantics {
                stateDescription = if (selected) "已选择" else "未选择"
            },
        shape = MaterialTheme.shapes.small,
        color = background,
        contentColor = foreground,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private data class FontWeightOption(
    val key: String,
    val title: String,
    val fontWeight: FontWeight,
)

// Shared with FishTheme so the preview and the applied typography use the same mapping.
private val FONT_FAMILIES = APP_FONT_FAMILIES

private val FONT_WEIGHTS = listOf(
    FontWeightOption("轻", "轻", FontWeight.Light),
    FontWeightOption("标准", "标准", FontWeight.Normal),
    FontWeightOption("粗", "粗", FontWeight.Bold),
)

private const val MIN_FONT_SCALE = 0.85f
private const val MAX_FONT_SCALE = 1.2f
private const val DEFAULT_FONT_FAMILY = DEFAULT_APP_FONT_FAMILY
private const val DEFAULT_FONT_SCALE = 1f
private const val DEFAULT_FONT_WEIGHT = "标准"


