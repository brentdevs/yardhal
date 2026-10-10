package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.CaseMapping
import dev.brentdevs.yardhal.core.protocol.RelayFormat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InteractionPreferencesTests {
    @Test
    fun relayAllowlistPersistsPerNetworkAndRemovalStopsParsing() {
        val directory = Files.createTempDirectory("relay-settings").toFile()
        try {
            RelayConfigurationStore(directory).update("one", listOf(RelayConfiguration("bridge", RelayFormat.ANGLE.name)))
            val restored = RelayConfigurationStore(directory)
            assertEquals("alice", restored.parse("one", "bridge", "<alice> hello", CaseMapping.ASCII)?.sender)
            assertNull(restored.parse("two", "bridge", "<alice> hello", CaseMapping.ASCII))
            restored.update("one", emptyList())
            assertNull(RelayConfigurationStore(directory).parse("one", "bridge", "<alice> hello", CaseMapping.ASCII))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun updatingRelayReplacesFoldEqualSenderUsingTheSelectedNetworksMapping() {
        val directory = Files.createTempDirectory("relay-casemapping").toFile()
        try {
            val store = RelayConfigurationStore(directory)
            store.update("rfc", listOf(RelayConfiguration("Alice[", "ANGLE")))
            store.upsert("rfc", RelayConfiguration("alice{", "BRACKET"), CaseMapping.RFC1459)
            assertEquals(listOf(RelayConfiguration("alice{", "BRACKET")), RelayConfigurationStore(directory).forNetwork("rfc"))
            assertEquals("bob", store.parse("rfc", "ALICE[", "[bob] new format", CaseMapping.RFC1459)?.sender)
            assertNull(store.parse("rfc", "ALICE[", "<bob> old format", CaseMapping.RFC1459))

            store.update("ascii", listOf(RelayConfiguration("Alice[", "ANGLE")))
            store.upsert("ascii", RelayConfiguration("alice{", "BRACKET"), CaseMapping.ASCII)
            assertEquals("bob", store.parse("ascii", "ALICE[", "<bob> original", CaseMapping.ASCII)?.sender)
            assertEquals("carol", store.parse("ascii", "ALICE{", "[carol] distinct", CaseMapping.ASCII)?.sender)
            store.upsert("ascii", RelayConfiguration("ALICE[", "COLON"), CaseMapping.ASCII)
            assertEquals("bob", store.parse("ascii", "alice[", "bob: replacement", CaseMapping.ASCII)?.sender)
            assertNull(store.parse("ascii", "alice[", "<bob> original", CaseMapping.ASCII))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun masksAndUnknownFormatsCannotCreateTrustedRelayIdentity() {
        val directory = Files.createTempDirectory("relay-validation").toFile()
        try {
            val store = RelayConfigurationStore(directory)
            for (nick in listOf("*", "bridge?", "bot!user@host", "two words", "")) {
                assertFailsWith<IllegalArgumentException> { store.update("one", listOf(RelayConfiguration(nick, "ANGLE"))) }
            }
            assertFailsWith<IllegalArgumentException> { store.update("one", listOf(RelayConfiguration("bridge", ".*"))) }
            assertEquals(emptyList(), store.forNetwork("one"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun recentsAreBoundedDeduplicatedOrderedAndDurable() {
        val directory = Files.createTempDirectory("recent-emoji").toFile()
        try {
            val store = RecentEmojiStore(directory)
            store.record("👍")
            store.record("❤️")
            store.record("👍")
            assertEquals(listOf("👍", "❤️"), RecentEmojiStore(directory).recent.value)
            repeat(60) { store.record("emoji-$it") }
            assertEquals(48, store.recent.value.size)
            assertEquals("emoji-59", RecentEmojiStore(directory).recent.value.first())
            assertFailsWith<IllegalArgumentException> { store.record("\n") }
        } finally {
            directory.deleteRecursively()
        }
    }
}
