package dev.brentdevs.yardhal.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.brentdevs.yardhal.MainActivity
import dev.brentdevs.yardhal.core.data.ConversationKind
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.NotificationKind
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public data class NotificationRoute(
    public val ref: ConversationRef,
    public val invite: Boolean,
    public val sender: String,
    public val eventId: String,
)

public class NotificationRouteInbox {
    private val mutablePending = MutableStateFlow<List<NotificationRoute>>(emptyList())
    public val pending: StateFlow<List<NotificationRoute>> = mutablePending.asStateFlow()

    @Synchronized
    public fun enqueue(route: NotificationRoute) {
        if (!NotificationRoutes.validRoute(route)) return
        if (mutablePending.value.none { NotificationRoutes.identity(it) == NotificationRoutes.identity(route) }) {
            mutablePending.value = mutablePending.value + route
        }
    }

    @Synchronized
    public fun consume(route: NotificationRoute) {
        val identity = NotificationRoutes.identity(route)
        mutablePending.value = mutablePending.value.filterNot { NotificationRoutes.identity(it) == identity }
    }
}

public object NotificationRoutes {
    public const val ACTION_OPEN: String = "dev.brentdevs.yardhal.OPEN_NOTIFICATION"
    private const val SCHEME = "yardhal-notification"
    private const val AUTHORITY = "open"

    public fun fromEvent(event: NotificationEvent): NotificationRoute = NotificationRoute(
        event.ref, event.kind == NotificationKind.INVITE, event.sender, event.eventId,
    )

    public fun intent(context: Context, route: NotificationRoute): Intent {
        require(validRoute(route))
        return Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            .setData(encode(route))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    public fun decode(intent: Intent?): NotificationRoute? {
        if (intent?.action != ACTION_OPEN) return null
        return decode(intent.data)
    }

    public fun encode(route: NotificationRoute): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(AUTHORITY)
        .appendPath(if (route.invite) "invite" else "conversation")
        .appendPath(route.ref.networkId)
        .appendPath(route.ref.kind.name)
        .appendPath(route.ref.rawTarget)
        .appendPath(route.ref.normalizedTarget)
        .appendPath(route.sender)
        .appendPath(route.eventId)
        .build()

    public fun decode(uri: Uri?): NotificationRoute? {
        if (uri == null || uri.scheme != SCHEME || uri.authority != AUTHORITY || uri.query != null || uri.fragment != null) return null
        val parts = uri.pathSegments
        if (parts.size != 7 || parts[0] !in setOf("conversation", "invite")) return null
        val kind = ConversationKind.entries.firstOrNull { it.name == parts[2] } ?: return null
        val route = NotificationRoute(ConversationRef(parts[1], kind, parts[3], parts[4]), parts[0] == "invite", parts[5], parts[6])
        return route.takeIf(::validRoute)
    }

    public fun validRef(ref: ConversationRef): Boolean =
        safe(ref.networkId, 512) && !ref.networkId.contains('|') &&
            safe(ref.rawTarget, 512) && safe(ref.normalizedTarget, 512) &&
            ref.rawTarget.none { it.isWhitespace() } && ref.normalizedTarget.none { it.isWhitespace() } &&
            ref.kind != ConversationKind.SERVER

    public fun validRoute(route: NotificationRoute): Boolean = validRef(route.ref) &&
        safe(route.eventId, 512) && safe(route.sender, 512) &&
        (!route.invite || route.ref.kind == ConversationKind.CHANNEL)

    public fun identity(route: NotificationRoute): String {
        val values = listOf(if (route.invite) "invite" else "conversation", route.ref.networkId,
            route.ref.kind.name, route.ref.normalizedTarget, route.eventId)
        val bytes = values.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun safe(value: String, limit: Int): Boolean = value.isNotBlank() && value.length <= limit &&
        value.none { it.code < 32 || it.code == 127 }
}
