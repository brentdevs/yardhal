package dev.brentdevs.yardhal.core.client

public data class StsPolicy(
    public val port: Int,
    public val expiresAtEpochSeconds: Long,
    public val durationSeconds: Long = 0,
) {
    public fun isExpired(nowEpochSeconds: Long): Boolean = nowEpochSeconds >= expiresAtEpochSeconds
}

public interface StsPolicyStore {
    public fun load(host: String): StsPolicy?
    public fun save(host: String, policy: StsPolicy)
    public fun delete(host: String)
}

public class InMemoryStsPolicyStore : StsPolicyStore {
    private val policies = LinkedHashMap<String, StsPolicy>()

    override fun load(host: String): StsPolicy? {
        synchronized(policies) { return policies[normalize(host)] }
    }

    override fun save(host: String, policy: StsPolicy) {
        synchronized(policies) { policies[normalize(host)] = policy }
    }

    override fun delete(host: String) {
        synchronized(policies) { policies.remove(normalize(host)) }
    }

    private fun normalize(host: String): String = host.lowercase()
}

public sealed interface StsUpgradeDecision {
    public data object ConnectAsConfigured : StsUpgradeDecision
    public data class UpgradeRequired(val port: Int) : StsUpgradeDecision
}

public object StsResolver {

    public const val CAP_NAME: String = "sts"

    public fun parseUpgradePort(value: String): Int? =
        tokenValue(value, "port")?.toIntOrNull()?.takeIf { it in 1..65535 }

    public fun parseCapValue(value: String, nowEpochSeconds: Long, currentPort: Int? = null): StsPolicy? {
        val duration = tokenValue(value, "duration")?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val port = if (tokenValue(value, "port") != null) {
            parseUpgradePort(value) ?: return null
        } else {
            currentPort ?: return null
        }
        val expiry = nowEpochSeconds + duration.coerceAtMost(Long.MAX_VALUE - nowEpochSeconds)
        return StsPolicy(port = port, expiresAtEpochSeconds = expiry, durationSeconds = duration)
    }

    private fun tokenValue(value: String, key: String): String? = value.split(',')
        .firstOrNull { it.substringBefore('=') == key && '=' in it }
        ?.substringAfter('=')

    public fun decide(
        store: StsPolicyStore,
        host: String,
        requestedPort: Int,
        tlsRequested: Boolean,
        nowEpochSeconds: Long,
    ): StsUpgradeDecision {
        val policy = store.load(host) ?: return StsUpgradeDecision.ConnectAsConfigured
        if (policy.isExpired(nowEpochSeconds)) {
            store.delete(host)
            return StsUpgradeDecision.ConnectAsConfigured
        }
        return when {
            !tlsRequested || requestedPort != policy.port -> StsUpgradeDecision.UpgradeRequired(policy.port)
            else -> StsUpgradeDecision.ConnectAsConfigured
        }
    }

    public fun refreshOnDisconnect(store: StsPolicyStore, host: String, nowEpochSeconds: Long) {
        val policy = store.load(host) ?: return
        if (policy.durationSeconds <= 0) return
        store.save(host, policy.copy(expiresAtEpochSeconds = nowEpochSeconds + policy.durationSeconds))
    }
}
