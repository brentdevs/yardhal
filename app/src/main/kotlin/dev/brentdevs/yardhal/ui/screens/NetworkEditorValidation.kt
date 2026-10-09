package dev.brentdevs.yardhal.ui.screens

import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkMode
import dev.brentdevs.yardhal.core.data.SaslMode

private val alternateNicknameSeparators = Regex("[,\\p{javaWhitespace}\\p{Z}]+")

internal fun parseAlternateNicknames(value: String): List<String> =
    value.splitToSequence(alternateNicknameSeparators)
        .filter(String::isNotEmpty)
        .toList()

internal fun suggestedTlsIdentityName(networkId: String?, draftId: String): String =
    "Yardhal IRC ${networkId ?: draftId}"

internal fun networkEditorErrors(draft: NetworkDraft, saved: NetworkConfig?): List<String> = buildList {
    addAll(bouncerSetupErrors(draft, saved))
    if (draft.host.isBlank()) add("Enter the IRC server host.")
    if (draft.port !in 1..65535) add("Enter an IRC server port between 1 and 65535.")
    if (draft.nick.isBlank() || draft.nick.any { it.isWhitespace() || it == '\u0000' }) {
        add("Enter a nickname without whitespace or NUL characters.")
    }
    if (draft.alternateNicks.orEmpty().any { nick ->
            nick.isBlank() || nick.any { it.isWhitespace() || it == '\u0000' }
        }
    ) {
        add("Separate alternate nicknames with commas or whitespace; nicknames cannot contain NUL characters.")
    }
    if (draft.realName?.any { it in "\r\n\u0000" } == true) {
        add("Real name cannot contain line breaks or NUL characters.")
    }
    val saslMode = if ((draft.mode ?: saved?.mode) == NetworkMode.ZNC) SaslMode.AUTO
        else draft.saslMode ?: saved?.saslMode ?: SaslMode.AUTO
    if (saslMode == SaslMode.EXTERNAL &&
        (!draft.tls || draft.clearTlsClientAlias || draft.tlsClientAlias.isNullOrBlank())
    ) {
        add("Choose a TLS client identity and enable TLS to use SASL EXTERNAL.")
    }
    if ((saslMode == SaslMode.PLAIN || saslMode == SaslMode.SCRAM_SHA_256) &&
        !hasPassword(draft.saslPassword, saved?.saslPasswordRef, draft.clearSaslPassword)
    ) {
        add("Enter a SASL password or keep the saved password to use ${saslMode.name.replace('_', '-')}.")
    }
    if ((draft.mode ?: saved?.mode ?: NetworkMode.DIRECT) == NetworkMode.DIRECT && draft.nickServService.isNullOrBlank()) {
        add("Enter the NickServ service nickname.")
    }
    if (draft.proxyEnabled == true) {
        if (draft.proxyHost.isNullOrBlank()) add("Enter the SOCKS5 proxy host.")
        if (draft.proxyPort == null || draft.proxyPort !in 1..65535) {
            add("Enter a SOCKS5 proxy port between 1 and 65535.")
        }
        val hasUsername = !draft.proxyUsername.isNullOrEmpty()
        val hasPassword = hasPassword(draft.proxyPassword, saved?.proxy?.passwordRef, draft.clearProxyPassword)
        if (hasUsername != hasPassword) {
            add("SOCKS5 authentication needs both a username and password. Enter both, or remove the username and clear the saved password.")
        }
    }
}

internal fun bouncerSetupErrors(draft: NetworkDraft, saved: NetworkConfig?): List<String> = buildList {
    when (draft.mode ?: saved?.mode ?: NetworkMode.DIRECT) {
        NetworkMode.DIRECT -> Unit
        NetworkMode.SOJU -> {
            if ((draft.saslMode ?: saved?.saslMode ?: SaslMode.AUTO) != SaslMode.EXTERNAL) {
                if ((draft.saslAuthcid ?: saved?.saslAuthcid).isNullOrBlank()) add("Enter your soju account.")
                if (!hasPassword(draft.saslPassword, saved?.saslPasswordRef, draft.clearSaslPassword)) {
                    add("Enter a soju account password or retain the saved one.")
                }
            }
        }
        NetworkMode.ZNC -> {
            val account = draft.saslAuthcid ?: saved?.saslAuthcid.orEmpty()
            if (account.isBlank() || account.any { it.isWhitespace() || it in "/:\u0000" }) {
                add("Enter a ZNC account without whitespace, slash, colon or NUL.")
            }
            if (draft.zncNetwork.orEmpty().any { it.isWhitespace() || it in "/:\u0000" }) {
                add("ZNC network names cannot contain whitespace, slash, colon or NUL.")
            }
            if (!hasPassword(draft.serverPassword, saved?.serverPasswordRef, draft.clearServerPassword)) {
                add("Enter a ZNC password or retain the saved one.")
            }
        }
    }
}

private fun hasPassword(replacement: String?, reference: String?, clear: Boolean): Boolean =
    !clear && (!replacement.isNullOrEmpty() || !reference.isNullOrBlank())
