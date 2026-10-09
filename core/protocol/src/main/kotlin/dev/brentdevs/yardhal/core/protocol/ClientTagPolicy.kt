package dev.brentdevs.yardhal.core.protocol

public class ClientTagPolicy(
    private val capabilities: Set<String>,
    denyValue: String? = null,
) {
    private val rules = denyValue.orEmpty().split(',').filter { it.isNotEmpty() }

    public fun allows(tag: String): Boolean {
        if (!tag.startsWith('+')) return true
        if ("message-tags" !in capabilities) return false
        val name = tag.drop(1)
        val catchAll = rules.firstOrNull() == "*"
        return if (catchAll) "-$name" in rules else name !in rules
    }

    public fun select(vararg alternatives: String): String? = alternatives.firstOrNull(::allows)

    public val reply: String? get() = select("+reply", "+draft/reply")
    public val react: String? get() = select("+draft/react")
    public val unreact: String? get() = select("+draft/unreact")
    public val refs: String? get() = select("+draft/refs", "+draft/msgids")
    public val reactionReference: String? get() = reply ?: refs
    public val typing: String? get() = select("+typing", "+draft/typing")
    public val attachment: String? get() = select("+draft/attachment")
    public val reactionsAvailable: Boolean get() = react != null && unreact != null && reactionReference != null
}
