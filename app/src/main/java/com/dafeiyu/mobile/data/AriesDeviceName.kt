package com.dafeiyu.mobile.data

/** Display metadata only. These rules match Aries 0.0.3's Unicode name contract. */
internal object AriesDeviceName {
    const val Capability = "device_name"

    fun normalize(raw: String): String {
        validateUnicode(raw)
        val name = trimUnicodeWhitespace(raw)
        require(name.codePointCount(0, name.length) in 1..128) {
            "Aries device_name must contain 1 to 128 Unicode code points"
        }
        return name
    }

    /** Invalid Build metadata is omitted; never truncate it or strip embedded controls. */
    fun fromBuild(manufacturer: String?, model: String?): String? = runCatching {
        fun part(raw: String?): String? {
            if (raw == null) return null
            validateUnicode(raw)
            return trimUnicodeWhitespace(raw).takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }
        }
        val brand = part(manufacturer)
        val type = part(model)
        val name = when {
            brand == null -> type
            type == null -> brand
            type.equals(brand, ignoreCase = true) -> type
            type.startsWith(brand, ignoreCase = true) && type.length > brand.length &&
                (isUnicodeWhitespace(type.codePointAt(brand.length)) || type[brand.length] in "-_") -> type
            else -> "$brand $type"
        }
        name?.let(::normalize)
    }.getOrNull()

    private fun validateUnicode(raw: String) {
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            require(!char.isLowSurrogate() && (!char.isHighSurrogate() ||
                index + 1 < raw.length && raw[index + 1].isLowSurrogate())) {
                "Aries device_name must be valid Unicode text"
            }
            val codePoint = raw.codePointAt(index)
            require(Character.getType(codePoint) != Character.CONTROL.toInt()) {
                "Aries device_name must not contain Unicode control characters"
            }
            index += Character.charCount(codePoint)
        }
    }

    private fun trimUnicodeWhitespace(raw: String): String {
        var start = 0
        var end = raw.length
        while (start < end && isUnicodeWhitespace(raw.codePointAt(start))) {
            start += Character.charCount(raw.codePointAt(start))
        }
        while (end > start && isUnicodeWhitespace(raw.codePointBefore(end))) {
            end -= Character.charCount(raw.codePointBefore(end))
        }
        return raw.substring(start, end)
    }

    private fun isUnicodeWhitespace(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
}
