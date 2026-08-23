package dev.brentdevs.yardhal.core.client

public data class StsPolicy(
    public val port: Int,
    public val expiresAtEpochSeconds: Long,
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
    public data class BlockedByPolicy(val policy: StsPolicy) : StsUpgradeDecision
}

public object StsResolver {

    public const val CAP_NAME: String = "sts"

    public fun parseCapValue(value: String, nowEpochSeconds: Long): StsPolicy? {
        var port: Int? = null
        var durationSeconds: Long? = null
        for (token in value.split(',')) {
            if (token.isEmpty()) continue
            val separator = token.indexOf('=')
            if (separator <= 0) continue
            val key = token.substring(0, separator)
            val rawValue = token.substring(separator + 1)
            when (key) {
                "port" -> port = rawValue.toIntOrNull()?.takeIf { it in 1..65535 }
                "duration" -> durationSeconds = rawValue.toLongOrNull()?.takeIf { it > 0 }
            }
        }
        val resolvedPort = port ?: return null
        val duration = durationSeconds ?: return null
        return StsPolicy(port = resolvedPort, expiresAtEpochSeconds = nowEpochSeconds + duration)
    }

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
            !tlsRequested -> StsUpgradeDecision.BlockedByPolicy(policy)
            requestedPort != policy.port -> StsUpgradeDecision.UpgradeRequired(policy.port)
            else -> StsUpgradeDecision.ConnectAsConfigured
        }
    }
}
