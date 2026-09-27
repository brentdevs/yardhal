package dev.brentdevs.yardhal.core.data

public data class WhoisInfo(
    public val nick: String,
    public val user: String? = null,
    public val host: String? = null,
    public val realName: String? = null,
    public val server: String? = null,
    public val serverInfo: String? = null,
    public val account: String? = null,
    public val awayMessage: String? = null,
    public val isOper: Boolean = false,
    public val idleSeconds: Long? = null,
    public val signOnEpochSeconds: Long? = null,
    public val channels: List<String> = emptyList(),
)

public class WhoisAccumulator {

    private var info: WhoisInfo? = null

    public fun reset() {
        info = null
    }

    public fun handle(numeric: Int, params: List<String>): WhoisInfo? {
        val target = params.getOrNull(1) ?: return null
        val base = info ?: WhoisInfo(nick = target).also { info = it }
        if (base.nick != target && numeric != 318) return null

        info = when (numeric) {
            311 -> base.copy(
                user = params.getOrNull(2),
                host = params.getOrNull(3),
                realName = params.getOrNull(5),
            )
            312 -> base.copy(server = params.getOrNull(2), serverInfo = params.getOrNull(3))
            313 -> base.copy(isOper = true)
            317 -> base.copy(
                idleSeconds = params.getOrNull(2)?.toLongOrNull(),
                signOnEpochSeconds = params.getOrNull(3)?.toLongOrNull(),
            )
            319 -> base.copy(
                channels = params.getOrNull(2)?.split(' ').orEmpty().filter { it.isNotEmpty() },
            )
            330 -> base.copy(account = params.getOrNull(2))
            301 -> base.copy(awayMessage = params.getOrNull(2))
            else -> base
        }

        if (numeric == 318) {
            val complete = info
            reset()
            return complete
        }
        return null
    }
}
