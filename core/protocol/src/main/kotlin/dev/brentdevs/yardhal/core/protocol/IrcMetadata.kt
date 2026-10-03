package dev.brentdevs.yardhal.core.protocol

public data class MetadataCapability(
    public val maxSubs: Int? = null,
    public val maxKeys: Int? = null,
    public val maxValueBytes: Int? = null,
    public val beforeConnect: Boolean = false,
) {
    public fun allowsValue(value: String): Boolean {
        val limit = maxValueBytes ?: return true
        return value.toByteArray(Charsets.UTF_8).size <= limit
    }

    public companion object {
        public fun parse(value: String?): MetadataCapability {
            if (value.isNullOrEmpty()) return MetadataCapability()
            var parsed = MetadataCapability()
            for (token in value.split(',')) {
                val key = token.substringBefore('=')
                val number = token.substringAfter('=', "").toIntOrNull()?.takeIf { it >= 0 }
                parsed = when (key) {
                    "before-connect" -> parsed.copy(beforeConnect = true)
                    "max-subs" -> parsed.copy(maxSubs = number)
                    "max-keys" -> parsed.copy(maxKeys = number)
                    "max-value-bytes" -> parsed.copy(maxValueBytes = number)
                    else -> parsed
                }
            }
            return parsed
        }
    }
}

public object IrcMetadata {
    public const val CAPABILITY: String = "draft/metadata-2"
    public const val COMMAND: String = "METADATA"
    public const val BATCH_TYPE: String = "metadata"
    public const val SUBS_BATCH_TYPE: String = "metadata-subs"
    public const val KEY_AVATAR: String = "avatar"
    public const val KEY_DISPLAY_NAME: String = "display-name"

    public const val RPL_WHOISKEYVALUE: Int = 760
    public const val RPL_KEYVALUE: Int = 761
    public const val RPL_KEYNOTSET: Int = 766
    public const val RPL_METADATASUBOK: Int = 770
    public const val RPL_METADATAUNSUBOK: Int = 771
    public const val RPL_METADATASUBS: Int = 772
    public const val RPL_METADATASYNCLATER: Int = 774

    public fun isValidKey(key: String): Boolean =
        key.isNotEmpty() && key.all { it in 'a'..'z' || it in '0'..'9' || it in "_./-" }

    public fun setCommand(key: String, value: String?): String =
        if (value == null) "$COMMAND * SET $key" else "$COMMAND * SET $key :$value"
}
