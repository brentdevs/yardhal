package dev.brentdevs.yardhal.core.client

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.WeakHashMap
import javax.net.ssl.KeyManager
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

public class TlsClientIdentity(
    public val privateKey: PrivateKey,
    certificates: Array<X509Certificate>,
) {
    private val chain = certificates.copyOf()
    public val certificates: Array<X509Certificate> get() = chain.copyOf()

    init {
        val leaf = chain.firstOrNull()
            ?: throw TlsIdentityUnavailableException("The selected client identity has no certificate chain. Select another identity.")
        try {
            leaf.checkValidity()
            if (!leaf.publicKey.algorithm.equals(privateKey.algorithm, ignoreCase = true)) {
                throw TlsIdentityUnavailableException("The selected client identity has an incompatible private key.")
            }
            if (leaf.keyUsage?.firstOrNull() == false) {
                throw TlsIdentityUnavailableException("The selected client certificate cannot sign a TLS handshake.")
            }
            val usage = leaf.extendedKeyUsage
            if (usage != null && "1.3.6.1.5.5.7.3.2" !in usage && "2.5.29.37.0" !in usage) {
                throw TlsIdentityUnavailableException("The selected certificate does not permit client authentication.")
            }
            val signingAlgorithm = when (privateKey.algorithm.uppercase()) {
                "RSA" -> "SHA256withRSA"
                "EC" -> "SHA256withECDSA"
                "DSA" -> "SHA256withDSA"
                "ED25519" -> "Ed25519"
                "ED448" -> "Ed448"
                "EDDSA" -> "EdDSA"
                else -> throw TlsIdentityUnavailableException("The selected client identity uses an unsupported key algorithm.")
            }
            val challenge = "Yardhal TLS client identity".toByteArray(Charsets.US_ASCII)
            val signer = Signature.getInstance(signingAlgorithm)
            signer.initSign(privateKey)
            signer.update(challenge)
            val proof = signer.sign()
            val verifier = Signature.getInstance(signingAlgorithm)
            verifier.initVerify(leaf.publicKey)
            verifier.update(challenge)
            if (!verifier.verify(proof)) {
                throw TlsIdentityUnavailableException("The selected client certificate does not match its private key.")
            }
        } catch (failure: CertificateException) {
            throw TlsIdentityUnavailableException("The selected client certificate is expired, not yet valid, or unreadable. Select another identity.")
        } catch (failure: GeneralSecurityException) {
            throw TlsIdentityUnavailableException("The selected client private key cannot be used for TLS authentication. Unlock the device or select another identity.")
        }
    }

    override fun toString(): String = "TlsClientIdentity(certificates=${chain.size})"
}

public data class CertificateDetails(
    val sha256: String,
    val subject: String,
    val issuer: String,
    val notBeforeMs: Long,
    val notAfterMs: Long,
    val subjectAltNames: List<String>,
)

public data class CertificateInspection(
    val host: String,
    val port: Int,
    val certificates: List<CertificateDetails>,
)

public class CertificateRejectedException(
    public val inspection: CertificateInspection,
    cause: CertificateException = CertificateException("Server certificate verification failed."),
) : CertificateException("The TLS certificate for ${inspection.host}:${inspection.port} was rejected.", cause)

public class TlsIdentityUnavailableException(message: String) : IOException(message)

public fun createTlsSocketFactory(
    host: String,
    port: Int,
    identity: TlsClientIdentity? = null,
    pinnedFingerprintSha256: String? = null,
): SSLSocketFactory {
    require(host.isNotBlank())
    require(port in 1..65535)
    require(pinnedFingerprintSha256 == null || (pinnedFingerprintSha256.length == 64 &&
        pinnedFingerprintSha256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }))
    val trust = EndpointTrustManager(host, port, pinnedFingerprintSha256, platformTrustManager(null))
    val keys: Array<KeyManager>? = identity?.let { arrayOf(ClientIdentityKeyManager(it)) }
    val context = SSLContext.getInstance("TLS")
    context.init(keys, arrayOf(trust), null)
    return EndpointSocketFactory(host, port, context.socketFactory, trust)
}

private fun platformTrustManager(store: KeyStore?): X509ExtendedTrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(store)
    return factory.trustManagers.firstOrNull { it is X509ExtendedTrustManager } as? X509ExtendedTrustManager
        ?: throw IOException("The TLS provider cannot securely verify server endpoint identities.")
}

private fun fingerprint(certificate: X509Certificate): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
    val digits = "0123456789abcdef"
    return buildString(64) {
        for (byte in digest) {
            val value = byte.toInt() and 255
            append(digits[value ushr 4])
            append(digits[value and 15])
        }
    }
}

private fun inspect(host: String, port: Int, chain: Array<X509Certificate>): CertificateInspection =
    CertificateInspection(host, port, chain.map { certificate ->
        CertificateDetails(
            sha256 = fingerprint(certificate),
            subject = certificate.subjectX500Principal.name,
            issuer = certificate.issuerX500Principal.name,
            notBeforeMs = certificate.notBefore.time,
            notAfterMs = certificate.notAfter.time,
            subjectAltNames = certificate.subjectAlternativeNames.orEmpty().mapNotNull { name ->
                val type = name.firstOrNull() as? Int
                val value = name.getOrNull(1) as? String
                when (type) {
                    1 -> value?.let { "Email:$it" }
                    2 -> value?.let { "DNS:$it" }
                    6 -> value?.let { "URI:$it" }
                    7 -> value?.let { "IP:$it" }
                    8 -> value?.let { "RegisteredID:$it" }
                    else -> null
                }
            },
        )
    })

