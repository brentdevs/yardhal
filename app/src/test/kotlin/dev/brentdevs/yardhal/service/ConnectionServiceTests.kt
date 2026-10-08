package dev.brentdevs.yardhal.service

import android.app.Notification
import android.app.Application
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConnectionServiceTests {
    @Test
    fun serviceIsForegroundBeforeAStartCommandCanBeCancelledByImmediateRejection() {
        val controller = Robolectric.buildService(ConnectionService::class.java).create()
        try {
            val service = controller.get()
            val notification = requireNotNull(shadowOf(service).lastForegroundNotification)
            assertTrue(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun rejectionStopIsDeliveredAfterForegroundPromotionInsteadOfCancellingAPendingStart() {
        val controller = Robolectric.buildService(ConnectionService::class.java).create()
        try {
            val service = controller.get()
            ConnectionService.stop(service)
            val stopCommand = requireNotNull(shadowOf(service.application).nextStartedService)
            service.onStartCommand(stopCommand, 0, 1)
            assertTrue(shadowOf(service).isStoppedBySelf)
            val notification = requireNotNull(shadowOf(service).lastForegroundNotification)
            assertTrue(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun stickyRestartWithoutAnEligibleNetworkStopsTheEmptyForegroundService() {
        val controller = Robolectric.buildService(ConnectionService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(null, 0, 1)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }
}
