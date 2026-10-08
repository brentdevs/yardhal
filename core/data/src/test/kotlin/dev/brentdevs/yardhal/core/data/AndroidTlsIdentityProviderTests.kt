package dev.brentdevs.yardhal.core.data

import android.content.Context
import android.os.Looper
import android.security.KeyChain
import android.security.KeyChainException
import androidx.test.core.app.ApplicationProvider
import dev.brentdevs.yardhal.core.client.TlsIdentityUnavailableException
import java.security.PrivateKey
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [AndroidTlsIdentityProviderTests.MissingIdentityKeyChainShadow::class])
class AndroidTlsIdentityProviderTests {
    private lateinit var provider: AndroidTlsIdentityProvider

    @Before
    fun setup() {
        MissingIdentityKeyChainShadow.lookupOnWorker = false
        MissingIdentityKeyChainShadow.lookupCount = 0
        MissingIdentityKeyChainShadow.lookupFailure = false
        provider = AndroidTlsIdentityProvider(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun missingAliasFailsClosedWithStructuredErrorAndResolvesOffTheMainThread() {
        val failure = assertFailsWith<TlsIdentityUnavailableException> { provider.resolve("missing-alias") }
        assertTrue(assertNotNull(failure.message).contains("missing or inaccessible"))
        assertTrue(MissingIdentityKeyChainShadow.lookupOnWorker)
        assertEquals(1, MissingIdentityKeyChainShadow.lookupCount)
        assertEquals(null, failure.cause)
    }

    @Test
    fun blankAliasFailsBeforeConsultingAndroidCredentialStorage() {
        val failure = assertFailsWith<TlsIdentityUnavailableException> { provider.resolve(" ") }
        assertTrue(assertNotNull(failure.message).contains("No client certificate identity"))
        assertEquals(0, MissingIdentityKeyChainShadow.lookupCount)
    }

    @Test
    fun credentialStorageFailureDoesNotExposePrivateKeyDetailsThroughItsCauseOrMessage() {
        MissingIdentityKeyChainShadow.lookupFailure = true
        val failure = assertFailsWith<TlsIdentityUnavailableException> { provider.resolve("missing-alias") }
        assertTrue(assertNotNull(failure.message).contains("Unlock credential storage"))
        assertFalse(failure.toString().contains("PRIVATE KEY"))
        assertEquals(null, failure.cause)
        assertTrue(MissingIdentityKeyChainShadow.lookupOnWorker)
    }

    @Implements(KeyChain::class)
    class MissingIdentityKeyChainShadow {
        companion object {
            @Volatile var lookupOnWorker: Boolean = false
            @Volatile var lookupCount: Int = 0
            @Volatile var lookupFailure: Boolean = false

            @JvmStatic
            @Implementation
            fun getPrivateKey(context: Context, alias: String): PrivateKey? {
                require(alias == "missing-alias")
                require(context == context.applicationContext)
                lookupOnWorker = Thread.currentThread() != Looper.getMainLooper().thread
                lookupCount += 1
                if (lookupFailure) throw KeyChainException("PRIVATE KEY material must not enter an error report")
                return null
            }
        }
    }
}
