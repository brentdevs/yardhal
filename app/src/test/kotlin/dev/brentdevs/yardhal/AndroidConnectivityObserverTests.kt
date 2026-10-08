package dev.brentdevs.yardhal

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import dev.brentdevs.yardhal.coordinator.ConnectionRecoveryPolicy
import dev.brentdevs.yardhal.coordinator.RecoveryAction
import dev.brentdevs.yardhal.coordinator.RecoveryPhase
import dev.brentdevs.yardhal.coordinator.RecoverySignal
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidConnectivityObserverTests {
    @Test
    fun unvalidatedWifiAllowsManualConnectionWithoutInternetProbeSuccess() {
        val policy = ConnectionRecoveryPolicy(autoConnect = false, available = false)
        observe { callback, publications ->
            val network = ShadowNetwork.newInstance(101)
            val capabilities = internetCapabilities()
            callback.onAvailable(network)
            callback.onCapabilitiesChanged(network, capabilities)

            assertFalse(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            assertEquals(true to network.networkHandle, publications.last())
            policy.handle(RecoverySignal.NETWORK_AVAILABLE)
            assertEquals(RecoveryAction.START, policy.handle(RecoverySignal.MANUAL_CONNECT))
            assertEquals(RecoveryPhase.CONNECTING, policy.phase)

            val publicationCount = publications.size
            shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            callback.onCapabilitiesChanged(network, capabilities)
            shadowOf(capabilities).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            callback.onCapabilitiesChanged(network, capabilities)
            assertEquals(publicationCount, publications.size)
        }
    }

    @Test
    fun blockedSuspendedAndLostRoutesPauseUntilUsableAgain() {
        observe { callback, publications ->
            val network = ShadowNetwork.newInstance(102)
            val capabilities = internetCapabilities()
            callback.onAvailable(network)
            callback.onCapabilitiesChanged(network, capabilities)
            assertTrue(publications.last().first)

            callback.onBlockedStatusChanged(network, true)
            assertFalse(publications.last().first)
            callback.onCapabilitiesChanged(network, capabilities)
            assertFalse(publications.last().first)
            callback.onBlockedStatusChanged(network, false)
            assertTrue(publications.last().first)

            shadowOf(capabilities).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            callback.onCapabilitiesChanged(network, capabilities)
            assertFalse(publications.last().first)
            shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            callback.onCapabilitiesChanged(network, capabilities)
            assertTrue(publications.last().first)

            shadowOf(capabilities).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            callback.onCapabilitiesChanged(network, capabilities)
            assertFalse(publications.last().first)
            callback.onLost(network)
            assertEquals(false to null, publications.last())
        }
    }

    @Test
    fun disconnectedDeviceAndUnknownRouteDoNotStartOptimisticRetries() {
        val policy = ConnectionRecoveryPolicy(available = false)
        observe { callback, publications ->
            assertEquals(listOf<Pair<Boolean, Long?>>(false to null), publications)
            assertEquals(RecoveryAction.PAUSE, policy.handle(RecoverySignal.START))
            assertEquals(RecoveryAction.PAUSE, policy.handle(RecoverySignal.MANUAL_CONNECT))
            assertEquals(RecoveryAction.NONE, policy.handle(RecoverySignal.RESUME))

            val network = ShadowNetwork.newInstance(103)
            callback.onAvailable(network)
            assertEquals(listOf<Pair<Boolean, Long?>>(false to null), publications)
            assertEquals(RecoveryPhase.OFFLINE, policy.phase)
            callback.onCapabilitiesChanged(network, internetCapabilities())
            assertTrue(publications.last().first)
            assertEquals(RecoveryAction.NUDGE, policy.handle(RecoverySignal.NETWORK_AVAILABLE))
            assertEquals(RecoveryPhase.CONNECTING, policy.phase)
        }
    }

    private fun internetCapabilities(): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        val shadow = shadowOf(it)
        shadow.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun observe(block: (ConnectivityManager.NetworkCallback, MutableList<Pair<Boolean, Long?>>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
        val shadow = shadowOf(manager)
        shadow.setDefaultNetworkActive(false)
        val publications = mutableListOf<Pair<Boolean, Long?>>()
        AndroidConnectivityObserver(context) { available, handle -> publications += available to handle }.use { observer ->
            observer.start()
            block(shadow.networkCallbacks.single(), publications)
        }
        assertTrue(shadow.networkCallbacks.isEmpty())
    }
}
