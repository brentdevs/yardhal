package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.client.UploadUrlPolicy
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
public data class UploadedAttachment(
    public val url: String,
    public val name: String,
    public val mimeType: String,
    public val sizeBytes: Long,
)

public class NegotiatedFilehost(
    public val endpointUrl: String,
    public val ircConnectionIsTls: Boolean,
    public val saslUser: String? = null,
    public val saslPassword: String? = null,
) {
    override fun toString(): String = "NegotiatedFilehost(secure=$ircConnectionIsTls, authenticated=${saslUser != null && saslPassword != null})"
}
public data class UploadEnvironment(
    public val negotiatedFilehost: NegotiatedFilehost?,
    public val requireSecureTransport: Boolean,
)


@Serializable
public enum class PhotoMetadataPolicy { STRIP, KEEP }

@Serializable
public data class UploadProvider(
    public val id: String = UUID.randomUUID().toString(),
    public val label: String,
    public val endpointUrl: String,
    public val networkId: String? = null,
    public val credentialRef: String? = null,
) {
    override fun toString(): String = "UploadProvider(id=$id, networkId=$networkId, authenticated=${credentialRef != null})"
}

@Serializable
public data class UploadPreferences(
    public val providers: List<UploadProvider> = emptyList(),
    public val globalProviderId: String? = null,
    public val networkProviderIds: Map<String, String> = emptyMap(),
    public val maximumBytes: Long = 25L * 1024 * 1024,
    public val photoMetadataPolicy: PhotoMetadataPolicy = PhotoMetadataPolicy.STRIP,
    public val consentKeys: Set<String> = emptySet(),
)

public class UploadAuthentication(public val username: String, public val password: String) {
    override fun toString(): String = "UploadAuthentication([protected])"
}

public class ResolvedUploadProvider(
    public val id: String,
    public val label: String,
    public val endpointUrl: String,
    public val requireSecureTransport: Boolean,
    public val authentication: UploadAuthentication?,
    public val authenticationDisclosure: String,
    public val consentKey: String,
) {
    public val origin: String get() = UploadUrlPolicy.origin(endpointUrl)
    override fun toString(): String = "ResolvedUploadProvider(id=$id, authenticated=${authentication != null})"
}

public class UploadSettingsStore(directory: File, private val vault: CredentialVault) {
    private val store = JsonFileStore(File(directory, "upload-settings.json"), UploadPreferences.serializer())
    private var state = store.loadOrDefault(UploadPreferences())

    @Synchronized
    public fun snapshot(): UploadPreferences = state

    @Synchronized
    public fun updateLimits(maximumBytes: Long, policy: PhotoMetadataPolicy) {
        require(maximumBytes in 1..MAXIMUM_LIMIT_BYTES) { "Choose a size limit between 1 byte and 2 GiB" }
        persist(state.copy(maximumBytes = maximumBytes, photoMetadataPolicy = policy))
    }

    @Synchronized
    public fun saveProvider(provider: UploadProvider, authentication: UploadAuthentication? = null, replaceAuthentication: Boolean = false): UploadProvider {
        require(provider.id.isNotBlank() && provider.label.isNotBlank()) { "Provider name is required" }
        val previous = state.providers.firstOrNull { it.id == provider.id }
        val reference = if (replaceAuthentication) authentication?.let { "upload:${UUID.randomUUID()}" }
            else previous?.credentialRef
        UploadUrlPolicy.validate(provider.endpointUrl, requireSecure = reference != null)
        if (replaceAuthentication && authentication != null) {
            require(':' !in authentication.username && authentication.username.none { it.isISOControl() }) { "Invalid authentication username" }
        }
        if (reference != null && replaceAuthentication && authentication != null) {
            vault.storePassword("$reference:user", authentication.username)
            try {
                vault.storePassword("$reference:password", authentication.password)
            } catch (failure: Exception) {
                vault.deletePassword("$reference:user")
                throw failure
            }
        }
        val saved = provider.copy(credentialRef = reference)
        try {
            persist(state.copy(providers = state.providers.filterNot { it.id == saved.id } + saved))
        } catch (failure: Exception) {
            if (replaceAuthentication && reference != null) deleteCredentials(reference)
            throw failure
        }
        if (previous?.credentialRef != reference) previous?.credentialRef?.let(::deleteCredentials)
        return saved
    }

