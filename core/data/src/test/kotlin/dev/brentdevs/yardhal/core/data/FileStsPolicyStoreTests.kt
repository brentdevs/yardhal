package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.client.StsPolicy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileStsPolicyStoreTests {
    @Test
    fun policySurvivesStoreRecreation() {
        val directory = Files.createTempDirectory("yardhal-sts").toFile()
        try {
            val policy = StsPolicy(6697, 1_700_003_600, 3600)
            FileStsPolicyStore(directory).save("IRC.Example", policy)
            val reloaded = FileStsPolicyStore(directory)
            assertEquals(policy, reloaded.load("irc.example"))
            reloaded.delete("IRC.EXAMPLE")
            assertNull(FileStsPolicyStore(directory).load("irc.example"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
