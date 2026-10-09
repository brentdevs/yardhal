package dev.brentdevs.yardhal

import android.app.Application
import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.brentdevs.yardhal.service.ConnectionService
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ApplicationStartupTests {
    @Test
    fun blockedLocalInitializationDoesNotDelayForegroundPromotionAndFailureStopsStickyService() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val diskThread = AtomicReference<Thread>()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
            override fun getFilesDir(): File {
                diskThread.set(Thread.currentThread())
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                throw IOException("Fixture storage unavailable")
            }
        }
        val app = Instrumentation.newApplication(YardhalApplication::class.java, context) as YardhalApplication
        val controller = Robolectric.buildService(ConnectionService::class.java).create()
        try {
            app.onCreate()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNotEquals(Looper.getMainLooper().thread, diskThread.get())
            assertEquals(ApplicationStartup.LOADING, app.startup.value)
            val service = controller.get()
            ReflectionHelpers.setField(service, "mApplication", app)
            service.onStartCommand(null, 0, 1)
            assertNotNull(shadowOf(service).lastForegroundNotification)
            assertFalse(shadowOf(service).isStoppedBySelf)
            release.countDown()
            runBlocking {
                withTimeout(5_000) {
                    app.startup.first { it == ApplicationStartup.FAILED }
                    assertFalse(app.awaitInitialization())
                    while (!shadowOf(service).isStoppedBySelf) {
                        shadowOf(Looper.getMainLooper()).idle()
                        delay(10)
                    }
                }
            }
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            release.countDown()
            controller.destroy()
            app.onTerminate()
        }
    }
}
