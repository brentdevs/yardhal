package dev.brentdevs.yardhal.coordinator

import dev.brentdevs.yardhal.core.data.MessageKind
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.StoredMessage
import dev.brentdevs.yardhal.core.client.IrcEvent
import dev.brentdevs.yardhal.core.protocol.IrcMessage
import dev.brentdevs.yardhal.core.protocol.IrcMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OfflineSnapshotMappingTests {
    @Test
    fun partialLiveProfileObservationPreservesUnrefreshedCachedFieldsAndExplicitRemoval() {
        val saved = UserProfile(avatarUrl = "https://example.com/avatar", displayName = "Saved Alice", cached = true, observedAtMs = 100)
        val partial = UserState("alice", metadata = mapOf(IrcMetadata.KEY_DISPLAY_NAME to "Live Alice"),
            metadataObservedAtMs = 200, metadataKeysObserved = setOf(IrcMetadata.KEY_DISPLAY_NAME))
        val merged = mergeObservedProfile(saved, partial)
        assertEquals(UserProfile("https://example.com/avatar", "Live Alice", cached = true, observedAtMs = 200), merged)
        val removed = partial.copy(metadata = emptyMap(), metadataObservedAtMs = 300,
            metadataKeysObserved = setOf(IrcMetadata.KEY_AVATAR, IrcMetadata.KEY_DISPLAY_NAME))
        assertNull(mergeObservedProfile(merged, removed))
        assertEquals(saved, mergeObservedProfile(saved, UserState("alice")))
    }

    @Test
    fun liveMetadataChangesOnlyActuallyObservedKeysIncludingExplicitEmptyRemoval() {
        val saved = mapOf(IrcMetadata.KEY_AVATAR to "https://example.com/avatar",
            IrcMetadata.KEY_DISPLAY_NAME to "Saved Alice", "custom" to "keep")
        val removedAvatar = UserState("alice", metadata = mapOf(IrcMetadata.KEY_DISPLAY_NAME to "Live Alice"),
            metadataObservedAtMs = 200, metadataKeysObserved = setOf(IrcMetadata.KEY_AVATAR, IrcMetadata.KEY_DISPLAY_NAME))
        assertEquals(mapOf(IrcMetadata.KEY_DISPLAY_NAME to "Live Alice", "custom" to "keep"), mergeObservedMetadata(saved, removedAvatar))
    }

    @Test
    fun observingAnAbsentProfileKeyStillPublishesRemovalForCachedProfile() {
        val users = UserTable()
        users["alice"] = UserState("alice")
        val before = users.profileVersion
        users["alice"] = UserState("alice", metadataObservedAtMs = 200,
            metadataKeysObserved = setOf(IrcMetadata.KEY_AVATAR))
        assertTrue(users.profileVersion > before)
    }

    @Test
    fun unknownHistoricalHighlightCannotChangeKnownLiveHighlightAndEchoIsCanonical() {
        val live = message(highlights = false, known = true)
        val replay = message(highlights = true, known = false).copy(playback = true, historyContext = true)
        val forward = mergeChatMessageMetadata(live, replay, incomingCanonical = true)
        val reverse = mergeChatMessageMetadata(replay, live, incomingCanonical = true)
        assertFalse(forward.highlightsMe)
        assertFalse(reverse.highlightsMe)
        assertTrue(forward.highlightsKnown)
        assertTrue(reverse.highlightsKnown)
        assertFalse(mergeChatMessageMetadata(replay, replay).highlightsKnown)
        val optimistic = live.copy(msgid = null, pendingEcho = true, echoLabel = "optimistic", storedRowId = 10)
        val canonical = live.copy(localId = 20, msgid = "canonical", storedRowId = 10)
        val echo = mergeChatMessageMetadata(optimistic, canonical, incomingCanonical = true)
        assertEquals(optimistic.localId, echo.localId)
        assertEquals("canonical", echo.msgid)
        assertFalse(echo.pendingEcho)
        assertNull(echo.echoLabel)
    }

    @Test
    fun durableTruncatedReactionFlagSurvivesHydrationAndMetadataMergeButNotRedaction() {
        val stored = StoredMessage(networkId = "network", conversation = ConversationRef.channel("network", "#room"),
            msgid = "message", senderNick = "alice", senderUser = null, senderHost = null,
            kind = MessageKind.PRIVMSG, text = "hello", sentByUs = false, timestampMs = 100, reactionsTruncated = true)
        val hydrated = stored.toChatMessage(1)
        assertTrue(hydrated.reactionsTruncated)
        val merged = mergeChatMessageMetadata(hydrated, message(false, true), incomingCanonical = true)
        assertTrue(merged.reactionsTruncated)
        assertFalse(merged.asRedacted().reactionsTruncated)
    }

    @Test
    fun historicalMetadataCannotRefreshOrReplaceObservedLiveProfile() {
        val state = PerNetworkState("network", "tester")
        fun frame(value: String, tags: String = "") = IrcEvent.MessageReceived(assertNotNull(IrcMessage.parse(
            "$tags:srv METADATA alice ${IrcMetadata.KEY_DISPLAY_NAME} * :$value")))
        state.apply(frame("Live Alice"), InboundContext(nowMs = 200))
        state.apply(frame("Old playback"), InboundContext(nowMs = 300, historyPlayback = true))
        state.apply(frame("Old context", "@draft/chathistory-context=message "), InboundContext(nowMs = 400))
        val user = assertNotNull(state.users["alice"])
        assertEquals("Live Alice", user.metadata[IrcMetadata.KEY_DISPLAY_NAME])
        assertEquals(200L, user.metadataObservedAtMs)
        assertEquals(setOf(IrcMetadata.KEY_DISPLAY_NAME), user.metadataKeysObserved)
    }

    private fun message(highlights: Boolean, known: Boolean) = ChatMessage(localId = 1, sender = "alice",
        kind = MessageKind.PRIVMSG, text = "hello", timestampMs = 100, sentByUs = false,
        highlightsMe = highlights, msgid = "message", highlightsKnown = known)
}
