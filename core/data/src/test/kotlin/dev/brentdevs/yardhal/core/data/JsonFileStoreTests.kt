package dev.brentdevs.yardhal.core.data

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class JsonFileStoreTests {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun encodingFailureCleansOwnedTemporaryAndKeepsLastCommit() {
        val target = File(tmp.root, "state.json")
        var rejectEncoding = false
        val serializer = object : KSerializer<String> by String.serializer() {
            override fun serialize(encoder: Encoder, value: String) {
                if (rejectEncoding) throw SerializationException("Rejected encoding")
                encoder.encodeString(value)
            }
        }
        val store = JsonFileStore(target, serializer)
        store.save("committed")
        rejectEncoding = true

        assertFailsWith<SerializationException> { store.save("rejected") }
        assertFalse(File(tmp.root, "state.json.tmp").exists())
        assertEquals("committed", store.loadOrDefault("missing"))
        assertEquals("committed", JsonFileStore(target, String.serializer()).loadOrDefault("missing"))

        rejectEncoding = false
        store.save("retried")
        assertEquals("retried", JsonFileStore(target, String.serializer()).loadOrDefault("missing"))
    }

    @Test
    fun renameFailureCleansOwnedTemporaryWithoutRemovingDestination() {
        val target = File(tmp.root, "state.json")
        val store = JsonFileStore(target, String.serializer())
        store.save("committed")
        assertTrue(target.delete())
        assertTrue(target.mkdir())
        val sentinel = File(target, "unrelated")
        sentinel.writeText("keep")

        assertFailsWith<IOException> { store.save("rejected") }
        assertFalse(File(tmp.root, "state.json.tmp").exists())
        assertEquals("committed", store.loadOrDefault("missing"))
        assertTrue(target.isDirectory)
        assertEquals("keep", sentinel.readText())

        assertTrue(sentinel.delete())
        assertTrue(target.delete())
        store.save("retried")
        assertEquals("retried", JsonFileStore(target, String.serializer()).loadOrDefault("missing"))
    }

    @Test
    fun leftoverTemporaryCanBeReusedAndSuccessfulSaveLeavesNoTemporary() {
        val target = File(tmp.root, "state.json")
        val store = JsonFileStore(target, String.serializer())
        store.save("committed")
        val leftover = File(tmp.root, "state.json.tmp")
        leftover.writeText("unfinished prior write")

        store.save("retried")
        assertFalse(leftover.exists())
        assertEquals("retried", store.loadOrDefault("missing"))
        assertEquals("retried", JsonFileStore(target, String.serializer()).loadOrDefault("missing"))
    }

    @Test
    fun directorySyncFailureKeepsCommittedCacheWithoutRollingBackRenamedFileAndRetryCommits() {
        val target = File(tmp.root, "state.json")
        var rejectSync = false
        val store = JsonFileStore(target, String.serializer(), Json) { directory ->
            syncStoreDirectory(if (rejectSync) File("/proc") else directory)
        }
        store.save("committed")
        rejectSync = true

        assertFailsWith<IOException> { store.save("uncertain") }
        assertFalse(File(tmp.root, "state.json.tmp").exists())
        assertEquals("committed", store.loadOrDefault("missing"))
        assertEquals("\"uncertain\"", target.readText())

        rejectSync = false
        store.save("uncertain")
        assertEquals("uncertain", store.loadOrDefault("missing"))
        assertEquals("uncertain", JsonFileStore(target, String.serializer()).loadOrDefault("missing"))
    }

    @Test
    fun firstSaveDirectorySyncFailureDoesNotLoadUncommittedReplacement() {
        val target = File(tmp.root, "state.json")
        val store = JsonFileStore(target, String.serializer(), Json) {
            syncStoreDirectory(File("/proc"))
        }

        assertFailsWith<IOException> { store.save("uncertain") }
        assertFalse(File(tmp.root, "state.json.tmp").exists())
        assertEquals("\"uncertain\"", target.readText())
        assertEquals("missing", store.loadOrDefault("missing"))
    }

    @Test
    fun unloadedStoreRetainsPreviousDurableValueAfterDirectorySyncFailure() {
        val target = File(tmp.root, "state.json")
        JsonFileStore(target, String.serializer()).save("committed")
        val store = JsonFileStore(target, String.serializer(), Json) {
            syncStoreDirectory(File("/proc"))
        }

        assertFailsWith<IOException> { store.save("uncertain") }
        assertEquals("committed", store.loadOrDefault("missing"))
        assertEquals("\"uncertain\"", target.readText())
        assertFalse(File(tmp.root, "state.json.tmp").exists())
    }

    @Test
    fun failedNewDirectoryCommitMustBeRetriedBeforeAnyFileCanPublish() {
        val target = File(tmp.root, "new/nested/state.json")
        var rejectSync = true
        val store = JsonFileStore(target, String.serializer(), Json) { directory ->
            syncStoreDirectory(if (rejectSync) File("/proc") else directory)
        }

        repeat(2) {
            assertFailsWith<IOException> { store.save("uncertain") }
            assertFalse(target.exists())
            assertFalse(File(target.parentFile, "state.json.tmp").exists())
        }
        assertEquals("missing", store.loadOrDefault("missing"))

        rejectSync = false
        store.save("committed")
        assertEquals("committed", store.loadOrDefault("missing"))
        assertEquals("committed", JsonFileStore(target, String.serializer()).loadOrDefault("missing"))
    }
}
