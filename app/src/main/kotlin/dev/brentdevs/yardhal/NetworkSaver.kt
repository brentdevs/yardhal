package dev.brentdevs.yardhal

import dev.brentdevs.yardhal.coordinator.LiveCoordinator
import dev.brentdevs.yardhal.core.data.CredentialVault
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.SocksProxyConfig
import dev.brentdevs.yardhal.ui.screens.NetworkDraft
import java.util.UUID

public class NetworkSaver(
    private val coordinator: LiveCoordinator,
    private val vault: CredentialVault,
) {
    @Synchronized
    public fun save(draft: NetworkDraft): Boolean {
        val existing = draft.networkId?.let { coordinator.networkStore.byId(it) ?: return false }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val staged = linkedMapOf<String, String>()

        fun passwordReference(role: String, previous: String?, replacement: String?, clear: Boolean): String? {
            if (clear) return null
            if (replacement == null) return previous
            val reference = "$role-$id-${UUID.randomUUID()}"
            staged[reference] = replacement
            return reference
        }

        val config = try {
            val passwordRef = passwordReference(
                "sasl", existing?.saslPasswordRef, draft.saslPassword, draft.clearSaslPassword,
            )
            val proxy = if (draft.proxyEnabled == false) {
                null
            } else if (draft.proxyEnabled == true || existing?.proxy != null) {
                SocksProxyConfig(
                    host = draft.proxyHost ?: existing?.proxy?.host ?: "",
                    port = draft.proxyPort ?: existing?.proxy?.port ?: 1080,
                    username = if (draft.proxyUsername != null) {
                        draft.proxyUsername.takeIf(String::isNotEmpty)
                    } else {
                        existing?.proxy?.username
                    },
                    passwordRef = passwordReference(
                        "proxy", existing?.proxy?.passwordRef, draft.proxyPassword, draft.clearProxyPassword,
                    ),
                )
            } else {
                null
            }
            val base = existing ?: NetworkConfig(
                id = id,
                name = draft.displayName,
                host = draft.host,
                port = draft.port,
                tls = draft.tls,
                nick = draft.nick,
            )
            base.copy(
                name = draft.displayName,
                host = draft.host,
                port = draft.port,
                tls = draft.tls,
                nick = draft.nick,
                realName = draft.realName ?: base.realName,
                alternateNicks = draft.alternateNicks ?: base.alternateNicks,
                autoConnect = draft.autoConnect ?: base.autoConnect,
                autojoin = draft.autojoin,
                saslAuthcid = draft.saslAuthcid ?: passwordRef?.let { draft.nick },
                saslMode = draft.saslMode ?: base.saslMode,
                saslPasswordRef = passwordRef,
                serverPasswordRef = passwordReference(
                    "server", base.serverPasswordRef, draft.serverPassword, draft.clearServerPassword,
                ),
                nickServAccount = if (draft.nickServAccount != null) {
                    draft.nickServAccount.takeIf(String::isNotEmpty)
                } else {
                    base.nickServAccount
                },
                nickServPasswordRef = passwordReference(
                    "nickserv", base.nickServPasswordRef, draft.nickServPassword, draft.clearNickServPassword,
                ),
                nickServService = draft.nickServService ?: base.nickServService,
                waitForNickServ = draft.waitForNickServ ?: base.waitForNickServ,
                proxy = proxy,
                tlsClientAlias = when {
                    draft.clearTlsClientAlias -> null
                    draft.tlsClientAlias != null -> draft.tlsClientAlias
                    else -> base.tlsClientAlias
                },
                saslPassword = null,
                serverPassword = null,
                nickServPassword = null,
                proxyPassword = null,
                tlsClientIdentity = null,
            )
        } catch (_: IllegalArgumentException) {
            return false
        }

        val attempted = mutableListOf<String>()
        val accepted = try {
            for ((reference, password) in staged) {
                attempted.add(reference)
                vault.storePassword(reference, password)
            }
            if (existing == null) {
                coordinator.networkStore.add(config)
            } else {
                coordinator.updateNetwork(config, credentialsChanged = staged.isNotEmpty())
            }
        } catch (_: Exception) {
            false
        }
        if (!accepted) {
            for (reference in attempted) {
                runCatching { coordinator.networkStore.deleteUnreferencedPasswords(listOf(reference), vault) }
            }
            return false
        }
        val previousReferences = existing?.let {
            listOfNotNull(it.saslPasswordRef, it.serverPasswordRef, it.nickServPasswordRef, it.proxy?.passwordRef)
        }.orEmpty()
        for (reference in previousReferences.distinct()) {
            runCatching { coordinator.networkStore.deleteUnreferencedPasswords(listOf(reference), vault) }
        }
        if (existing == null) coordinator.connect(config)
        return true
    }
}
