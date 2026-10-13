package com.dafeiyu.mobile.glasslab

import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import java.util.Base64
import javax.imageio.ImageIO
import org.json.JSONArray
import org.json.JSONObject

/** JVM-only data contract checks. Android persistence/rendering require separate device checks. */
fun main(arguments: Array<String>) {
    val results = JSONArray()
    fun checkCase(name: String, block: () -> Unit) {
        try {
            block()
            results.put(JSONObject().put("name", name).put("passed", true))
        } catch (error: Throwable) {
            results.put(JSONObject().put("name", name).put("passed", false).put("error", error.toString()))
        }
    }
    fun mustReject(block: () -> Any?) {
        try { block() } catch (_: LabFormatException) { return }
        error("Invalid document was accepted")
    }
    val baseline = LabDocument.defaults()
    val encoded = baseline.encode()
    val jpegBytes = ByteArrayOutputStream().use { output ->
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, 0x3055D0); image.setRGB(1, 0, 0x60DADA)
        image.setRGB(0, 1, 0xFA9FB0); image.setRGB(1, 1, 0xFFFFFF)
        check(ImageIO.write(image, "jpeg", output))
        output.toByteArray()
    }
    val jpegData = Base64.getEncoder().encodeToString(jpegBytes)
    // Standard JPEG COM segments retain a decodable image while exercising the byte cap.
    fun paddedJpeg(totalBytes: Int): ByteArray {
        require(totalBytes >= jpegBytes.size + 4)
        return ByteArrayOutputStream(totalBytes).use { output ->
            output.write(jpegBytes, 0, 2)
            var remaining = totalBytes - jpegBytes.size
            while (remaining > 0) {
                val segmentBytes = minOf(65_537, remaining)
                require(segmentBytes >= 4)
                output.write(0xFF); output.write(0xFE)
                val segmentLength = segmentBytes - 2
                output.write(segmentLength ushr 8); output.write(segmentLength and 255)
                output.write(ByteArray(segmentBytes - 4))
                remaining -= segmentBytes
            }
            output.write(jpegBytes, 2, jpegBytes.size - 2)
            output.toByteArray().also { check(it.size == totalBytes) }
        }
    }
    fun changedJson(block: (JSONObject) -> Unit): String = JSONObject(encoded).also(block).toString()
    fun circle(root: JSONObject): JSONObject = root.getJSONObject("light").getJSONObject("circle")

    checkCase("complete_defaults_and_round_trip") {
        check(LabDocument.decode(encoded) == baseline)
        for (theme in LabDocument.themes) for (type in LabParameters.types) {
            val parameters = baseline.copy(selectedTheme = theme, selectedType = type).current()
            check(parameters.values.keys == LabParameters.specByKey.keys)
            check(parameters.colors.keys == LabParameters.colorKeys.toSet())
        }
        check(LabParameters.specs.map { it.key }.distinct().size == LabParameters.specs.size)
        check(baseline.light["circle"] != baseline.dark["circle"])
        check(baseline.light["accent"] != baseline.light["circle"])
    }
    checkCase("each_theme_type_is_independent") {
        var doc = baseline
        for ((themeIndex, theme) in LabDocument.themes.withIndex()) {
            for ((typeIndex, type) in LabParameters.types.withIndex()) {
                doc = doc.copy(selectedTheme = theme, selectedType = type)
                    .withParameters(doc.copy(selectedTheme = theme, selectedType = type).current()
                        .withValue("shadowOpacity", (themeIndex * 4 + typeIndex + 1) / 10f)
                        .withColor("icon", "#12AB3C"))
            }
        }
        val decoded = LabDocument.decode(doc.encode())
        check(decoded == doc)
        check(decoded.light.values.map { it["shadowOpacity"] }.toSet().size == 4)
        check(decoded.dark.values.map { it["shadowOpacity"] }.toSet().size == 4)
        check(decoded.dark.values.none { it["shadowOpacity"] in decoded.light.values.map { p -> p["shadowOpacity"] } })
        check(decoded.current().colors.getValue("icon") == "#FF12AB3C")
        check(baseline.current()["shadowOpacity"] == 0.08f)
    }
    checkCase("every_parameter_accepts_both_edges") {
        LabParameters.specs.forEach { spec ->
            for (edge in listOf(spec.min, spec.max)) {
                val doc = baseline.withParameters(baseline.current().withValue(spec.key, edge))
                check(LabDocument.decode(doc.encode()).current()[spec.key] == edge)
            }
        }
    }
    checkCase("every_parameter_rejects_out_of_range_without_partial_import") {
        LabParameters.specs.forEach { spec ->
            for (bad in listOf(spec.min - 1f, spec.max + 1f)) {
                mustReject { baseline.current().withValue(spec.key, bad) }
                val invalid = changedJson { root -> circle(root).getJSONObject("values").put(spec.key, bad.toDouble()) }
                mustReject { LabDocument.decode(invalid) }
            }
        }
        check(baseline == LabDocument.defaults())
    }
    checkCase("nan_infinity_and_wrong_types_rejected") {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { bad ->
            mustReject { baseline.current().withValue("shadowOpacity", bad) }
        }
        listOf("1e999", "-1e999", "\"NaN\"", "\"Infinity\"", "null", "true").forEach { token ->
            val text = encoded.replace("\"blurDp\": 2", "\"blurDp\": $token")
            check(text != encoded)
            mustReject { LabDocument.decode(text) }
        }
        mustReject { LabDocument.decode(changedJson { circle(it).put("glassEnabled", 1) }) }
        mustReject { LabDocument.decode(changedJson { it.put("compare", "false") }) }
    }
    checkCase("all_enumerations_shapes_and_booleans_round_trip") {
        for (blend in LabParameters.blendModes) for (icon in LabParameters.icons) for (shape in LabParameters.shapes) {
            val parameters = baseline.current().copy(iconBlend = blend, icon = icon, shape = shape,
                glassEnabled = false, motionEnabled = false, springEnabled = false, disabled = true)
            check(LabDocument.decode(baseline.withParameters(parameters).encode()).current() == parameters)
        }
        LabDocument.backgroundPresets.forEach {
            val doc = baseline.copy(backgroundPreset = it, compare = true, animateBackground = true)
            check(LabDocument.decode(doc.encode()) == doc)
        }
    }
    checkCase("invalid_enumerations_rejected") {
        mustReject { baseline.current().copy(iconBlend = "invalid") }
        mustReject { baseline.current().copy(icon = "invalid") }
        mustReject { baseline.current().copy(shape = "invalid") }
        mustReject { baseline.current().copy(pressEffect = "invalid") }
        mustReject { baseline.copy(selectedTheme = "system") }
        mustReject { baseline.copy(selectedType = "other") }
        mustReject { baseline.copy(backgroundPreset = "network") }
        mustReject { LabDocument.decode(changedJson { circle(it).put("shape", "invalid") }) }
        mustReject { LabDocument.decode(changedJson { circle(it).put("pressEffect", "invalid") }) }
    }
    checkCase("press_modes_and_legacy_radius_preserved") {
        LabParameters.pressEffects.forEach { mode ->
            val parameters = baseline.current().copy(pressEffect = mode)
            check(LabDocument.decode(baseline.withParameters(parameters).encode()).current() == parameters)
        }
        val legacy = changedJson { root ->
            root.getJSONObject("light").getJSONObject("regular").getJSONObject("values")
                .remove("roundness")
            root.getJSONObject("light").getJSONObject("regular").getJSONObject("values")
                .put("cornerDp", 12)
            circle(root).getJSONObject("values").remove("roundness")
            circle(root).getJSONObject("values").put("cornerDp", 0)
            circle(root).getJSONObject("values").remove("bridgeReachDp")
            circle(root).getJSONObject("values").put("fusionDistanceDp", 31)
        }
        val restored = LabDocument.decode(legacy)
        check(restored.light.getValue("regular")["roundness"] == .5f)
        check(restored.light.getValue("circle")["roundness"] == 1f)
        check(restored.light.getValue("circle")["bridgeReachDp"] == 31f)
        check(LabDocument.decode(restored.encode()) == restored)
    }
    checkCase("color_canonicalization_and_rejection") {
        val parameters = baseline.current().withColor("icon", "#ab12cd").withColor("text", "#40ab12cd")
        check(parameters.colors["icon"] == "#FFAB12CD")
        check(parameters.colors["text"] == "#40AB12CD")
        listOf("#fff", "red", "#1234567890", "123456", "#GGGGGG", " #AB12CD").forEach {
            mustReject { baseline.current().withColor("icon", it) }
        }
        mustReject { baseline.current().withColor("unknown", "#123456") }
        mustReject { LabDocument.decode(changedJson { circle(it).getJSONObject("colors").put("icon", 123) }) }
    }
    checkCase("unknown_fields_warn_and_do_not_survive_export") {
        val source = changedJson {
            it.put("futureDocument", JSONObject().put("harmless", true))
            it.getJSONObject("light").put("futureType", JSONObject())
            circle(it).put("futureParameter", true)
            circle(it).getJSONObject("values").put("futureSlider", 15)
            circle(it).getJSONObject("colors").put("futureColor", "#FF000000")
        }
        val result = LabDocument.decodeWithWarnings(source)
        check(result.document == baseline)
        check(result.warnings.size == 5)
        check(!result.document.encode().contains("future"))
    }
    checkCase("missing_known_optional_fields_expand_to_complete_defaults") {
        val result = LabDocument.decodeWithWarnings(changedJson {
            circle(it).remove("values")
            circle(it).remove("colors")
            it.getJSONObject("dark").remove("panel")
        })
        check(result.document == baseline)
        check(result.warnings.any { it.startsWith("dark.panel") })
        check(result.document.current().values.size == LabParameters.specs.size)
    }
    checkCase("schema_renderer_trailing_content_and_nesting_rejected") {
        mustReject { LabDocument.decode(changedJson { it.put("schema", 2) }) }
        mustReject { LabDocument.decode(changedJson { it.put("schema", "1") }) }
        mustReject { LabDocument.decode(changedJson { it.put("renderer", "another") }) }
        mustReject { LabDocument.decode("$encoded true") }
        mustReject { LabDocument.decode("[]") }
        mustReject { LabDocument.decode("{\"future\":" + "[".repeat(14) + "0" + "]".repeat(14) + "}") }
        mustReject { LabDocument.decode("{\"unterminated\":\"value}") }
    }
    checkCase("256kib_utf8_limit_enforced") {
        mustReject { LabDocument.decode(" ".repeat(LabDocument.MAX_IMPORT_BYTES + 1)) }
        val utf8Oversized = changedJson { it.put("unknown", "中".repeat(90_000)) }
        check(utf8Oversized.length < LabDocument.MAX_IMPORT_BYTES)
        check(utf8Oversized.toByteArray(Charsets.UTF_8).size > LabDocument.MAX_IMPORT_BYTES)
        mustReject { LabDocument.decode(utf8Oversized) }
    }
    checkCase("exports_only_whitelisted_material_and_public_metadata") {
        val root = JSONObject(encoded)
        check(root.getString("renderer") == "dafeiyu-native-glass")
        check(root.getJSONObject("metadata").has("androidSdk"))
        listOf("apiKey", "deviceId", "pairing", "token", "credentials", "endpoint", "path").forEach {
            check(!encoded.contains("\"$it\"", ignoreCase = true))
        }
    }
    checkCase("global_preview_background_round_trip_and_type_independence") {
        val document = baseline.copy(previewBackgroundColors = listOf("#FF192033", "#FF456789", "#FFEACB91", "#FFFFAABB"),
            previewBackgroundLabel = "四层渐变")
        check(LabDocument.decode(document.encode()) == document)
        for (theme in LabDocument.themes) for (type in LabParameters.types) {
            val changed = document.copy(selectedTheme = theme, selectedType = type)
                .withParameters(LabParameters.defaults(type, theme == "dark").withColor("background", "#FF000000"))
            check(changed.previewBackgroundColors == document.previewBackgroundColors)
            check(changed.previewBackgroundLabel == document.previewBackgroundLabel)
            check(LabDocument.decode(changed.encode()).previewBackgroundColors == document.previewBackgroundColors)
        }
        val immutable = document.snapshot().previewBackgroundColors as MutableList<String>
        try { immutable.add("#FF000000"); error("Scene colors were mutable") } catch (_: UnsupportedOperationException) { }
    }
    checkCase("global_preview_background_old_files_and_validation") {
        val legacy = changedJson { it.remove("previewBackgroundColors"); it.remove("previewBackgroundLabel") }
        check(LabDocument.decode(legacy).previewBackgroundColors.isEmpty())
        check(LabDocument.decode(legacy).previewBackgroundLabel.isEmpty())
        val normalized = LabDocument.decode(changedJson {
            it.put("previewBackgroundColors", JSONArray().put("#ab12cd").put("#40ab12cd"))
        })
        check(normalized.previewBackgroundColors == listOf("#FFAB12CD", "#40AB12CD"))
        mustReject { baseline.copy(previewBackgroundColors = List(5) { "#FFFFFFFF" }) }
        mustReject { baseline.copy(previewBackgroundColors = listOf("#GGGGGG")) }
        mustReject { baseline.copy(previewBackgroundLabel = "长".repeat(81)) }
        mustReject { baseline.copy(previewBackgroundLabel = "含\n控制字符") }
        mustReject { LabDocument.decode(changedJson { it.put("previewBackgroundColors", "#FFFFFFFF") }) }
        mustReject { LabDocument.decode(changedJson { it.put("previewBackgroundColors", JSONArray().put(123)) }) }
        mustReject { LabDocument.decode(changedJson { it.put("previewBackgroundLabel", 123) }) }
    }
    checkCase("image_background_legacy_files_and_reference_round_trip") {
        check(baseline.previewBackgroundImage == "none")
        check(baseline.previewBackgroundImageData.isEmpty())
        val legacy = changedJson { it.remove("previewBackgroundImage"); it.remove("previewBackgroundImageData") }
        check(LabDocument.decode(legacy) == baseline)
        check(LabDocument.decodeStored(legacy) == baseline)
        val reference = baseline.copy(previewBackgroundImage = "reference", previewBackgroundLabel = "参考图片")
        check(LabDocument.decode(reference.encode()) == reference)
        check(reference.light == baseline.light && reference.dark == baseline.dark)
        check(LabParameters.specs.size == 76 && LabParameters.colorKeys.size == 16)
        mustReject { LabDocument.decode(changedJson { it.put("previewBackgroundImage", 123) }) }
        mustReject { LabDocument.decode(changedJson { it.put("previewBackgroundImageData", 123) }) }
        mustReject { LabDocument.decode(changedJson { it.put("previewBackgroundImage", JSONObject.NULL) }) }
        mustReject { LabDocument.decode(changedJson { it.put("previewBackgroundImageData", JSONObject.NULL) }) }
    }
    checkCase("photo_background_complete_jpeg_and_eight_profiles_remain_independent") {
        val photo = baseline.copy(previewBackgroundImage = "photo", previewBackgroundImageData = jpegData,
            previewBackgroundLabel = "相册图片")
        check(photo.light == baseline.light && photo.dark == baseline.dark)
        var edited = photo
        for ((themeIndex, theme) in LabDocument.themes.withIndex()) for ((typeIndex, type) in LabParameters.types.withIndex()) {
            edited = edited.copy(selectedTheme = theme, selectedType = type)
                .withParameters(edited.copy(selectedTheme = theme, selectedType = type).current()
                    .withValue("shadowOpacity", (themeIndex * 4 + typeIndex + 1) / 10f))
            check(edited.previewBackgroundImage == "photo" && edited.previewBackgroundImageData == jpegData)
        }
        val decoded = LabDocument.decode(edited.encode())
        check(decoded == edited)
        check(Base64.getDecoder().decode(decoded.previewBackgroundImageData).contentEquals(jpegBytes))
        check(decoded.light.values.map { it["shadowOpacity"] }.toSet().size == 4)
        check(decoded.dark.values.map { it["shadowOpacity"] }.toSet().size == 4)
        check(decoded.dark.values.none { it["shadowOpacity"] in decoded.light.values.map { p -> p["shadowOpacity"] } })
        check(decoded.snapshot().previewBackgroundImageData == jpegData)
        check(JSONObject(decoded.encode()).getJSONObject("light").length() == 4)
        check(JSONObject(decoded.encode()).getJSONObject("dark").length() == 4)
    }
    checkCase("image_source_and_data_mismatches_are_rejected") {
        listOf("network", "content://album/photo", "file:///photo.jpg", "").forEach { source ->
            mustReject { baseline.copy(previewBackgroundImage = source) }
            mustReject { LabDocument.decode(changedJson { it.put("previewBackgroundImage", source) }) }
        }
        listOf("none", "reference").forEach { source ->
            mustReject { baseline.copy(previewBackgroundImage = source, previewBackgroundImageData = jpegData) }
            mustReject { LabDocument.decode(changedJson {
                it.put("previewBackgroundImage", source); it.put("previewBackgroundImageData", jpegData)
            }) }
        }
        mustReject { baseline.copy(previewBackgroundImage = "photo") }
        mustReject { LabDocument.decode(changedJson { it.put("previewBackgroundImage", "photo"); it.remove("previewBackgroundImageData") }) }
        mustReject { LabDocument.decode(changedJson { it.remove("previewBackgroundImage"); it.put("previewBackgroundImageData", jpegData) }) }
    }
    checkCase("photo_requires_canonical_base64_and_complete_jpeg_markers") {
        listOf("!not-base64!", "$jpegData\n", "data:image/jpeg;base64,$jpegData",
            Base64.getEncoder().encodeToString("not a jpeg".toByteArray()),
            Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
            Base64.getEncoder().encodeToString(jpegBytes.copyOf(jpegBytes.size - 2)),
            Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))).forEach { data ->
            mustReject { baseline.copy(previewBackgroundImage = "photo", previewBackgroundImageData = data) }
            mustReject { LabDocument.decode(changedJson {
                it.put("previewBackgroundImage", "photo"); it.put("previewBackgroundImageData", data)
            }) }
        }
    }
    checkCase("photo_byte_and_character_limits_are_enforced_separately") {
        val largest = Base64.getEncoder().encodeToString(paddedJpeg(LabDocument.MAX_IMAGE_BYTES))
        check(ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(largest))).width == 2)
        check(largest.length <= LabDocument.MAX_IMAGE_DATA_CHARACTERS)
        val accepted = baseline.copy(previewBackgroundImage = "photo", previewBackgroundImageData = largest)
        check(LabDocument.decode(accepted.encode()) == accepted)
        val tooManyBytes = Base64.getEncoder().encodeToString(paddedJpeg(LabDocument.MAX_IMAGE_BYTES + 1))
        check(tooManyBytes.length <= LabDocument.MAX_IMAGE_DATA_CHARACTERS)
        mustReject { baseline.copy(previewBackgroundImage = "photo", previewBackgroundImageData = tooManyBytes) }
        mustReject { LabDocument.decode(changedJson {
            it.put("previewBackgroundImage", "photo"); it.put("previewBackgroundImageData", tooManyBytes)
        }) }
        val tooManyCharacters = "A".repeat(LabDocument.MAX_IMAGE_DATA_CHARACTERS + 4)
        mustReject { baseline.copy(previewBackgroundImage = "photo", previewBackgroundImageData = tooManyCharacters) }
        mustReject { LabDocument.decode(changedJson {
            it.put("previewBackgroundImage", "photo"); it.put("previewBackgroundImageData", tooManyCharacters)
        }) }
    }
    checkCase("stored_complete_documents_reject_missing_damaged_groups") {
        check(LabDocument.decodeStored(encoded) == baseline)
        listOf(
            changedJson { circle(it).remove("values") },
            changedJson { circle(it).remove("colors") },
            changedJson { circle(it).getJSONObject("values").remove("shadowOpacity") },
            changedJson { circle(it).getJSONObject("colors").remove("icon") },
            changedJson { circle(it).remove("glassEnabled") },
            changedJson { it.getJSONObject("dark").remove("panel") },
        ).forEach { mustReject { LabDocument.decodeStored(it) } }
    }
    checkCase("undo_redo_branches_and_bounds") {
        val history = LabUndoHistory(baseline, maxEntries = 3)
        check(!history.update(baseline))
        for (index in 1..5) history.update(baseline.withParameters(baseline.current().withValue("blurDp", index.toFloat())))
        check(history.undoDepth == 3)
        check(history.undo()?.current()?.get("blurDp") == 4f)
        check(history.undo()?.current()?.get("blurDp") == 3f)
        check(history.undo()?.current()?.get("blurDp") == 2f)
        check(history.undo() == null)
        check(history.redo()?.current()?.get("blurDp") == 3f)
        history.update(baseline.withParameters(baseline.current().withValue("blurDp", 7f)))
        check(!history.canRedo)
        check(history.redo() == null)
        history.clear()
        check(!history.canUndo && !history.canRedo)
    }
    checkCase("slider_gestures_coalesce_and_cancel_without_losing_redo") {
        val history = LabUndoHistory(baseline)
        history.beginGesture()
        for (index in 1..40) history.update(baseline.withParameters(baseline.current().withValue("blurDp", index.toFloat())))
        check(history.canUndo && history.undoDepth == 0)
        check(history.endGesture())
        check(history.undoDepth == 1)
        check(history.undo() == baseline)
        check(history.redoDepth == 1)
        history.beginGesture()
        history.update(baseline.withParameters(baseline.current().withValue("blurDp", 8f)))
        history.cancelGesture()
        check(history.current == baseline)
        check(history.redoDepth == 1)
        check(history.redo()?.current()?.get("blurDp") == 40f)
        history.beginGesture()
        history.update(baseline.withParameters(baseline.current().withValue("blurDp", 10f)))
        check(history.undo()?.current()?.get("blurDp") == 40f)
    }
    checkCase("history_owns_mutable_input_maps") {
        val rawValues = baseline.current().values.toMutableMap()
        val rawColors = baseline.current().colors.toMutableMap()
        val rawTheme = baseline.light.toMutableMap()
        rawTheme["circle"] = baseline.current().copy(values = rawValues, colors = rawColors)
        val history = LabUndoHistory(baseline.copy(light = rawTheme))
        rawValues["blurDp"] = 40f
        rawColors["icon"] = "#FF000000"
        rawTheme["circle"] = LabParameters.defaults("accent")
        check(history.current == baseline)
        val immutable = history.current.current().values as MutableMap<String, Float>
        try { immutable["blurDp"] = 12f; error("History was mutable") } catch (_: UnsupportedOperationException) { }
    }
    val failures = (0 until results.length()).count { !results.getJSONObject(it).getBoolean("passed") }
    val report = JSONObject().put("passed", failures == 0).put("checks", results.length()).put("failures", failures)
        .put("scope", "JVM material data/JSON/history only; no renderer or Android AtomicFile device claim")
        .put("parameterCount", LabParameters.specs.size).put("colorCount", LabParameters.colorKeys.size).put("results", results)
    arguments.firstOrNull()?.let { File(it).writeText(report.toString(2), Charsets.UTF_8) }
    println(report.toString(2))
    check(failures == 0) { "$failures data contract checks failed" }
}
