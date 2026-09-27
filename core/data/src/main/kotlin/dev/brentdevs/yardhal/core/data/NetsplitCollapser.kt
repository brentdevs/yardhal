package dev.brentdevs.yardhal.core.data

public class NetsplitCollapser {

    private val open: MutableMap<String, Entry> = LinkedHashMap()

    public data class Entry(
        public val type: String,
        public var count: Int = 0,
        public val servers: List<String>,
    )

    public fun onStart(batchId: String, type: String, servers: List<String>): Boolean {
        if (type != NETSPLIT && type != NETJOIN) return false
        open[batchId] = Entry(type = type, servers = servers)
        return true
    }

    public fun isSuppressing(command: String): Boolean {
        val wanted = when (command.uppercase()) {
            "QUIT" -> NETSPLIT
            "JOIN" -> NETJOIN
            else -> return false
        }
        return open.values.any { it.type == wanted }
    }

    public fun recordSuppressed(command: String) {
        val wanted = when (command.uppercase()) {
            "QUIT" -> NETSPLIT
            else -> NETJOIN
        }
        open.values.firstOrNull { it.type == wanted }?.let { it.count += 1 }
    }

    public fun onEnd(batchId: String): Summary? {
        val entry = open.remove(batchId) ?: return null
        return Summary(
            isSplit = entry.type == NETSPLIT,
            count = entry.count,
            servers = entry.servers,
        )
    }

    public data class Summary(public val isSplit: Boolean, public val count: Int, public val servers: List<String>) {
        override fun toString(): String {
            val verb = if (isSplit) "quit during netsplit" else "returned from netsplit"
            val arrow = if (isSplit) "⇅" else "⇜"
            val who = "$count users $verb"
            return if (servers.isEmpty()) "$arrow $who" else "$arrow $who (${servers.joinToString(" ↔ ")})"
        }
    }

    public companion object {
        public const val NETSPLIT: String = "netsplit"
        public const val NETJOIN: String = "netjoin"
    }
}
