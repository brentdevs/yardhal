package dev.brentdevs.yardhal.ui.screens

import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkMode
import dev.brentdevs.yardhal.core.data.SaslMode
import dev.brentdevs.yardhal.core.data.SocksProxyConfig
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import org.junit.Test

class NetworkEditorValidationTests {
    private val draft = NetworkDraft(
        host = "irc.example.org",
        port = 6697,
        tls = true,
        nick = "sailor",
        saslPassword = null,
        autojoin = emptyList(),
        displayName = "Example",
        realName = "Sailor",
        alternateNicks = emptyList(),
        saslMode = SaslMode.AUTO,
        nickServService = "NickServ",
        proxyEnabled = false,
    )
    private val saved = NetworkConfig(
        id = "example", name = "Example", host = "irc.example.org", nick = "sailor",
    )

    @Test
    fun commaAndWhitespaceSeparatedAlternatesProduceValidConnectionCandidates() {
        val alternates = parseAlternateNicknames(" sailor_,sailor2\tsailor3\nsailor4  , sailor5\u2003sailor6 ")
        assertEquals(listOf("sailor_", "sailor2", "sailor3", "sailor4", "sailor5", "sailor6"), alternates)
        assertTrue(networkEditorErrors(draft.copy(alternateNicks = alternates), null).isEmpty())
    }

    @Test
    fun identityImportSuggestionsAreIsolatedByNetworkOrUnsavedDraft() {
        assertNotEquals(suggestedTlsIdentityName("network-a", "draft"), suggestedTlsIdentityName("network-b", "draft"))
        assertNotEquals(suggestedTlsIdentityName(null, "draft-a"), suggestedTlsIdentityName(null, "draft-b"))
        assertEquals(suggestedTlsIdentityName("network-a", "old-editor"), suggestedTlsIdentityName("network-a", "new-editor"))
    }

    @Test
    fun invalidAlternateTokensAreRejectedBeforeSaving() {
        for (nickname in listOf("bad\u0000nick", "bad nick", "bad\tnick")) {
            val errors = networkEditorErrors(draft.copy(alternateNicks = listOf(nickname)), null)
            assertTrue(errors.any { it.contains("alternate nicknames") })
        }
    }

    @Test
    fun realNameAllowsSpacesButRejectsWireLineSeparatorsAndNul() {
        assertTrue(networkEditorErrors(draft.copy(realName = "A real name"), null).isEmpty())
        for (character in "\r\n\u0000") {
            val errors = networkEditorErrors(draft.copy(realName = "Name${character}USER injected"), null)
            assertTrue(errors.any { it.startsWith("Real name") })
        }
    }

    @Test
    fun explicitPasswordMechanismsRequireNewOrRetainedCredentials() {
        for (mode in listOf(SaslMode.PLAIN, SaslMode.SCRAM_SHA_256)) {
            val explicit = draft.copy(saslMode = mode)
            assertTrue(networkEditorErrors(explicit, null).any { it.startsWith("Enter a SASL password") })
            assertTrue(networkEditorErrors(explicit.copy(saslPassword = "new password"), null).isEmpty())
            assertTrue(networkEditorErrors(explicit, saved.copy(saslPasswordRef = "saved-secret")).isEmpty())
            assertTrue(networkEditorErrors(explicit, saved.copy(saslPasswordRef = " ")).isNotEmpty())
            assertTrue(networkEditorErrors(
                explicit.copy(clearSaslPassword = true), saved.copy(saslPasswordRef = "saved-secret"),
            ).isNotEmpty())
        }
        assertTrue(networkEditorErrors(draft, null).isEmpty())
    }

    @Test
    fun proxyCredentialsMustRemainPairedWhenSavedPasswordsAreKeptOrCleared() {
        val proxy = draft.copy(proxyEnabled = true, proxyHost = "proxy.example.org", proxyPort = 1080, proxyUsername = "user")
        val withSavedProxy = saved.copy(proxy = SocksProxyConfig("proxy.example.org", username = "user", passwordRef = "proxy-secret"))
        assertTrue(networkEditorErrors(proxy, null).any { it.startsWith("SOCKS5 authentication") })
        assertTrue(networkEditorErrors(proxy.copy(proxyPassword = "new password"), null).isEmpty())
        assertTrue(networkEditorErrors(proxy, withSavedProxy).isEmpty())
        assertTrue(networkEditorErrors(proxy.copy(clearProxyPassword = true), withSavedProxy).isNotEmpty())
        assertTrue(networkEditorErrors(proxy.copy(proxyUsername = ""), withSavedProxy).isNotEmpty())
        assertTrue(networkEditorErrors(proxy.copy(proxyUsername = "", clearProxyPassword = true), withSavedProxy).isEmpty())
        assertTrue(networkEditorErrors(proxy.copy(proxyUsername = "", proxyPassword = "orphan"), null).isNotEmpty())
        assertTrue(networkEditorErrors(proxy.copy(proxyEnabled = false), null).isEmpty())
    }

    @Test
    fun sojuRequiresItsAccountCredentialsUnlessUsingExternalIdentity() {
        val soju = draft.copy(mode = NetworkMode.SOJU, saslAuthcid = "bouncer-user")
        assertTrue(bouncerSetupErrors(soju, null).isNotEmpty())
        assertEquals(emptyList(), bouncerSetupErrors(soju.copy(saslPassword = "account secret"), null))
        assertTrue(bouncerSetupErrors(soju.copy(saslAuthcid = "", saslPassword = "secret"), null).isNotEmpty())
        val retained = saved.copy(mode = NetworkMode.SOJU, saslAuthcid = "bouncer-user", saslPasswordRef = "vault-key")
        assertEquals(emptyList(), bouncerSetupErrors(soju, retained))
        assertTrue(bouncerSetupErrors(soju.copy(clearSaslPassword = true), retained).isNotEmpty())
        assertEquals(emptyList(), networkEditorErrors(soju.copy(
            saslMode = SaslMode.EXTERNAL, tlsClientAlias = "keychain-identity",
        ), null))
        assertTrue(networkEditorErrors(soju.copy(saslMode = SaslMode.EXTERNAL, tls = false), null).isNotEmpty())
    }

    @Test
    fun zncCredentialRulesProtectPassAccountAndNetworkSeparators() {
        val znc = draft.copy(mode = NetworkMode.ZNC, saslAuthcid = "account", serverPassword = "secret: with / punctuation")
        assertEquals(emptyList(), bouncerSetupErrors(znc, null))
        assertEquals(emptyList(), bouncerSetupErrors(znc.copy(zncNetwork = "network"), null))
        for (separator in listOf(" ", "/", ":", "\u0000", "\r", "\n")) {
            assertTrue(bouncerSetupErrors(znc.copy(saslAuthcid = "account${separator}other"), null).isNotEmpty())
            assertTrue(bouncerSetupErrors(znc.copy(zncNetwork = "network${separator}other"), null).isNotEmpty())
        }
        assertTrue(bouncerSetupErrors(znc.copy(serverPassword = null), null).isNotEmpty())
        val retained = saved.copy(mode = NetworkMode.ZNC, saslAuthcid = "account", serverPasswordRef = "vault-key")
        assertEquals(emptyList(), bouncerSetupErrors(znc.copy(serverPassword = null), retained))
        assertTrue(bouncerSetupErrors(znc.copy(serverPassword = null, clearServerPassword = true), retained).isNotEmpty())
    }
}