    @Synchronized
    public fun removeProvider(id: String) {
        val removed = state.providers.firstOrNull { it.id == id } ?: return
        persist(state.copy(providers = state.providers.filterNot { it.id == id }))
        removed.credentialRef?.let(::deleteCredentials)
    }

    @Synchronized
    public fun selectProvider(networkId: String?, providerId: String?) {
        if (providerId != null && providerId != NEGOTIATED_PROVIDER) {
            val provider = state.providers.firstOrNull { it.id == providerId } ?: error("Upload provider was removed")
            require(provider.networkId == null || provider.networkId == networkId) { "Provider belongs to a different network" }
        }
        persist(if (networkId == null) state.copy(globalProviderId = providerId) else state.copy(
            networkProviderIds = if (providerId == null) state.networkProviderIds - networkId else state.networkProviderIds + (networkId to providerId),
        ))
    }
    @Synchronized
    public fun resolve(networkId: String, negotiated: NegotiatedFilehost?, requireSecureTransport: Boolean = negotiated?.ircConnectionIsTls == true): ResolvedUploadProvider {
        val chosen = state.networkProviderIds[networkId] ?: state.globalProviderId
        if (chosen == null || chosen == NEGOTIATED_PROVIDER) {
            val advertised = negotiated ?: throw IllegalStateException("No advertised FILEHOST is available. Configure or select an upload provider.")
            UploadUrlPolicy.validate(advertised.endpointUrl, requireSecureTransport || advertised.ircConnectionIsTls)
            val authentication = if (advertised.saslUser != null && advertised.saslPassword != null) {
                UploadAuthentication(advertised.saslUser, advertised.saslPassword)
            } else null
            if (authentication != null) UploadUrlPolicy.validate(advertised.endpointUrl, true)
            val id = "negotiated:$networkId"
            return ResolvedUploadProvider(id, "Advertised FILEHOST", advertised.endpointUrl, requireSecureTransport || advertised.ircConnectionIsTls,
                authentication, if (authentication == null) "No credentials" else "Basic authentication using this IRC connection's effective credentials",
                consentKey(id, advertised.endpointUrl, if (authentication == null) "none" else "connection-basic"))
        }
        val provider = state.providers.firstOrNull { it.id == chosen && (it.networkId == null || it.networkId == networkId) }
            ?: throw IllegalStateException("The selected upload provider is unavailable. Select another provider in upload settings.")
        val requireSecure = requireSecureTransport
        UploadUrlPolicy.validate(provider.endpointUrl, requireSecure)
        val authentication = provider.credentialRef?.let { ref ->
            val user = vault.readPassword("$ref:user") ?: throw IllegalStateException("Provider credentials are unavailable. Update upload settings.")
            val password = vault.readPassword("$ref:password") ?: throw IllegalStateException("Provider credentials are unavailable. Update upload settings.")
            UploadAuthentication(user, password)
        }
        if (authentication != null) UploadUrlPolicy.validate(provider.endpointUrl, true)
        return ResolvedUploadProvider(provider.id, provider.label, provider.endpointUrl, requireSecure, authentication,
            if (authentication == null) "No credentials; IRC credentials are not forwarded" else "Basic authentication using this provider's protected credentials; IRC credentials are not forwarded",
            consentKey(provider.id, provider.endpointUrl, provider.credentialRef ?: "none"))
    }

    @Synchronized
    public fun hasConsent(provider: ResolvedUploadProvider): Boolean = provider.consentKey in state.consentKeys

    @Synchronized
    public fun grantConsent(provider: ResolvedUploadProvider) { persist(state.copy(consentKeys = state.consentKeys + provider.consentKey)) }

    private fun consentKey(id: String, endpoint: String, auth: String): String = "$id|${UploadUrlPolicy.origin(endpoint)}|$auth"
    private fun deleteCredentials(reference: String) {
        vault.deletePassword("$reference:user")
        vault.deletePassword("$reference:password")
    }
    private fun persist(next: UploadPreferences) { store.save(next); state = next }

    public companion object {
        public const val NEGOTIATED_PROVIDER: String = "@negotiated"
        public const val MAXIMUM_LIMIT_BYTES: Long = 2L * 1024 * 1024 * 1024
    }
}
