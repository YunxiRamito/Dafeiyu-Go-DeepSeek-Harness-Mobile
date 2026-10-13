package com.dafeiyu.mobile.glasslab

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** The range here is part of the portable renderer contract, rather than a UI-only slider limit. */
data class LabParameterSpec(
    val key: String,
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String,
    val group: String,
)

/**
 * A single theme/type's material. Colors use Android's #AARRGGBB order; #RRGGBB is also accepted.
 * Missing known parameters read as their registered default. Imported documents are expanded to
 * complete maps, so exports never depend on hidden slider state or on another button type.
 */
data class LabParameters(
    val values: Map<String, Float>,
    val colors: Map<String, String>,
    val iconBlend: String = "srcOver",
    val icon: String = "add",
    val glassEnabled: Boolean = true,
    val motionEnabled: Boolean = true,
    val springEnabled: Boolean = true,
    val disabled: Boolean = false,
    val shape: String = "rounded",
    val pressEffect: String = "glow",
) {
    init {
        values.forEach { (key, value) ->
            val spec = specByKey[key] ?: invalid("values.$key", "未知参数")
            validateValue(spec, value, "values.$key")
        }
        colors.forEach { (key, value) ->
            if (key !in colorKeys) invalid("colors.$key", "未知颜色项")
            normalizeColor(value, "colors.$key")
        }
        requireChoice(iconBlend, blendModes, "iconBlend")
        requireChoice(icon, icons, "icon")
        requireChoice(shape, shapes, "shape")
        requireChoice(pressEffect, pressEffects, "pressEffect")
    }

    operator fun get(key: String): Float =
        values[key] ?: specByKey[key]?.default ?: invalid("values.$key", "未知参数")

    fun withValue(key: String, value: Float): LabParameters = copy(values = values + (key to value))

    fun withColor(key: String, color: String): LabParameters =
        copy(colors = colors + (key to normalizeColor(color, "colors.$key")))

    /** Returns an immutable, complete snapshot, suitable for history, persistence and renderer use. */
    fun snapshot(type: String = "circle", dark: Boolean = false): LabParameters {
        val baseline = defaults(type, dark)
        return copy(
            values = immutableMap(specs.associate { spec -> spec.key to (values[spec.key] ?: baseline[spec.key]) }),
            colors = immutableMap(colorKeys.associateWith { key ->
                normalizeColor(colors[key] ?: baseline.colors.getValue(key), "colors.$key")
            }),
        )
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put("values", JSONObject().apply { specs.forEach { put(it.key, this@LabParameters[it.key].toDouble()) } })
        .put("colors", JSONObject().apply { colorKeys.forEach { put(it, normalizeColor(colors.getValue(it), "colors.$it")) } })
        .put("iconBlend", iconBlend)
        .put("icon", icon)
        .put("glassEnabled", glassEnabled)
        .put("motionEnabled", motionEnabled)
        .put("springEnabled", springEnabled)
        .put("disabled", disabled)
        .put("shape", shape)
        .put("pressEffect", pressEffect)

    companion object {
        val types: List<String> = listOf("accent", "circle", "regular", "panel")
        val colorKeys: List<String> = listOf("surface", "icon", "text", "highlight", "border", "shadow", "background", "backgroundAccent", "contentOutline", "contentHighlight", "innerShadow", "sheen", "focus", "hover", "pressed", "trackingLight")
        val blendModes: List<String> = listOf("srcOver", "multiply", "screen", "overlay", "difference", "plus", "srcIn")
        val icons: List<String> = listOf("add", "send", "settings", "folder", "agent", "archive", "trash", "shield")
        val shapes: List<String> = listOf("rounded", "pill", "circle", "rectangle")
        val pressEffects: List<String> = listOf("none", "darken", "glow", "beam", "lift")

        val specs: List<LabParameterSpec> = listOf(
            LabParameterSpec("blurDp", "背景模糊", 0f, 48f, 2f, "dp", "玻璃"),
            LabParameterSpec("refractionScale", "折射幅度", 0f, 4f, 1f, "×", "玻璃"),
            LabParameterSpec("refractionFalloff", "折射衰减", 0.05f, 8f, 2f, "", "玻璃"),
            LabParameterSpec("dispersion", "色散", 0f, 0.8f, 0.10f, "", "玻璃"),
            LabParameterSpec("bevelScale", "透镜边缘宽度", 0f, 4f, 1f, "×", "玻璃"),
            LabParameterSpec("glassThicknessDp", "玻璃厚度", 0f, 36f, 7f, "dp", "玻璃"),
            LabParameterSpec("innerRefractionStrength", "内圈折射强度", 0f, 4f, 0f, "×", "玻璃"),
            LabParameterSpec("innerRefractionWidthDp", "内圈折射宽度", 0f, 24f, 4f, "dp", "玻璃"),
            LabParameterSpec("innerDispersion", "内圈色差", 0f, 1f, .18f, "", "玻璃"),
            LabParameterSpec("trackingLightWidthDp", "追光宽度", 1f, 80f, 18f, "dp", "反射与高光"),
            LabParameterSpec("trackingLightIntensity", "追光强度", 0f, 2f, .65f, "", "反射与高光"),
            LabParameterSpec("pressDarken", "按压变深", 0f, 1f, .12f, "", "按压与拖拽"),
            LabParameterSpec("reflectionStrength", "反射强度", 0f, 4f, 0.8f, "", "反射与高光"),
            LabParameterSpec("lightAngleDegrees", "光照方向", 0f, 360f, 233.1301f, "°", "反射与高光"),
            LabParameterSpec("highlightStrength", "按压高光", 0f, 4f, 1f, "", "反射与高光"),
            LabParameterSpec("rimStrength", "亮边强度", 0f, 1f, 0.05f, "", "反射与高光"),
            LabParameterSpec("borderWidthDp", "描边宽度", 0f, 12f, 0f, "dp", "描边与颜色"),
            LabParameterSpec("borderOpacity", "描边不透明度", 0f, 1f, 0f, "", "描边与颜色"),
            LabParameterSpec("saturation", "玻璃饱和度", 0f, 3f, 1.25f, "×", "描边与颜色"),
            LabParameterSpec("dimAmount", "玻璃压暗", 0f, 1f, 0f, "", "描边与颜色"),
            LabParameterSpec("surfaceAlpha", "整层材质不透明度", 0f, 1f, 1f, "", "描边与颜色"),
            LabParameterSpec("tintAlpha", "表面底色不透明度", 0f, 1f, 0.12f, "", "描边与颜色"),
            LabParameterSpec("pressScale", "按压缩放", 0.5f, 1.5f, 1.035f, "×", "按压与拖拽"),
            LabParameterSpec("dragStretch", "拖拽拉伸", 0f, 0.5f, 0.045f, "", "按压与拖拽"),
            LabParameterSpec("dragCompression", "拖拽压缩", 0f, 0.5f, 0.018f, "", "按压与拖拽"),
            LabParameterSpec("dragDistanceDp", "最大跟手距离", 0f, 48f, 6f, "dp", "按压与拖拽"),
            LabParameterSpec("dragResistance", "拖拽阻力", 0f, 4f, 1f, "×", "按压与拖拽"),
            LabParameterSpec("pressMillis", "按下时长", 0f, 2000f, 150f, "ms", "按压与拖拽"),
            LabParameterSpec("releaseMillis", "松手时长", 0f, 3000f, 360f, "ms", "按压与拖拽"),
            LabParameterSpec("shadowBlurDp", "阴影模糊范围", 0f, 48f, 5.76f, "dp", "阴影"),
            LabParameterSpec("shadowSpreadDp", "阴影扩散", 0f, 32f, 0f, "dp", "阴影"),
            LabParameterSpec("shadowOffsetXDp", "阴影横向距离", -32f, 32f, 0f, "dp", "阴影"),
            LabParameterSpec("shadowOffsetYDp", "阴影纵向距离", -32f, 32f, 1.2f, "dp", "阴影"),
            LabParameterSpec("shadowOpacity", "阴影不透明度", 0f, 1f, 0.08f, "", "阴影"),
            LabParameterSpec("widthDp", "组件宽度", 32f, 360f, 48f, "dp", "尺寸与内容"),
            LabParameterSpec("heightDp", "组件高度", 32f, 320f, 48f, "dp", "尺寸与内容"),
            LabParameterSpec("cornerDp", "圆角半径", 0f, 160f, 24f, "dp", "尺寸与内容"),
            LabParameterSpec("roundness", "圆角度 · 方形到圆形", 0f, 1f, 1f, "", "尺寸与内容"),
            LabParameterSpec("iconSizeDp", "图标大小", 8f, 64f, 23f, "dp", "尺寸与内容"),
            LabParameterSpec("iconAlpha", "图标不透明度", 0f, 1f, 1f, "", "尺寸与内容"),
            LabParameterSpec("textSizeSp", "文字大小", 8f, 32f, 15f, "sp", "尺寸与内容"),
            LabParameterSpec("textAlpha", "文字不透明度", 0f, 1f, 1f, "", "尺寸与内容"),
            LabParameterSpec("paddingDp", "内容边距", 0f, 32f, 12f, "dp", "尺寸与内容"),
            LabParameterSpec("iconTextGapDp", "图标文字间距", 0f, 32f, 8f, "dp", "尺寸与内容"),
            LabParameterSpec("textWeight", "文字粗细", 100f, 900f, 500f, "", "尺寸与内容"),
            LabParameterSpec("textLetterSpacingSp", "文字字距", -2f, 6f, 0f, "sp", "尺寸与内容"),
            LabParameterSpec("contentOffsetXDp", "内容横向偏移", -24f, 24f, 0f, "dp", "尺寸与内容"),
            LabParameterSpec("contentOffsetYDp", "内容纵向偏移", -24f, 24f, 0f, "dp", "尺寸与内容"),
            LabParameterSpec("rotationDegrees", "组件旋转", -180f, 180f, 0f, "°", "尺寸与内容"),
            LabParameterSpec("disabledAlpha", "禁用状态不透明度", 0f, 1f, 0.4f, "", "尺寸与内容"),
            LabParameterSpec("contentOutlineWidthDp", "内容描边宽度", 0f, 3f, 0f, "dp", "图标与文字效果"),
            LabParameterSpec("contentOutlineAlpha", "内容描边不透明度", 0f, 1f, 0f, "", "图标与文字效果"),
            LabParameterSpec("contentHighlightAlpha", "内容高光不透明度", 0f, 1f, 0f, "", "图标与文字效果"),
            LabParameterSpec("shadowPressScale", "按压阴影倍率", 0f, 2f, 1f, "×", "按压与拖拽"),
            LabParameterSpec("springDamping", "弹簧阻尼", 0.1f, 1f, 0.62f, "", "按压与拖拽"),
            LabParameterSpec("springStiffness", "弹簧刚度", 50f, 1200f, 380f, "", "按压与拖拽"),
            LabParameterSpec("grainAlpha", "表面颗粒不透明度", 0f, 0.3f, 0f, "", "表面纹理"),
            LabParameterSpec("grainSizeDp", "表面颗粒大小", 0.3f, 3f, 0.8f, "dp", "表面纹理"),
            LabParameterSpec("innerShadowAlpha", "内阴影不透明度", 0f, 1f, 0f, "", "表面纹理"),
            LabParameterSpec("innerShadowWidthDp", "内阴影宽度", 0f, 24f, 0f, "dp", "表面纹理"),
            LabParameterSpec("sheenAlpha", "斜向光泽不透明度", 0f, 1f, 0f, "", "表面纹理"),
            LabParameterSpec("sheenWidth", "斜向光泽宽度", 0f, 1f, 0.35f, "", "表面纹理"),
            LabParameterSpec("sheenAngleDegrees", "斜向光泽角度", 0f, 360f, 233.13f, "°", "表面纹理"),
            LabParameterSpec("focusBorderWidthDp", "聚焦描边宽度", 0f, 8f, 2f, "dp", "交互状态"),
            LabParameterSpec("focusBorderAlpha", "聚焦描边不透明度", 0f, 1f, 0.45f, "", "交互状态"),
            LabParameterSpec("hoverAlpha", "悬停底色不透明度", 0f, 1f, 0.06f, "", "交互状态"),
            LabParameterSpec("pressedTintAlpha", "按压底色不透明度", 0f, 1f, 0.08f, "", "交互状态"),
            LabParameterSpec("fusionStrength", "粘连强度", 0f, 1f, 0.55f, "", "粘连"),
            LabParameterSpec("fusionDistanceDp", "粘连触发距离", 0f, 64f, 18f, "dp", "粘连"),
            LabParameterSpec("fusionMillis", "粘连过渡时长", 0f, 1500f, 150f, "ms", "粘连"),
            LabParameterSpec("bridgeReleaseMillis", "松手变细断开时长", 0f, 1500f, 240f, "ms", "粘连"),
            LabParameterSpec("bridgeReachDp", "移动衔接范围", 0f, 64f, 24f, "dp", "粘连"),
            LabParameterSpec("buttonGapDp", "两个按钮的间距", 0f, 80f, 8f, "dp", "粘连"),
            LabParameterSpec("backgroundAlpha", "背景不透明度", 0f, 1f, 1f, "", "场景"),
            LabParameterSpec("backgroundPatternScale", "背景图案比例", 0.25f, 4f, 1f, "×", "场景"),
            LabParameterSpec("backgroundBlurDp", "场景整体模糊", 0f, 32f, 0f, "dp", "场景"),
        )
        val specByKey: Map<String, LabParameterSpec> = specs.associateBy { it.key }

        fun defaults(type: String = "circle", dark: Boolean = false): LabParameters {
            requireChoice(type, types, "type")
            val base = specs.associate { it.key to it.default }.toMutableMap()
            if (type != "circle") base["roundness"] = 0.35f
            when (type) {
                "accent" -> base.putAll(mapOf("widthDp" to 120f, "tintAlpha" to 0.82f, "pressScale" to 1.025f))
                "regular" -> base.putAll(mapOf("widthDp" to 132f, "tintAlpha" to 0.14f, "pressScale" to 1.025f))
                "panel" -> base.putAll(mapOf("widthDp" to 280f, "heightDp" to 160f, "blurDp" to 3.5f, "tintAlpha" to 0.16f, "pressScale" to 1f, "shadowOpacity" to 0.06f))
            }
            if (dark) {
                base["rimStrength"] = 0.10f
                base["reflectionStrength"] = if (type == "panel") 0.80f else 1.05f
                if (type == "circle") base["tintAlpha"] = 0.10f
            }
            val foreground = if (dark || type == "accent") "#FFFFFFFF" else "#FF202126"
            val colorValues = linkedMapOf(
                "surface" to if (type == "accent") { if (dark) "#FF557DF2" else "#FF3878ED" } else if (dark) "#FF1C1C1E" else "#FFFFFFFF",
                "icon" to foreground,
                "text" to foreground,
                "highlight" to "#FFFFFFFF",
                "border" to if (dark) "#FFFFFFFF" else "#FF202126",
                "shadow" to "#FF000000",
                "background" to if (dark) "#FF10131C" else "#FFE6ECF5",
                "backgroundAccent" to if (dark) "#FF384D86" else "#FFB9D6FB",
                "contentOutline" to if (dark) "#FF000000" else "#FFFFFFFF",
                "contentHighlight" to "#FFFFFFFF",
                "innerShadow" to "#FF000000",
                "sheen" to "#FFFFFFFF",
                "focus" to if (dark) "#FF82ADFF" else "#FF3878ED",
                "hover" to if (dark) "#FFFFFFFF" else "#FF000000",
                "pressed" to if (dark) "#FFFFFFFF" else "#FF000000",
                "trackingLight" to "#FFFFFFFF",
            )
            return LabParameters(immutableMap(base), immutableMap(colorValues), icon = if (type == "accent") "send" else "add")
        }

        internal fun fromJson(json: JSONObject, type: String, dark: Boolean, path: String, warnings: MutableList<String>): LabParameters {
            warnUnknown(json, setOf("values", "colors", "iconBlend", "icon", "glassEnabled", "motionEnabled", "springEnabled", "disabled", "shape", "pressEffect"), path, warnings)
            val base = defaults(type, dark)
            val values = base.values.toMutableMap()
            optionalObject(json, "values", path)?.let { objectValues ->
                warnUnknown(objectValues, specByKey.keys, "$path.values", warnings)
                specs.forEach { spec ->
                    if (objectValues.has(spec.key)) {
                        val value = objectValues.opt(spec.key) as? Number ?: invalid("$path.values.${spec.key}", "必须是数值")
                        val double = value.toDouble()
                        if (!double.isFinite() || double < spec.min.toDouble() || double > spec.max.toDouble()) {
                            invalid("$path.values.${spec.key}", "超出 ${spec.min}–${spec.max} 的有限范围")
                        }
                        val float = double.toFloat()
                        validateValue(spec, float, "$path.values.${spec.key}")
                        values[spec.key] = float
                    }
                }
                // Keep the visible radius of saved 1.0.0/1.0.1 profiles. A new,
                // omitted value must not reset an explicitly tuned old corner.
                if (!objectValues.has("roundness") && objectValues.has("cornerDp")) {
                    val oldShape = optionalString(json, "shape", path) ?: base.shape
                    values["roundness"] = when {
                        oldShape == "rectangle" -> 0f
                        oldShape == "circle" || oldShape == "pill" || type == "circle" -> 1f
                        else -> (2f * values.getValue("cornerDp") /
                            minOf(values.getValue("widthDp"), values.getValue("heightDp"))).coerceIn(0f, 1f)
                    }
                }
                if (!objectValues.has("bridgeReachDp") && objectValues.has("fusionDistanceDp")) {
                    values["bridgeReachDp"] = values.getValue("fusionDistanceDp")
                }
            }
            val colors = base.colors.toMutableMap()
            optionalObject(json, "colors", path)?.let { objectColors ->
                warnUnknown(objectColors, colorKeys.toSet(), "$path.colors", warnings)
                colorKeys.forEach { key ->
                    if (objectColors.has(key)) colors[key] = normalizeColor(requiredString(objectColors, key, "$path.colors"), "$path.colors.$key")
                }
            }
            return LabParameters(
                values = immutableMap(values), colors = immutableMap(colors),
                iconBlend = optionalString(json, "iconBlend", path) ?: base.iconBlend,
                icon = optionalString(json, "icon", path) ?: base.icon,
                pressEffect = optionalString(json, "pressEffect", path) ?: base.pressEffect,
                glassEnabled = optionalBoolean(json, "glassEnabled", path) ?: base.glassEnabled,
                motionEnabled = optionalBoolean(json, "motionEnabled", path) ?: base.motionEnabled,
                springEnabled = optionalBoolean(json, "springEnabled", path) ?: base.springEnabled,
                disabled = optionalBoolean(json, "disabled", path) ?: base.disabled,
                shape = optionalString(json, "shape", path) ?: base.shape,
            )
        }
    }
}

data class LabDocument(
    val light: Map<String, LabParameters>,
    val dark: Map<String, LabParameters>,
    val selectedTheme: String = "light",
    val selectedType: String = "circle",
    val backgroundPreset: String = "gradient",
    val compare: Boolean = false,
    val animateBackground: Boolean = false,
    val previewBackgroundColors: List<String> = emptyList(),
    val previewBackgroundLabel: String = "",
    val previewBackgroundImage: String = "none",
    val previewBackgroundImageData: String = "",
) {
    init {
        requireChoice(selectedTheme, themes, "selectedTheme")
        requireChoice(selectedType, LabParameters.types, "selectedType")
        requireChoice(backgroundPreset, backgroundPresets, "backgroundPreset")
        validateTheme(light, "light")
        validateTheme(dark, "dark")
        validateBackgroundColors(previewBackgroundColors)
        validateBackgroundLabel(previewBackgroundLabel)
        validateBackgroundImage(previewBackgroundImage, previewBackgroundImageData)
    }

    fun current(): LabParameters = if (selectedTheme == "dark") dark.getValue(selectedType) else light.getValue(selectedType)

    fun withParameters(parameters: LabParameters): LabDocument = if (selectedTheme == "dark") {
        copy(dark = immutableMap(dark + (selectedType to parameters.snapshot(selectedType, true))))
    } else {
        copy(light = immutableMap(light + (selectedType to parameters.snapshot(selectedType, false))))
    }

    /** Fully validates and owns the maps; changes to a caller's MutableMap cannot rewrite history. */
    fun snapshot(): LabDocument = copy(
        light = immutableMap(LabParameters.types.associateWith { light.getValue(it).snapshot(it, false) }),
        dark = immutableMap(LabParameters.types.associateWith { dark.getValue(it).snapshot(it, true) }),
        previewBackgroundColors = Collections.unmodifiableList(previewBackgroundColors.map { normalizeColor(it, "previewBackgroundColors") }),
    )

    /** Stable renderer data plus public build metadata; no formal-app state or device identity. */
    fun encode(): String {
        val document = snapshot()
        return JSONObject()
            .put("schema", SCHEMA)
            .put("renderer", RENDERER)
            .put("version", VERSION)
            .put("metadata", JSONObject()
                .put("rendererVersion", RENDERER_VERSION)
                .put("androidSdk", Build.VERSION.SDK_INT)
                .put("platform", "android")
                .put("colorFormat", "#AARRGGBB")
                .put("units", "dp/sp/ms"))
            .put("selectedTheme", document.selectedTheme)
            .put("selectedType", document.selectedType)
            .put("backgroundPreset", document.backgroundPreset)
            .put("compare", document.compare)
            .put("animateBackground", document.animateBackground)
            .put("previewBackgroundColors", JSONArray().apply { document.previewBackgroundColors.forEach { put(it) } })
            .put("previewBackgroundLabel", document.previewBackgroundLabel)
            .put("previewBackgroundImage", document.previewBackgroundImage)
            .put("previewBackgroundImageData", document.previewBackgroundImageData)
            .put("light", JSONObject().apply { LabParameters.types.forEach { put(it, document.light.getValue(it).toJson()) } })
            .put("dark", JSONObject().apply { LabParameters.types.forEach { put(it, document.dark.getValue(it).toJson()) } })
            .toString(2)
            .also { checkByteLimit(it) }
    }

    companion object {
        const val SCHEMA = 1
        const val RENDERER = "dafeiyu-native-glass"
        const val VERSION = "1.0.2"
        const val RENDERER_VERSION = "material-lab-v1"
        const val MAX_IMPORT_BYTES = 256 * 1024
        const val MAX_IMAGE_BYTES = 112 * 1024
        const val MAX_IMAGE_DATA_CHARACTERS = 152920
        private var validatedImageData: String? = null
        val themes: List<String> = listOf("light", "dark")
        val backgroundPresets: List<String> = listOf("gradient", "solid", "checker", "stripes", "text", "aurora")

        fun defaults(): LabDocument = LabDocument(
            light = immutableMap(LabParameters.types.associateWith { LabParameters.defaults(it, false) }),
            dark = immutableMap(LabParameters.types.associateWith { LabParameters.defaults(it, true) }),
        )

        fun decode(text: String): LabDocument = decodeWithWarnings(text).document

        fun decodeWithWarnings(text: String): LabImportResult {
            val json = parseObject(text)
            val schema = json.opt("schema") as? Number ?: invalid("schema", "必须是整数")
            if (schema.toDouble() != SCHEMA.toDouble()) invalid("schema", "不支持的格式版本")
            if (requiredString(json, "renderer", "") != RENDERER) invalid("renderer", "不是本调试器的渲染参数")
            val warnings = mutableListOf<String>()
            warnUnknown(json, setOf("schema", "renderer", "version", "metadata", "selectedTheme", "selectedType", "backgroundPreset", "compare", "animateBackground", "previewBackgroundColors", "previewBackgroundLabel", "previewBackgroundImage", "previewBackgroundImageData", "light", "dark"), "", warnings)
            optionalString(json, "version", "")?.let { if (it.length > 64) invalid("version", "版本标记过长") }
            optionalObject(json, "metadata", "")
            fun decodeTheme(name: String): Map<String, LabParameters> {
                val theme = requiredObject(json, name, "")
                warnUnknown(theme, LabParameters.types.toSet(), name, warnings)
                return immutableMap(LabParameters.types.associateWith { type ->
                    if (!theme.has(type)) {
                        warning(warnings, "$name.$type 缺失，已使用默认值")
                        LabParameters.defaults(type, name == "dark")
                    } else {
                        LabParameters.fromJson(requiredObject(theme, type, name), type, name == "dark", "$name.$type", warnings)
                    }
                })
            }
            val document = LabDocument(
                light = decodeTheme("light"), dark = decodeTheme("dark"),
                selectedTheme = optionalString(json, "selectedTheme", "") ?: "light",
                selectedType = optionalString(json, "selectedType", "") ?: "circle",
                backgroundPreset = optionalString(json, "backgroundPreset", "") ?: "gradient",
                compare = optionalBoolean(json, "compare", "") ?: false,
                animateBackground = optionalBoolean(json, "animateBackground", "") ?: false,
                previewBackgroundColors = decodeBackgroundColors(json),
                previewBackgroundLabel = optionalString(json, "previewBackgroundLabel", "") ?: "",
                previewBackgroundImage = optionalString(json, "previewBackgroundImage", "") ?: "none",
                previewBackgroundImageData = optionalString(json, "previewBackgroundImageData", "") ?: "",
            )
            return LabImportResult(document, warnings.toList())
        }

        /** Our persisted snapshots are complete; missing stored groups are damage, not defaults. */
        internal fun decodeStored(text: String): LabDocument {
            val json = parseObject(text)
            for (themeName in themes) {
                val theme = requiredObject(json, themeName, "")
                for (type in LabParameters.types) {
                    val parameter = requiredObject(theme, type, themeName)
                    val values = requiredObject(parameter, "values", "$themeName.$type")
                    val colors = requiredObject(parameter, "colors", "$themeName.$type")
                    LabParameters.specs.forEach { if (!values.has(it.key)) invalid("$themeName.$type.values.${it.key}", "保存文件缺少参数") }
                    LabParameters.colorKeys.forEach { if (!colors.has(it)) invalid("$themeName.$type.colors.$it", "保存文件缺少颜色") }
                    listOf("iconBlend", "icon", "glassEnabled", "motionEnabled", "springEnabled", "disabled", "shape").forEach {
                        if (!parameter.has(it)) invalid("$themeName.$type.$it", "保存文件缺少参数")
                    }
                }
            }
            return decode(text)
        }

        private fun decodeBackgroundColors(json: JSONObject): List<String> {
            if (!json.has("previewBackgroundColors")) return emptyList()
            val array = json.opt("previewBackgroundColors") as? JSONArray ?: invalid("previewBackgroundColors", "必须是颜色数组")
            if (array.length() > 4) invalid("previewBackgroundColors", "最多为四个颜色")
            return (0 until array.length()).map { index ->
                normalizeColor(array.opt(index) as? String ?: invalid("previewBackgroundColors[$index]", "必须是颜色字符串"), "previewBackgroundColors[$index]")
            }
        }

        private fun validateBackgroundColors(colors: List<String>) {
            if (colors.size > 4) invalid("previewBackgroundColors", "最多为四个颜色")
            colors.forEachIndexed { index, color -> normalizeColor(color, "previewBackgroundColors[$index]") }
        }

        private fun validateBackgroundLabel(label: String) {
            if (label.codePointCount(0, label.length) > 80 || label.any { Character.isISOControl(it) }) {
                invalid("previewBackgroundLabel", "最多为 80 个字符，且不能包含控制字符")
            }
        }

        /** One validated immutable image is cached; slider updates never decode the same photo again. */
        @Synchronized
        private fun validateBackgroundImage(source: String, data: String) {
            requireChoice(source, listOf("none", "reference", "photo"), "previewBackgroundImage")
            if (source != "photo") {
                if (data.isNotEmpty()) invalid("previewBackgroundImageData", "非相册背景不能携带图片数据")
                return
            }
            if (data.isEmpty() || data.length > MAX_IMAGE_DATA_CHARACTERS) {
                invalid("previewBackgroundImageData", "相册图片缺失或超过允许大小")
            }
            if (validatedImageData == data) return
            val bytes = try { java.util.Base64.getDecoder().decode(data) }
                catch (_: IllegalArgumentException) { invalid("previewBackgroundImageData", "图片数据不是有效的 Base64") }
            if (bytes.size < 10 || bytes.size > MAX_IMAGE_BYTES ||
                bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte() ||
                bytes[bytes.lastIndex - 1] != 0xFF.toByte() || bytes.last() != 0xD9.toByte()) {
                invalid("previewBackgroundImageData", "图片必须是经过限制大小的 JPEG")
            }
            if (java.util.Base64.getEncoder().encodeToString(bytes) != data) {
                invalid("previewBackgroundImageData", "图片编码格式不正确")
            }
            validatedImageData = data
        }

        private fun validateTheme(theme: Map<String, LabParameters>, path: String) {
            if (theme.keys != LabParameters.types.toSet()) invalid(path, "必须包含四种独立组件参数")
            theme.forEach { (type, parameters) -> parameters.snapshot(type, path == "dark") }
        }
    }
}

data class LabImportResult(val document: LabDocument, val warnings: List<String>)
class LabFormatException(message: String) : IllegalArgumentException(message)

/** Gesture-aware bounded history. A slider drag is one record, rather than one record per frame. */
class LabUndoHistory(initial: LabDocument, val maxEntries: Int = 80) {
    init { require(maxEntries in 1..200) { "历史上限必须为 1–200" } }
    private val undo = ArrayDeque<LabDocument>()
    private val redo = ArrayDeque<LabDocument>()
    private var gestureStart: LabDocument? = null
    var current: LabDocument = initial.snapshot()
        private set
    val canUndo: Boolean get() = undo.isNotEmpty() || gestureStart?.let { it != current } == true
    val canRedo: Boolean get() = redo.isNotEmpty() && gestureStart == null
    val undoDepth: Int get() = undo.size
    val redoDepth: Int get() = redo.size

    fun update(next: LabDocument): Boolean {
        val snapshot = next.snapshot()
        if (snapshot == current) return false
        if (gestureStart == null) {
            push(undo, current)
            redo.clear()
        }
        current = snapshot
        return true
    }

    fun beginGesture() { if (gestureStart == null) gestureStart = current }

    fun endGesture(): Boolean {
        val start = gestureStart ?: return false
        gestureStart = null
        if (start == current) return false
        push(undo, start)
        redo.clear()
        return true
    }

    fun cancelGesture() {
        gestureStart?.let { current = it }
        gestureStart = null
    }

    fun undo(): LabDocument? {
        endGesture()
        if (undo.isEmpty()) return null
        push(redo, current)
        current = undo.removeLast()
        return current
    }

    fun redo(): LabDocument? {
        endGesture()
        if (redo.isEmpty()) return null
        push(undo, current)
        current = redo.removeLast()
        return current
    }

    fun clear() {
        endGesture()
        undo.clear()
        redo.clear()
    }

    private fun push(queue: ArrayDeque<LabDocument>, document: LabDocument) {
        if (queue.size == maxEntries) queue.removeFirst()
        queue.addLast(document)
    }
}

data class LabSavedPreset(val id: String, val name: String, val savedAtMillis: Long)

/** Files stay exclusively inside this package's private filesDir. No storage permission is used. */
class LabPresetStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "material-lab")
    private val presets = File(root, "presets")
    internal val storageKey: String = root.absolutePath
    private val fileLock = fileLockFor(storageKey)
    @Volatile
    var lastError: String? = null
        private set
    @Volatile
    var recoveryNotice: String? = null
        private set
    private var preserveCurrent = false

    fun load(): LabDocument? = synchronized(fileLock) {
        lastError = null
        recoveryNotice = null
        preserveCurrent = false
        val current = File(root, "current.json")
        if (existsAtomic(current)) {
            try {
                return@synchronized LabDocument.decodeStored(readAtomic(current))
            } catch (error: Exception) {
                preserveCurrent = true
                lastError = "上次参数无法读取，原文件已保留：${error.message?.take(180)}"
            }
        }
        for (name in listOf("current.continued.json", "last-good.json")) {
            val fallback = File(root, name)
            if (!existsAtomic(fallback)) continue
            try {
                val restored = LabDocument.decodeStored(readAtomic(fallback))
                recoveryNotice = if (name == "current.continued.json") "已载入继续调节的参数；损坏原件保留"
                    else "已从上次有效版本恢复参数；损坏原件保留"
                return@synchronized restored
            } catch (error: Exception) {
                lastError = "恢复文件也无法读取，原件保留：${error.message?.take(180)}"
            }
        }
        null
    }

    fun save(document: LabDocument): Unit = synchronized(fileLock) {
        val encoded = document.encode()
        val current = File(root, "current.json")
        // Revalidate on every write. Another instance or an interrupted previous write must not
        // turn an unreadable file into permission to overwrite the only recoverable raw copy.
        if (!preserveCurrent && existsAtomic(current)) {
            try { LabDocument.decodeStored(readAtomic(current)) } catch (_: Exception) {
                preserveCurrent = true
                recoveryNotice = "损坏原件保留；新参数已另存，可继续调节"
            }
        }
        val target = if (preserveCurrent) File(root, "current.continued.json") else current
        val previous = if (existsAtomic(target)) {
            try { readAtomic(target).also { LabDocument.decodeStored(it) } } catch (_: Exception) {
                preserveDamaged(target)
                null
            }
        } else null
        val backup = File(root, "last-good.json")
        if (existsAtomic(backup)) {
            try { LabDocument.decodeStored(readAtomic(backup)) } catch (_: Exception) { preserveDamaged(backup) }
        }
        writeAtomic(backup, previous ?: encoded)
        writeAtomic(target, encoded)
        lastError = null
        if (preserveCurrent) recoveryNotice = "损坏原件保留；新参数已另存，可继续调节"
        Unit
    }

    fun savePreset(name: String, document: LabDocument): LabSavedPreset = synchronized(fileLock) {
        val validName = validatePresetName(name)
        if (listPresets().size >= MAX_PRESETS) invalid("preset", "最多保存 $MAX_PRESETS 个预设，请先删除不需要的预设")
        val result = LabSavedPreset(UUID.randomUUID().toString(), validName, System.currentTimeMillis())
        val envelope = JSONObject()
            .put("schema", 1)
            .put("id", result.id)
            .put("name", result.name)
            .put("savedAtMillis", result.savedAtMillis)
            .put("document", JSONObject(document.encode()))
        writeAtomic(presetFile(result.id), envelope.toString(2))
        lastError = null
        result
    }

    fun listPresets(): List<LabSavedPreset> = synchronized(fileLock) {
        lastError = null
        presetIds().mapNotNull { id -> readOrReport { readPreset(id).first } }
            .sortedWith(compareByDescending<LabSavedPreset> { it.savedAtMillis }.thenBy { it.name })
    }

    fun loadPreset(id: String): LabDocument? = synchronized(fileLock) {
        lastError = null
        val file = presetFile(id)
        if (!existsAtomic(file)) return@synchronized null
        readOrReport { readPreset(id).second }
    }

    fun deletePreset(id: String): Boolean = synchronized(fileLock) {
        val file = presetFile(id)
        if (!existsAtomic(file)) return@synchronized false
        AtomicFile(file).delete()
        if (existsAtomic(file)) throw IOException("无法删除预设")
        lastError = null
        true
    }

    private fun readPreset(id: String): Pair<LabSavedPreset, LabDocument> {
        val json = parseObject(readAtomic(presetFile(id)))
        if (json.opt("schema") != 1) invalid("preset.schema", "不支持的预设格式")
        if (requiredString(json, "id", "preset") != id) invalid("preset.id", "与文件不匹配")
        val name = validatePresetName(requiredString(json, "name", "preset"))
        val savedAt = json.opt("savedAtMillis") as? Number ?: invalid("preset.savedAtMillis", "必须是整数")
        val savedDouble = savedAt.toDouble()
        val savedLong = savedAt.toLong()
        if (!savedDouble.isFinite() || savedLong < 0 || savedDouble != savedLong.toDouble()) invalid("preset.savedAtMillis", "必须是非负整数")
        val document = LabDocument.decodeStored(requiredObject(json, "document", "preset").toString())
        return LabSavedPreset(id, name, savedLong) to document
    }

    private fun presetIds(): List<String> = presets.listFiles().orEmpty().mapNotNull { file ->
        if (!file.isFile) return@mapNotNull null
        val name = file.name.removeSuffix(".bak").removeSuffix(".new")
        if (!name.endsWith(".json")) return@mapNotNull null
        name.removeSuffix(".json").takeIf { PRESET_ID.matches(it) }
    }.distinct().take(MAX_PRESETS + 1)

    private fun presetFile(id: String): File {
        if (!PRESET_ID.matches(id)) invalid("preset.id", "预设标识无效")
        return File(presets, "$id.json")
    }

    private fun <T> readOrReport(block: () -> T): T? = try { block() } catch (error: Exception) {
        lastError = error.message?.take(240) ?: "读取预设失败"
        null
    }

    private fun writeAtomic(file: File, text: String) {
        checkByteLimit(text)
        val directory = file.parentFile ?: throw IOException("存储目录无效")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建私有预设目录")
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            val stored = atomic.openRead().use { readBounded(it).toString(Charsets.UTF_8) }
            if (stored != text) throw IOException("保存后校验失败，未确认参数已保存")
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun readAtomic(file: File): String {
        // AtomicFile restores a legacy .bak by replacing the base without validating either.
        // Preserve both raw files first: even a valid base must survive an invalid old backup.
        if (file.isFile && File(file.path + ".bak").isFile) {
            preserveDamaged(file)
            recoveryNotice = "中断保存的原件已保留；已尝试恢复原子备份"
        }
        return AtomicFile(file).openRead().use { input -> readBounded(input).toString(Charsets.UTF_8) }
    }

    /** Keep damaged continuation/backup bytes before replacement; never delete older originals. */
    private fun preserveDamaged(file: File) {
        val recovery = File(root, "recovery")
        if (!recovery.isDirectory && !recovery.mkdirs()) throw IOException("无法保留损坏原件，未覆盖参数")
        val sources = listOf(file, File(file.path + ".bak")).filter { it.isFile }
        if (recovery.listFiles().orEmpty().count { it.isFile } + sources.size > MAX_RECOVERY_FILES) {
            throw IOException("损坏原件保留区已满，未覆盖参数；请先导出当前参数文件")
        }
        // A damaged .bak is also retained. A .new is an uncommitted staging file, not a snapshot.
        for (source in sources) {
            val destination = File(recovery, "${source.name}.${UUID.randomUUID()}.raw")
            var written = false
            try {
                source.inputStream().use { input ->
                    java.io.FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            if (total > MAX_RECOVERY_BYTES) throw IOException("损坏文件过大，保留原位并取消覆盖")
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                written = true
            } finally {
                if (!written) destination.delete()
            }
        }
    }

    companion object {
        const val MAX_PRESETS = 100
        // AtomicFile.openRead may delete a concurrent writer's .new. Share locks across all
        // instances for the same root, covering complete read/write/recovery transactions.
        // Stripes have process lifetime and a fixed memory bound; hash collisions only serialize
        // unrelated roots. JVM monitors are reentrant for savePreset -> listPresets.
        private val fileLocks = Array(32) { Any() }
        private fun fileLockFor(key: String): Any =
            fileLocks[(key.hashCode() and Int.MAX_VALUE) % fileLocks.size]
        private const val MAX_RECOVERY_FILES = 20
        private const val MAX_RECOVERY_BYTES = 1024L * 1024L
        private val PRESET_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private fun existsAtomic(file: File): Boolean = file.exists() || File(file.path + ".bak").exists()
    }
}

private fun parseObject(text: String): JSONObject {
    checkByteLimit(text)
    checkNesting(text)
    return try {
        val tokener = JSONTokener(text)
        val json = tokener.nextValue() as? JSONObject ?: invalid("document", "根节点必须是对象")
        if (tokener.nextClean() != '\u0000') invalid("document", "对象之后存在多余内容")
        json
    } catch (error: LabFormatException) {
        throw error
    } catch (error: Exception) {
        throw LabFormatException("JSON 格式错误：${error.message?.take(180) ?: "无法解析"}")
    }
}

private fun checkByteLimit(text: String) {
    if (text.length > LabDocument.MAX_IMPORT_BYTES || text.toByteArray(Charsets.UTF_8).size > LabDocument.MAX_IMPORT_BYTES) {
        invalid("document", "文件不可超过 256 KiB")
    }
}

/** Prevent a deeply nested unknown field from exhausting JSONTokener's recursive parser stack. */
private fun checkNesting(text: String) {
    var depth = 0
    var quoted = false
    var escaped = false
    text.forEach { character ->
        if (quoted) {
            if (escaped) escaped = false else when (character) {
                '\\' -> escaped = true
                '"' -> quoted = false
            }
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> { depth++; if (depth > 12) invalid("document", "JSON 嵌套过深") }
            '}', ']' -> { depth--; if (depth < 0) invalid("document", "JSON 括号不匹配") }
        }
    }
    if (depth != 0 || quoted) invalid("document", "JSON 未完整结束")
}

private fun readBounded(input: InputStream): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total += count
        if (total > LabDocument.MAX_IMPORT_BYTES) invalid("document", "文件不可超过 256 KiB")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun validateValue(spec: LabParameterSpec, value: Float, path: String) {
    if (!value.isFinite() || value !in spec.min..spec.max) invalid(path, "超出 ${spec.min}–${spec.max} 的有限范围")
}

private fun normalizeColor(value: String, path: String): String {
    if (!COLOR_PATTERN.matches(value)) invalid(path, "颜色必须为 #RRGGBB 或 #AARRGGBB")
    return (if (value.length == 7) "#FF${value.substring(1)}" else value).uppercase(Locale.ROOT)
}

private fun requireChoice(value: String, allowed: List<String>, path: String) {
    if (value !in allowed) invalid(path, "不支持的选项")
}

private fun requiredString(json: JSONObject, key: String, path: String): String =
    json.opt(key) as? String ?: invalid(join(path, key), "必须是字符串")

private fun optionalString(json: JSONObject, key: String, path: String): String? =
    if (json.has(key)) requiredString(json, key, path) else null

private fun optionalBoolean(json: JSONObject, key: String, path: String): Boolean? =
    if (json.has(key)) json.opt(key) as? Boolean ?: invalid(join(path, key), "必须为 true 或 false") else null

private fun requiredObject(json: JSONObject, key: String, path: String): JSONObject =
    json.opt(key) as? JSONObject ?: invalid(join(path, key), "必须是对象")

private fun optionalObject(json: JSONObject, key: String, path: String): JSONObject? =
    if (json.has(key)) requiredObject(json, key, path) else null

private fun warnUnknown(json: JSONObject, allowed: Set<String>, path: String, warnings: MutableList<String>) {
    json.keys().forEach { if (it !in allowed) warning(warnings, "已忽略未知字段 ${join(path, it).take(160)}") }
}

private fun warning(warnings: MutableList<String>, message: String) {
    if (warnings.size < 64) warnings += message
}

private fun validatePresetName(raw: String): String {
    val name = raw.trim()
    if (name.isEmpty() || name.codePointCount(0, name.length) > 64 || name.any { Character.isISOControl(it) }) {
        invalid("preset.name", "名称须为 1–64 个字符，且不能包含控制字符")
    }
    return name
}

private fun join(path: String, key: String): String = if (path.isEmpty()) key else "$path.$key"
private fun invalid(path: String, reason: String): Nothing = throw LabFormatException("$path：$reason")
private fun <K, V> immutableMap(map: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(map))
private val COLOR_PATTERN = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
