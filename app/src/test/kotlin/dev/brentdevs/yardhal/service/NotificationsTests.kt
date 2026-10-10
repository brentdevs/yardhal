package dev.brentdevs.yardhal.service

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.NotificationKind
import dev.brentdevs.yardhal.core.data.NotificationKindPreferences
import dev.brentdevs.yardhal.core.data.NotificationPreferences
import dev.brentdevs.yardhal.core.data.NotificationPriority
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationsTests {
    private lateinit var app: Application
    private lateinit var manager: NotificationManager
    private val event = NotificationEvent(NotificationKind.MENTION, ConversationRef.channel("soju-upstream-1", "#Yardhal/日本"), "Network", "Alice", "hello", 100, "msgid-1")

    @Before
    fun prepare() {
        app = RuntimeEnvironment.getApplication()
        manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun changingSoundOrPrioritySelectsNewImmutableChannelsAndPreservesForegroundChannel() {
        Notifications.ensureChannels(app)
        val loud = NotificationKindPreferences()
        val original = Notifications.ensureEventChannel(app, NotificationKind.MENTION, loud)
        val silent = Notifications.ensureEventChannel(app, NotificationKind.MENTION, loud.copy(sound = false))
        val quiet = Notifications.ensureEventChannel(app, NotificationKind.MENTION, loud.copy(priority = NotificationPriority.QUIET))
        val normal = Notifications.ensureEventChannel(app, NotificationKind.MENTION, loud.copy(priority = NotificationPriority.DEFAULT))
        assertNotEquals(original.id, silent.id)
        assertNotEquals(original.id, quiet.id)
        assertNotEquals(original.id, normal.id)
        assertNotNull(original.sound)
        assertNull(silent.sound)
        assertNull(quiet.sound)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, original.importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, quiet.importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, normal.importance)
        assertEquals(original.id, Notifications.ensureEventChannel(app, NotificationKind.MENTION, loud).id)
        assertNotNull(manager.getNotificationChannel(original.id).sound)
        assertEquals(NotificationManager.IMPORTANCE_MIN, manager.getNotificationChannel(Notifications.CHANNEL_ONGOING).importance)
        assertNotEquals(original.id, Notifications.ensureEventChannel(app, NotificationKind.DIRECT_MESSAGE, loud).id)
        val settings = Notifications.settingsIntent(app, NotificationKind.MENTION, NotificationPreferences(mentions = loud.copy(sound = false)))
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, settings.action)
        assertEquals(silent.id, settings.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(app.packageName, settings.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun existingUserBlockedChannelIsNotRecreatedToBypassAndroidChoice() {
        val id = Notifications.channelId(NotificationKind.MENTION, NotificationKindPreferences())
        manager.createNotificationChannel(NotificationChannel(id, "User blocked", NotificationManager.IMPORTANCE_NONE))
        assertFalse(Notifications.post(app, event, NotificationEligibility(), NotificationPreferences()))
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(id).importance)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun runtimePermissionAndEligibilityPreventNativePostingWithoutDisturbingConnection() {
        Notifications.ensureChannels(app)
        manager.notify(Notifications.ONGOING_NOTIFICATION_ID, Notifications.ongoing(app, 1))
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(Notifications.post(app, event, NotificationEligibility(), NotificationPreferences()))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        for (eligibility in listOf(NotificationEligibility(muted = true), NotificationEligibility(viewed = true), NotificationEligibility(read = true), NotificationEligibility(playback = true), NotificationEligibility(ignored = true))) {
            assertFalse(Notifications.post(app, event, eligibility, NotificationPreferences()))
        }
        assertEquals(listOf(Notifications.ONGOING_NOTIFICATION_ID), manager.activeNotifications.map { it.id })
        assertTrue(Notifications.post(app, event, NotificationEligibility(), NotificationPreferences(showAvatars = false)))
        assertEquals(2, manager.activeNotifications.size)
        Notifications.cancelConversation(app, event.ref)
        assertEquals(listOf(Notifications.ONGOING_NOTIFICATION_ID), manager.activeNotifications.map { it.id })
    }

    @Test
    fun distinctImmutablePendingIntentsKeepExactConversationInviteNetworkAndMessageIdentity() {
        val routes = listOf(
            NotificationRoutes.fromEvent(event),
            NotificationRoutes.fromEvent(event.copy(eventId = "msgid-2")),
            NotificationRoutes.fromEvent(event.copy(ref = ConversationRef.channel("soju-upstream-2", event.ref.rawTarget))),
            NotificationRoutes.fromEvent(event.copy(ref = ConversationRef.channel(event.ref.networkId, "#Other"))),
            NotificationRoutes.fromEvent(event.copy(kind = NotificationKind.INVITE)),
        )
        val intents = routes.map { Notifications.pendingIntent(app, it) }
        assertEquals(routes.size, intents.distinct().size)
        for ((route, pendingIntent) in routes.zip(intents)) {
            assertTrue(pendingIntent.isImmutable)
            pendingIntent.send()
            val launched = requireNotNull(shadowOf(app).nextStartedActivity)
            assertEquals(route, NotificationRoutes.decode(launched))
            assertEquals("dev.brentdevs.yardhal.MainActivity", launched.component?.className)
        }
        assertEquals(intents.first(), Notifications.pendingIntent(app, routes.first()))
    }

    @Test
    fun routeInboxRetainsColdLaunchUntilExplicitConsumptionAndDoesNotAutojoinInvites() {
        val inbox = NotificationRouteInbox()
        val conversation = NotificationRoutes.fromEvent(event)
        val invite = NotificationRoutes.fromEvent(event.copy(kind = NotificationKind.INVITE, eventId = "invite-1"))
        inbox.enqueue(conversation)
        inbox.enqueue(conversation)
        inbox.enqueue(invite)
        assertEquals(listOf(conversation, invite), inbox.pending.value)
        val restored = NotificationRouteInbox()
        inbox.pending.value.map(NotificationRoutes::encode).map { requireNotNull(NotificationRoutes.decode(it)) }.forEach(restored::enqueue)
        inbox.consume(conversation)
        assertEquals(listOf(invite), inbox.pending.value)
        restored.consume(conversation)
        assertEquals(listOf(invite), restored.pending.value)
        assertTrue(restored.pending.value.single().invite)
    }

    @Test
    fun malformedAndForeignRoutesCannotProduceNavigationOrCommands() {
        assertNull(NotificationRoutes.decode(Intent(Intent.ACTION_SEND).setData(NotificationRoutes.encode(NotificationRoutes.fromEvent(event)))))
        assertNull(NotificationRoutes.decode(Uri.parse("yardhal-notification://foreign/invite")))
        assertNull(NotificationRoutes.decode(NotificationRoutes.encode(NotificationRoutes.fromEvent(event)).buildUpon().appendQueryParameter("join", "now").build()))
        assertNull(NotificationRoutes.decode(NotificationRoutes.encode(NotificationRoutes.fromEvent(event)).buildUpon().fragment("join").build()))
        assertNull(NotificationRoutes.decode(NotificationRoutes.encode(NotificationRoute(event.ref.copy(rawTarget = "#bad\r\nJOIN #evil"), true, "Alice", "invite"))))
        assertNull(NotificationRoutes.decode(NotificationRoutes.encode(NotificationRoute(ConversationRef.directMessage("n", "Alice"), true, "Alice", "invite"))))
        assertNull(NotificationRoutes.decode(Uri.parse("yardhal-notification://open/invite/n/CHANNEL/%23x/%23x/Alice")))
    }

    @Test
    fun notificationPreviewAndAvatarPreferencesControlActualPresentationWhileTapRetainsDestination() {
        assertTrue(Notifications.post(app, event, NotificationEligibility(), NotificationPreferences(showPreview = false, showAvatars = false)))
        val notification = manager.activeNotifications.single().notification
        val presentation = requireNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification))
        assertEquals("New mentions", presentation.messages.single().text.toString())
        assertEquals("Alice", presentation.messages.single().person?.name.toString())
        assertEquals("#Yardhal/日本 · Network", presentation.conversationTitle.toString())
        assertNull(notification.getLargeIcon())
        requireNotNull(notification.contentIntent).send()
        assertEquals(event.ref, requireNotNull(NotificationRoutes.decode(shadowOf(app).nextStartedActivity)).ref)
        assertTrue(Notifications.post(app, event.copy(eventId = "avatar"), NotificationEligibility(), NotificationPreferences()))
        assertNotNull(manager.activeNotifications.first { it.tag == NotificationRoutes.identity(NotificationRoutes.fromEvent(event.copy(eventId = "avatar"))) }.notification.getLargeIcon())
    }
}
