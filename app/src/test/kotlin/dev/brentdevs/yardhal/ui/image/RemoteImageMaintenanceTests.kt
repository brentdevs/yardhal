package dev.brentdevs.yardhal.ui.image

import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteImageMaintenanceTests {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun maintenanceExpiresUnrequestedImagesButKeepsTheExactLifetimeBoundary() {
        val now = 2_000_000_000L
        val lifetime = 7L * 24 * 60 * 60 * 1_000
        val expired = image("expired", 16, now - lifetime - 1)
        val boundary = image("boundary", 16, now - lifetime)
        val fresh = image("fresh", 16, now - 1)

        RemoteImageLoader(temporaryFolder.root) { now }.maintain()

        assertFalse(expired.exists())
        assertTrue(boundary.isFile)
        assertTrue(fresh.isFile)
        assertEquals(16L, boundary.length())
        assertEquals(16L, fresh.length())
    }

    @Test
    fun maintenanceEvictsOldestImagesUntilTheDiskBudgetIsSatisfied() {
        val now = 2_000_000_000L
        val size = 12L * 1024 * 1024
        val oldest = image("oldest", size, now - 3)
        val middle = image("middle", size, now - 2)
        val newest = image("newest", size, now - 1)

        RemoteImageLoader(temporaryFolder.root) { now }.maintain()

        assertFalse(oldest.exists())
        assertTrue(middle.isFile)
        assertTrue(newest.isFile)
        assertEquals(24L * 1024 * 1024, middle.length() + newest.length())
    }

    @Test
    fun maintenanceKeepsAnImageAtTheExactDiskBudget() {
        val now = 2_000_000_000L
        val size = 32L * 1024 * 1024
        val boundary = image("boundary", size, now)

        RemoteImageLoader(temporaryFolder.root) { now }.maintain()

        assertTrue(boundary.isFile)
        assertEquals(size, boundary.length())
    }

    private fun image(name: String, size: Long, modifiedAt: Long): File {
        val directory = File(temporaryFolder.root, "remote-images")
        assertTrue(directory.isDirectory || directory.mkdirs())
        return File(directory, name).also { file ->
            RandomAccessFile(file, "rw").use { it.setLength(size) }
            assertTrue(file.setLastModified(modifiedAt))
        }
    }
}
