package dev.brentdevs.yardhal.ui.components

import dev.brentdevs.yardhal.coordinator.ChatMessage
import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.TimestampFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageAccessibilityTests {
    @Test
    fun summaryPreservesRelayQuoteAttachmentAndUnconfirmedContextWithoutIrcControls() {
        val message = message().copy(
            sender = "bridge",
            kind = MessageKind.ACTION,
            relayedSender = "Zoë",
            relaySource = "Matrix",
            relayedBody = "\u0002waves\u0002 👩‍🚀",
            channelContext = "#日本語",
            replyToMsgid = "parent",
            attachmentUrl = "https://example.test/photo.png",
            attachmentName = "Photo",
            attachmentMimeType = "image/png",
            attachmentSizeBytes = 123,
            pendingEcho = true,
            reactionsTruncated = true,
            highlightsMe = true,
        )
        assertEquals(
            "Zoë. 14:05. Action. waves 👩‍🚀. Relayed via Matrix. Channel context #日本語. Reply to earlier words. Attachment: Photo. image/png. 123 bytes. Delivery unconfirmed. Mentions you. Partial reaction history, retained members only. woman astronaut: at least 2 reactions",
            messageAccessibilitySummary(message, "14:05", "\u0002earlier words\u0002", mapOf("👩‍🚀" to setOf("alice", "bob")), mapOf("👩‍🚀" to "woman astronaut")),
        )
    }

    @Test
    fun redactionNeverAnnouncesRetainedPrivateBodyQuoteLinksOrReactionHistory() {
        val deleted = message().copy(
            redacted = true,
            relayedBody = "secret https://private.test",
            attachmentUrl = "https://private.test/attachment",
            attachmentName = "secret file",
            replyToMsgid = "parent",
            reactionsTruncated = true,
        )
        assertEquals("alice. 14:05. Message deleted", messageAccessibilitySummary(deleted, "14:05", "secret quote", mapOf("😀" to setOf("bob"))))
        assertEquals(emptyList(), messageAccessibilityLinks(deleted))
        assertEquals(emptySet(), eligibleMessageActions(deleted, AccessibleMessageAction.entries.toSet()))
    }

    @Test
    fun absentPermissionsExposeNoActionsAndIdentifiersAloneDoNotAuthorizeEffects() {
        assertEquals(emptySet(), eligibleMessageActions(message().copy(sentByUs = true), emptySet()))
        assertEquals(setOf(AccessibleMessageAction.COPY), eligibleMessageActions(message(), setOf(AccessibleMessageAction.COPY)))
        assertFalse(AccessibleMessageAction.DELETE in eligibleMessageActions(message(), AccessibleMessageAction.entries.toSet()))
    }

    @Test
    fun idlessAndPendingMessagesCannotExposeRemoteReactionOrDeletion() {
        val requested = AccessibleMessageAction.entries.toSet()
        assertEquals(setOf(AccessibleMessageAction.REPLY, AccessibleMessageAction.COPY), eligibleMessageActions(message().copy(msgid = null, sentByUs = true), requested))
        assertFalse(AccessibleMessageAction.DELETE in eligibleMessageActions(message().copy(sentByUs = true, pendingEcho = true), requested))
        assertTrue(AccessibleMessageAction.DELETE in eligibleMessageActions(message().copy(sentByUs = true), requested))
        assertFalse(AccessibleMessageAction.REACTION in eligibleMessageActions(message().copy(kind = MessageKind.TOPIC), requested))
        assertFalse(AccessibleMessageAction.COPY in eligibleMessageActions(message().copy(text = "\u0002\u000f"), requested))
    }

    @Test
    fun distinctLinksKeepUnicodeChannelsBalancedUrlPunctuationAndNickRouting() {
        val links = messageAccessibilityLinks(message().copy(
            text = "Visit https://example.test/a(b). https://other.test/end! #日本語 @bob https://example.test/a(b).",
            attachmentUrl = "https://example.test/a(b)",
            channelContext = "#日本語",
        ))
        assertEquals(listOf(
            AccessibleMessageLink(AccessibleLinkKind.URL, "https://example.test/a(b)"),
            AccessibleMessageLink(AccessibleLinkKind.URL, "https://other.test/end"),
            AccessibleMessageLink(AccessibleLinkKind.CHANNEL, "#日本語"),
            AccessibleMessageLink(AccessibleLinkKind.NICK, "bob"),
        ), links)
        assertEquals(links.size, links.map { it.label }.distinct().size)
    }

    @Test
    fun allMessageKindsAreDistinguishableIncludingHistoryAndLocalReplyContext() {
        val labels = mapOf(
            MessageKind.PRIVMSG to "Message", MessageKind.NOTICE to "Notice", MessageKind.ACTION to "Action",
            MessageKind.SYSTEM to "System event", MessageKind.JOIN to "Join event", MessageKind.PART to "Part event",
            MessageKind.QUIT to "Quit event", MessageKind.NICK_CHANGE to "Nickname change", MessageKind.MODE to "Mode change",
            MessageKind.TOPIC to "Topic change", MessageKind.KICK to "Kick event",
        )
        for ((kind, label) in labels) {
            val summary = messageAccessibilitySummary(message().copy(kind = kind, playback = true, historyContext = true, localReplyParentRowId = 42), "14:05", null, emptyMap())
            assertEquals("alice. 14:05. $label. Hello. Reply to earlier message. History message. Historical context", summary)
        }
    }

    @Test
    fun timestampFormatsAreLocaleStableAcrossMidnightAndZoneTransitions() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val midnight = Instant.parse("2026-01-01T00:00:09Z").toEpochMilli()
            val utc = ZoneId.of("UTC")
            assertEquals("00:00", formatTime(midnight, TimestampFormat.TIME_24, utc))
            assertEquals("12:00 AM", formatTime(midnight, TimestampFormat.TIME_12, utc))
            assertEquals("00:00:09", formatTime(midnight, TimestampFormat.TIME_SECONDS, utc))
            assertEquals("2026-01-01 00:00:09Z", formatTime(midnight, TimestampFormat.ISO_DATE_TIME, utc))
            assertEquals("11:00 PM", formatTime(midnight, TimestampFormat.TIME_12, ZoneId.of("Atlantic/Azores")))
            val beforeDst = Instant.parse("2026-03-08T06:59:59Z").toEpochMilli()
            val newYork = ZoneId.of("America/New_York")
            assertEquals("01:59:59", formatTime(beforeDst, TimestampFormat.TIME_SECONDS, newYork))
            assertEquals("03:00:00", formatTime(beforeDst + 1000, TimestampFormat.TIME_SECONDS, newYork))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun descriptiveEmojiNamesAndAvatarInitialsPreserveSupplementaryUnicode() {
        val entries = parseEmojiCatalog(sequenceOf(
            "# group: People & Body",
            "1F469 1F3FD 200D 1F680 ; fully-qualified # 👩🏽‍🚀 E4.0 woman astronaut: medium skin tone",
            "# group: Flags",
            "1F1FA 1F1F8 ; fully-qualified # 🇺🇸 E0.6 flag: United States",
        )) { true }
        val names = entries.associate { it.emoji to it.name }
        assertEquals("woman astronaut: medium skin tone", emojiAccessibilityLabel("👩🏽‍🚀", names))
        assertEquals("flag: United States", emojiAccessibilityLabel("🇺🇸", names))
        assertEquals("grinning face", emojiAccessibilityLabel("😀"))
        assertEquals("?", avatarInitial("_👩‍🚀"))
        assertEquals("𐐀", avatarInitial("_𐐨nick"))
        assertEquals("É", avatarInitial("_éclair"))
    }

    private fun message(): ChatMessage = ChatMessage(
        localId = 1, sender = "alice", kind = MessageKind.PRIVMSG, text = "Hello", timestampMs = 0,
        sentByUs = false, highlightsMe = false, msgid = "message-id",
    )
}
