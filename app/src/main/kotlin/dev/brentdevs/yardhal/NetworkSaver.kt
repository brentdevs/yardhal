package dev.brentdevs.yardhal

import dev.brentdevs.yardhal.coordinator.LiveCoordinator
import dev.brentdevs.yardhal.core.data.CredentialVault
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.ui.screens.NetworkDraft
import java.util.UUID

public class NetworkSaver(
    private val coordinator: LiveCoordinator,
    private val vault: CredentialVault,
) {
    public fun save(draft: NetworkDraft): Boolean {
        val existing = draft.networkId?.let { coordinator.networkStore.byId(it) ?: return false }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val previousPasswordRef = existing?.saslPasswordRef
        val replacementPassword = draft.saslPassword.takeUnless { draft.clearSaslPassword }
        val passwordRef = when {
            draft.clearSaslPassword -> null
            replacementPassword != null -> "sasl-$id-${UUID.randomUUID()}"
            else -> previousPasswordRef
        }
        val authcid = draft.saslAuthcid ?: passwordRef?.let { draft.nick }
        val config = existing?.copy(
            name = draft.displayName,
            host = draft.host,
            port = draft.port,
            tls = draft.tls,
            nick = draft.nick,
            autojoin = draft.autojoin,
            saslAuthcid = authcid,
            saslPasswordRef = passwordRef,
            saslPassword = null,
        ) ?: NetworkConfig(
            id = id,
            name = draft.displayName,
            host = draft.host,
            port = draft.port,
            tls = draft.tls,
            nick = draft.nick,
            autojoin = draft.autojoin,
            saslAuthcid = authcid,
            saslPasswordRef = passwordRef,
        )
        if (replacementPassword != null && passwordRef != null) {
            vault.storePassword(passwordRef, replacementPassword)
        }
        if (existing == null) {
            if (!coordinator.networkStore.add(config)) {
                if (replacementPassword != null && passwordRef != null) vault.deletePassword(passwordRef)
                return false
            }
            coordinator.connect(config)
        } else if (!coordinator.updateNetwork(config, credentialsChanged = replacementPassword != null)) {
            if (replacementPassword != null && passwordRef != null) vault.deletePassword(passwordRef)
            return false
        }
        if (previousPasswordRef != null && previousPasswordRef != passwordRef &&
            previousPasswordRef != config.serverPasswordRef
        ) {
            vault.deletePassword(previousPasswordRef)
        }
        return true
    }
}
