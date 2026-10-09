package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.client.FilehostException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class UploadSettingsStoreTests {
    @get:Rule val temporary = TemporaryFolder()
    private val vault = InMemoryCredentialVault()
    private fun store(): UploadSettingsStore = UploadSettingsStore(temporary.root, vault)
    private val advertised = NegotiatedFilehost("https://advertised.example/upload", true, "irc-user", "irc-secret")

    @Test
    fun networkSelectionOverridesGlobalAndConfiguredProviderNeverInheritsIrcCredentials() {
        val store = store()
        val global = UploadProvider(label = "Global", endpointUrl = "https://global.example/upload")
        val local = UploadProvider(label = "Network", endpointUrl = "https://network.example/upload", networkId = "a")
        store.saveProvider(global)
        store.saveProvider(local)
        store.selectProvider(null, global.id)
        store.selectProvider("a", local.id)
        assertEquals(local.id, store.resolve("a", advertised).id)
        assertEquals(global.id, store.resolve("b", advertised).id)
        assertNull(store.resolve("a", advertised).authentication)
        store.selectProvider("a", UploadSettingsStore.NEGOTIATED_PROVIDER)
        assertEquals("irc-secret", assertNotNull(store.resolve("a", advertised).authentication).password)
        assertFailsWith<IllegalArgumentException> { store.selectProvider("b", local.id) }
    }

    @Test
    fun explicitAdvertisedSelectionFailsWithoutAdvertisementInsteadOfFallingBack() {
        val store = store()
        val global = UploadProvider(label = "Global", endpointUrl = "https://global.example/upload")
        store.saveProvider(global)
        store.selectProvider(null, global.id)
        store.selectProvider("a", UploadSettingsStore.NEGOTIATED_PROVIDER)
        assertFailsWith<IllegalStateException> { store.resolve("a", null) }
    }

    @Test
    fun removingASelectedProviderRequiresExplicitReselectionInsteadOfFallingBack() {
        val store = store()
        val provider = UploadProvider(label = "Chosen", endpointUrl = "https://chosen.example/upload")
        store.saveProvider(provider)
        store.selectProvider(null, provider.id)
        store.removeProvider(provider.id)
        assertFailsWith<IllegalStateException> { store.resolve("a", advertised) }
        store.selectProvider(null, null)
        assertEquals("negotiated:a", store.resolve("a", advertised).id)
    }

    @Test
    fun providerUsernameAndPasswordOnlyLiveInVaultAndDiagnosticsAreSecretSafe() {
        val store = store()
        val provider = UploadProvider(label = "Private", endpointUrl = "https://private.example/upload")
        store.saveProvider(provider, UploadAuthentication("protected-account", "protected-secret"), replaceAuthentication = true)
        store.selectProvider(null, provider.id)
        val text = File(temporary.root, "upload-settings.json").readText()
        assertFalse(text.contains("protected-account"))
        assertFalse(text.contains("protected-secret"))
        assertEquals("protected-secret", assertNotNull(store().resolve("a", null).authentication).password)
        assertFalse(advertised.toString().contains("irc-secret"))
        assertFalse(store.resolve("a", null).toString().contains("protected-secret"))
        val ref = assertNotNull(store.snapshot().providers.single().credentialRef)
        store.removeProvider(provider.id)
        assertNull(vault.readPassword("$ref:password"))
        assertNull(vault.readPassword("$ref:user"))
    }

    @Test
    fun consentSurvivesRestartAndChangesWithOriginOrAuthenticationReference() {
        val store = store()
        val provider = UploadProvider(label = "Provider", endpointUrl = "https://one.example/upload")
        store.saveProvider(provider)
        store.selectProvider(null, provider.id)
        store.grantConsent(store.resolve("a", null))
        assertTrue(store().hasConsent(store().resolve("a", null)))
        store.saveProvider(provider.copy(endpointUrl = "https://two.example/upload"))
        assertFalse(store.hasConsent(store.resolve("a", null)))
        store.grantConsent(store.resolve("a", null))
        store.saveProvider(provider.copy(endpointUrl = "https://two.example/upload"), UploadAuthentication("a", "b"), true)
        assertFalse(store.hasConsent(store.resolve("a", null)))
    }

    @Test
    fun configuredEndpointHonorsSecureTransportEvenWithoutFilehostAdvertisement() {
        val store = store()
        val provider = UploadProvider(label = "HTTP", endpointUrl = "http://example.com/upload")
        store.saveProvider(provider)
        store.selectProvider(null, provider.id)
        assertFailsWith<FilehostException.InsecureTransport> { store.resolve("a", null, requireSecureTransport = true) }
        assertFailsWith<FilehostException.InsecureTransport> { store.saveProvider(provider, UploadAuthentication("a", "b"), true) }
    }

    @Test
    fun sizeLimitAndMetadataPolicyPersistAndInvalidLimitsAreRejected() {
        val store = store()
        assertEquals(PhotoMetadataPolicy.STRIP, store.snapshot().photoMetadataPolicy)
        store.updateLimits(123, PhotoMetadataPolicy.KEEP)
        assertEquals(123L, store().snapshot().maximumBytes)
        assertEquals(PhotoMetadataPolicy.KEEP, store().snapshot().photoMetadataPolicy)
        assertFailsWith<IllegalArgumentException> { store.updateLimits(0, PhotoMetadataPolicy.STRIP) }
        assertFailsWith<IllegalArgumentException> { store.updateLimits(UploadSettingsStore.MAXIMUM_LIMIT_BYTES + 1, PhotoMetadataPolicy.STRIP) }
    }
}
