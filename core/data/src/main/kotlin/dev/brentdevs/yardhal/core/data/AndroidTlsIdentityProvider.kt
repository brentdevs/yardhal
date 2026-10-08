package dev.brentdevs.yardhal.core.data

import android.content.Context
import android.security.KeyChain
import android.security.KeyChainException
import dev.brentdevs.yardhal.core.client.TlsClientIdentity
import dev.brentdevs.yardhal.core.client.TlsIdentityUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

public class AndroidTlsIdentityProvider(context: Context) {
    private val context = context.applicationContext

    public fun resolve(alias: String): TlsClientIdentity = runBlocking(Dispatchers.IO) {
        if (alias.isBlank()) {
            throw TlsIdentityUnavailableException("No client certificate identity was selected. Choose an identity in network settings.")
        }
        try {
            val key = KeyChain.getPrivateKey(context, alias)
                ?: throw TlsIdentityUnavailableException("The selected client identity is missing or inaccessible. Select it again in network settings.")
            val chain = KeyChain.getCertificateChain(context, alias)
                ?: throw TlsIdentityUnavailableException("The selected client identity has no accessible certificate chain. Select another identity.")
            TlsClientIdentity(key, chain)
        } catch (failure: KeyChainException) {
            throw TlsIdentityUnavailableException("Android could not access the selected client identity. Unlock credential storage or select another identity.")
        } catch (failure: SecurityException) {
            throw TlsIdentityUnavailableException("Access to the selected client identity was revoked. Select it again in network settings.")
        } catch (failure: IllegalStateException) {
            throw TlsIdentityUnavailableException("Android credential storage is unavailable. Unlock the device and try again.")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TlsIdentityUnavailableException("Client identity lookup was interrupted. Try connecting again.")
        }
    }
}