private class EndpointTrustManager(
    private val host: String,
    private val port: Int,
    private val pin: String?,
    private val platform: X509ExtendedTrustManager,
) : X509ExtendedTrustManager() {
    private var layeredSocketPorts: WeakHashMap<Socket, Int>? = null

    fun bindLayeredSocket(socket: Socket, transportPort: Int) {
        if (transportPort == port) return
        synchronized(this) {
            val ports = layeredSocketPorts ?: WeakHashMap<Socket, Int>().also { layeredSocketPorts = it }
            ports[socket] = transportPort
        }
    }

    private fun verify(
        chain: Array<X509Certificate>,
        peerHost: String?,
        peerPort: Int?,
        endpointIdentificationAlgorithm: String?,
        layeredTransportPort: Int? = null,
        check: (X509ExtendedTrustManager) -> Unit,
    ) {
        try {
            if (peerHost == null || !host.equals(peerHost, ignoreCase = true) ||
                peerPort == null || (peerPort != port && peerPort != layeredTransportPort)) {
                throw CertificateException("TLS verification endpoint does not match the configured endpoint.")
            }
            if (endpointIdentificationAlgorithm != "HTTPS") {
                throw CertificateException("Secure TLS endpoint identification is required.")
            }
            val leaf = chain.firstOrNull() ?: throw CertificateException("The server supplied no certificate.")
            if (pin == null) {
                check(platform)
            } else {
                if (!pin.equals(fingerprint(leaf), ignoreCase = true)) {
                    throw CertificateException("The server certificate has changed since it was explicitly trusted.")
                }
                leaf.checkValidity()
                val store = KeyStore.getInstance(KeyStore.getDefaultType())
                store.load(null, null)
                store.setCertificateEntry("explicit-leaf", leaf)
                check(platformTrustManager(store))
            }
        } catch (failure: CertificateException) {
            throw CertificateRejectedException(inspect(host, port, chain), failure)
        }
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        val tls = socket as? SSLSocket
        val session = tls?.handshakeSession
        val transportPort = if (tls == null) null else synchronized(this) { layeredSocketPorts?.get(tls) }
        verify(chain, session?.peerHost, session?.peerPort, tls?.sslParameters?.endpointIdentificationAlgorithm, transportPort) {
            it.checkServerTrusted(chain, authType, socket)
        }
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
        verify(chain, engine?.peerHost, engine?.peerPort, engine?.sslParameters?.endpointIdentificationAlgorithm) {
            it.checkServerTrusted(chain, authType, engine)
        }
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        verify(chain, null, null, null) { it.checkServerTrusted(chain, authType) }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        platform.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        platform.checkClientTrusted(chain, authType, socket)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        platform.checkClientTrusted(chain, authType, engine)

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
}

private class ClientIdentityKeyManager(identity: TlsClientIdentity) : X509ExtendedKeyManager() {
    private val key = identity.privateKey
    private val chain = identity.certificates
    private val alias = "configured-client-identity"

    private fun matches(keyType: String, issuers: Array<out Principal>?): Boolean {
        val requested = keyType.substringBefore('_')
        return requested.equals(chain.first().publicKey.algorithm, ignoreCase = true) &&
            (issuers.isNullOrEmpty() || chain.any { certificate ->
                issuers.any { it == certificate.issuerX500Principal }
            })
    }

    override fun getClientAliases(keyType: String, issuers: Array<out Principal>?): Array<String>? =
        if (matches(keyType, issuers)) arrayOf(alias) else null

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        if (keyType.orEmpty().any { matches(it, issuers) }) alias else null

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        if (keyType.orEmpty().any { matches(it, issuers) }) alias else null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == this.alias) chain.copyOf() else null

    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == this.alias) key else null
    override fun getServerAliases(keyType: String, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun chooseEngineServerAlias(keyType: String, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null
}

private class EndpointSocketFactory(
    private val host: String,
    private val port: Int,
    private val delegate: SSLSocketFactory,
    private val trust: EndpointTrustManager,
) : SSLSocketFactory() {
    private fun configure(socket: Socket): Socket {
        val tls = socket as SSLSocket
        val parameters = tls.sslParameters
        parameters.endpointIdentificationAlgorithm = "HTTPS"
        if (':' !in host && !host.matches(Regex("[0-9.]+"))) {
            parameters.serverNames = listOf(SNIHostName(host))
        }
        tls.sslParameters = parameters
        return tls
    }

    private fun endpoint(host: String, port: Int) {
        if (!this.host.equals(host, ignoreCase = true) || this.port != port) {
            throw IOException("The TLS socket factory is scoped to a different server endpoint.")
        }
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(): Socket = configure(delegate.createSocket())

    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        endpoint(host, port)
        val tls = configure(delegate.createSocket(socket, host, port, autoClose))
        trust.bindLayeredSocket(tls, socket.port)
        return tls
    }

    override fun createSocket(host: String, port: Int): Socket {
        endpoint(host, port)
        return configure(delegate.createSocket(host, port))
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        endpoint(host, port)
        return configure(delegate.createSocket(host, port, localHost, localPort))
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        endpoint(host.hostAddress, port)
        return configure(delegate.createSocket(host, port))
    }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        endpoint(address.hostAddress, port)
        return configure(delegate.createSocket(address, port, localAddress, localPort))
    }
}
