package dev.brentdevs.yardhal.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncLedgerTests {
    private val account = UUID.randomUUID().toString()
    private val network = UUID.randomUUID().toString()
    private val epoch = UUID.randomUUID().toString()

    @Test
    fun versionFourMigrationPreservesExistingMessagesAndCreatesLedger() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sync-v4-migration.db"
        context.deleteDatabase(name)
        try {
            val path = "schemas/dev.brentdevs.yardhal.core.data.YardhalDatabase/4.json"
            val exported = listOf(File(path), File("core/data/$path")).first { it.isFile }
            val schema = Json.parseToJsonElement(exported.readText()).jsonObject["database"]?.jsonObject
                ?: error("Missing v4 schema")
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { fixture ->
                for (entity in schema.getValue("entities").jsonArray) {
                    val row = entity.jsonObject
                    val table = row.getValue("tableName").jsonPrimitive.content
                    fixture.execSQL(row.getValue("createSql").jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table))
                    for (index in row.getValue("indices").jsonArray) {
                        fixture.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("${'$'}{TABLE_NAME}", table))
                    }
                }
                for (query in schema.getValue("setupQueries").jsonArray) fixture.execSQL(query.jsonPrimitive.content)
                fixture.execSQL("INSERT INTO messages(networkId,conversation,msgid,contentHash,senderNick,kind,text,sentByUs,timestampMs) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>(network, "#room", "old-msg", "hash", "alice", "NOTICE", "private archive", 1, 5))
                fixture.version = 4
            }
            YardhalDatabase.build(context, name).use { database ->
                assertEquals(5, database.openHelper.writableDatabase.version)
                assertEquals("private archive", database.messageDao().allRows().single().text)
                val ledger = SyncLedger(database, account)
                val key = SyncLedger.conversationKey(network, "#room")
                ledger.reconcileSnapshot("pin", mapOf(key to JsonObject(mapOf("enabled" to JsonPrimitive(true)))))
                assertEquals(1, ledger.pending().size)
            }
            YardhalDatabase.build(context, name).use { database ->
                assertEquals("private archive", database.messageDao().allRows().single().text)
                assertEquals(1, SyncLedger(database, account).pending().size)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun startupSnapshotRecoversCrashAfterJsonSaveBeforeJournalAndInboxAfterRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sync-recovery.db"
        val directory = File(context.cacheDir, "sync-recovery-config")
        context.deleteDatabase(name)
        try {
            val key = SyncLedger.conversationKey(network, "#room")
            val store = ChannelOrderStore(directory)
            store.togglePin("$network|#room")
            YardhalDatabase.build(context, name).use { database ->
                val ledger = SyncLedger(database, account)
                val value = JsonObject(mapOf("enabled" to JsonPrimitive(true)))
                ledger.reconcileSnapshot("pin", mapOf(key to value))
                val pending = ledger.pending().single()
                ledger.reconcileSnapshot("pin", mapOf(key to value))
                assertEquals(pending.id, ledger.pending().single().id)
                ledger.acknowledge(listOf(pending.id))
                ledger.applyRemote(listOf(SyncRemoteChange(1, "pin", key,
                    JsonObject(mapOf("enabled" to JsonPrimitive(false))), "other-device")), epoch, 1)
                ledger.reconcileSnapshot("pin", mapOf(key to value))
                assertTrue(ledger.pending().isEmpty())
            }
            YardhalDatabase.build(context, name).use { database ->
                val ledger = SyncLedger(database, account)
                val apply = ledger.pendingMaterializations().single()
                assertEquals(key, apply.key)
                assertEquals(JsonObject(mapOf("enabled" to JsonPrimitive(false))), apply.value)
                assertEquals(SyncPosition(epoch, 1), ledger.syncPosition())
                store.applySyncState(store.snapshot().copy(pinnedKeys = emptyList()))
                ledger.confirmMaterialized(apply.kind, apply.key, apply.seq)
                assertTrue(ledger.pendingMaterializations().isEmpty())
                ledger.reconcileSnapshot("pin", emptyMap())
                assertTrue(ledger.pending().isEmpty())
            }
        } finally {
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }

    @Test
    fun removingPreviouslySyncedPositivePinCreatesARealTombstone() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { database ->
            val ledger = SyncLedger(database, account)
            val key = SyncLedger.conversationKey(network, "#room")
            ledger.reconcileSnapshot("pin", mapOf(key to JsonObject(mapOf("enabled" to JsonPrimitive(true)))))
            ledger.acknowledge(ledger.pending().map { it.id })
            ledger.reconcileSnapshot("pin", emptyMap())
            assertEquals(key, ledger.pending().single().key)
            assertNull(ledger.pending().single().value)
        }
    }

    @Test
    fun remoteNeutralSettingsDoNotTurnIntoOutboundTombstonesOrDefaultOverrides() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { database ->
            val ledger = SyncLedger(database, account)
            val key = SyncLedger.conversationKey(network, "#room")
            ledger.applyRemote(listOf(
                SyncRemoteChange(1, "pin", key, JsonObject(mapOf("enabled" to JsonPrimitive(false))), "peer"),
                SyncRemoteChange(2, "mute", key, JsonObject(mapOf("enabled" to JsonPrimitive(false))), "peer"),
                SyncRemoteChange(3, "membership", key, JsonObject(mapOf("group" to kotlinx.serialization.json.JsonNull)), "peer"),
                SyncRemoteChange(4, "avatar", "global", null, "peer"),
            ), epoch, 4)
            for (item in ledger.pendingMaterializations()) {
                ledger.confirmMaterialized(item.kind, item.key, item.seq)
            }
            ledger.reconcileSnapshot("pin", emptyMap())
            ledger.reconcileSnapshot("mute", emptyMap())
            ledger.reconcileSnapshot("membership", emptyMap())
            ledger.reconcileSnapshot("avatar",
                mapOf("global" to JsonObject(mapOf("show" to JsonPrimitive(true)))))
            assertEquals(0, ledger.pendingCount())
        }
    }

    @Test
    fun localReactionRollsBackIfJournalCannotCommitAndRenameKeepsScopedKeys() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ref = ConversationRef.channel(network, "#before")
        YardhalDatabase.inMemory(context).use { database ->
            val ledger = SyncLedger(database, account)
            val store = MessageStore(database.messageDao())
            store.record(message(ref, "m1"))
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER block_outbox BEFORE INSERT ON sync_outbox " +
                "BEGIN SELECT RAISE(ABORT, 'outbox failure'); END")
            assertFails { ledger.localReaction(ref, "m1", "alice", "❤", true) }
            assertTrue(store.reactions(ref, "m1").isEmpty())
            assertTrue(ledger.pending().isEmpty())
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_outbox")
            assertEquals(1, ledger.localReaction(ref, "m1", "alice", "❤", true).size)
            val rename = ledger.renameConversation(network, ref.normalizedTarget, "#after")
            assertEquals(1, rename.movedCount)
            assertTrue(ledger.pending().any { it.kind == "reaction" && it.key.startsWith(SyncLedger.conversationKey(network, "#after")) })
            assertTrue(store.reactions(ConversationRef.channel(network, "#after"), "m1").isNotEmpty())
            assertTrue(ledger.pending().none { it.key.startsWith(SyncLedger.conversationKey(network, "#before")) })
        }
    }

    @Test
    fun orphanReplyReactionAndRedactionPersistWithoutBodiesOrUrls(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ref = ConversationRef.channel(network, "#room")
        val key = SyncLedger.messageKey(network, ref.normalizedTarget, "m1")
        YardhalDatabase.inMemory(context).use { database ->
            val ledger = SyncLedger(database, account)
            val reactionKey = SyncLedger.reactionKey(network, ref.normalizedTarget, "m1", "❤", "alice")
            ledger.applyRemote(listOf(
                SyncRemoteChange(1, "reply", key, JsonObject(mapOf("parentMsgid" to JsonPrimitive("parent"))), "peer"),
                SyncRemoteChange(2, "reaction", reactionKey, JsonObject(mapOf("present" to JsonPrimitive(true))), "peer"),
            ), epoch, 2)
            val store = MessageStore(database.messageDao())
            assertTrue(store.reactions(ref, "m1").isEmpty())
            store.record(message(ref, "m1"))
            ledger.hydrateOrphanMetadata(ref, "m1")
            assertEquals("parent", store.findStoredMessage(message(ref, "m1"))?.replyToMsgid)
            assertEquals(mapOf("❤" to setOf("alice")), store.reactions(ref, "m1"))
            ledger.applyRemote(listOf(SyncRemoteChange(3, "redaction", key,
                JsonObject(mapOf("deleted" to JsonPrimitive(true))), "peer")), epoch, 3)
            assertTrue(store.reactions(ref, "m1").isEmpty())
            assertTrue(assertNotNull(store.findStoredMessage(message(ref, "m1"))).redacted)
            assertTrue(ledger.pending().isEmpty())
            assertFails { ledger.localAttachment(ref, "m1", "https://example.test/private", "image/png", 1, 1, 1) }
        }
    }

    @Test
    fun bootstrapReplaysOnlyPortableMetadataAndRemovalDefeatsStaleMembership() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ref = ConversationRef.channel(network, "#room")
        YardhalDatabase.inMemory(context).use { database ->
            val store = MessageStore(database.messageDao())
            val own = message(ref, "own").copy(replyToMsgid = "parent", attachmentName = "photo.png",
                attachmentUrl = "https://private.example.test/photo", attachmentMimeType = "image/png")
            store.record(own)
            store.record(message(ref, "inbound").copy(sentByUs = false, replyToMsgid = "unshared",
                attachmentName = "inbound.png"))
            store.applyReaction(ref, "own", "bob", "❤", true)
            val ledger = SyncLedger(database, account)
            ledger.reconcileInteractionSnapshot(setOf(network))
            val changes = ledger.pending()
            assertEquals(setOf("reply", "attachment", "reaction"), changes.map { it.kind }.toSet())
            assertTrue(changes.none { it.key.contains(SyncLedger.encodeKeyPart("inbound")) })
            assertTrue(changes.none { it.value.toString().contains("private.example.test") || it.value.toString().contains("never sync this body") })
            ledger.reconcileInteractionSnapshot(setOf(network))
            assertEquals(changes.map { it.id }, ledger.pending().map { it.id })
            ledger.localReply(ref, "own", "parent")
            ledger.localAttachment(ref, "own", "photo.png", "image/png", null, null, null)
            assertEquals(changes.map { it.id }, ledger.pending().map { it.id })
            val reaction = changes.single { it.kind == "reaction" }
            ledger.applyRemote(listOf(SyncRemoteChange(1, "reaction", reaction.key,
                JsonObject(mapOf("present" to JsonPrimitive(false))), "peer")), epoch, 1)
            assertTrue(ledger.pending().none { it.kind == "reaction" })
            assertTrue(store.reactions(ref, "own").isEmpty())
            ledger.reconcileInteractionSnapshot(setOf(network))
            assertTrue(ledger.pending().none { it.kind == "reaction" })
        }
    }

    @Test
    fun bootstrapBaselineDistinguishesDefaultsFromAChangeSavedAfterRemoteInbox() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { database ->
            val ledger = SyncLedger(database, account)
            val group = "g-${UUID.randomUUID()}"
            val old = JsonObject(mapOf("name" to JsonPrimitive("local")))
            val remote = JsonObject(mapOf("name" to JsonPrimitive("remote")))
            val changed = JsonObject(mapOf("name" to JsonPrimitive("saved after inbox")))
            ledger.captureBaseline("group", mapOf(group to old))
            ledger.captureBaseline("avatar", mapOf("global" to JsonObject(mapOf("show" to JsonPrimitive(true)))))
            assertTrue(ledger.pending().isEmpty())
            ledger.applyRemote(listOf(
                SyncRemoteChange(1, "group", group, remote, "peer"),
                SyncRemoteChange(2, "avatar", "global", JsonObject(mapOf("show" to JsonPrimitive(false))), "peer"),
            ), epoch, 2)
            ledger.reconcileSnapshot("group", mapOf(group to old))
            ledger.reconcileSnapshot("avatar", mapOf("global" to JsonObject(mapOf("show" to JsonPrimitive(true)))))
            assertEquals(2, ledger.pendingMaterializations().size)
            assertTrue(ledger.pending().isEmpty())
            ledger.reconcileSnapshot("group", mapOf(group to changed))
            assertEquals(changed, ledger.pending().single().value)
            assertEquals(listOf("avatar"), ledger.pendingMaterializations().map { it.kind })
            ledger.confirmMaterialized("avatar", "global", 2)
            ledger.clearBaselines()
        }
    }

    @Test
    fun resetKeepsIntentAndNeverLeaksSensitiveNetworkFields(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { database ->
            val ledger = SyncLedger(database, account)
            val key = SyncLedger.conversationKey(network, "#room")
            ledger.reconcileSnapshot("read", mapOf(key to SyncProjection.read(1234)))
            val original = ledger.pending().single()
            ledger.resetEpoch(epoch)
            assertEquals(original.id, ledger.pending().single().id)
            assertEquals(0, ledger.pending().single().base)
            assertEquals(SyncPosition(null, 0), ledger.syncPosition())
            val other = SyncLedger(database, UUID.randomUUID().toString())
            assertTrue(other.pending().isEmpty())
            val config = NetworkConfig(id = network, name = "Private", host = "irc.example.test", nick = "alice",
                saslPasswordRef = "secret-ref", serverPasswordRef = "other-secret-ref")
            val projected = SyncProjection.network(config)
            assertFalse(projected.toString().contains("secret-ref"))
            assertFalse(projected.toString().contains("password", ignoreCase = true))
            assertTrue(SyncProjection.canSync("network", network,
                SyncProjection.network(config.copy(host = "www.irc.example.test"))))
            assertFalse(SyncProjection.canSync("reply",
                SyncLedger.messageKey(network, "#room", "file:private"),
                JsonObject(mapOf("parentMsgid" to JsonPrimitive("parent")))))
            assertFalse(SyncProjection.canSync("attachment",
                SyncLedger.messageKey(network, "#room", "message"),
                JsonObject(mapOf("name" to JsonPrimitive("www.example.test/private"),
                    "mimeType" to JsonPrimitive("image/png"), "sizeBytes" to JsonPrimitive(1),
                    "width" to JsonPrimitive(1), "height" to JsonPrimitive(1)))))
            assertFails { ledger.reconcileSnapshot("network", mapOf(network to JsonObject(mapOf("body" to JsonPrimitive("private"))))) }
        }
    }

    @Test
    fun restoredServerSnapshotReuploadsAcceptedStateButHonorsRemoteTombstones() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { database ->
            val ledger = SyncLedger(database, account)
            val networkValue = SyncProjection.network(NetworkConfig(id = network, name = "Archive",
                host = "irc.example.test", nick = "alice"))
            ledger.reconcileSnapshot("network", mapOf(network to networkValue))
            ledger.acknowledge(ledger.pending().map { it.id })
            val key = SyncLedger.conversationKey(network, "#room")
            val pin = JsonObject(mapOf("enabled" to JsonPrimitive(true)))
            ledger.reconcileSnapshot("pin", mapOf(key to pin))
            val unsentPin = ledger.pending().single()
            ledger.resetEpoch(UUID.randomUUID().toString())
            assertEquals(listOf(unsentPin.id), ledger.pending().map { it.id })
            ledger.captureBaseline("network", mapOf(network to networkValue))
            ledger.captureBaseline("pin", mapOf(key to pin))
            ledger.applyRemote(listOf(SyncRemoteChange(1, "pin", key, null, "restored-server")), epoch, 1)
            ledger.reconcileSnapshot("network", mapOf(network to networkValue))
            ledger.reconcileSnapshot("pin", mapOf(key to pin))
            assertEquals(listOf("network"), ledger.pending().map { it.kind })
            assertEquals(networkValue, ledger.pending().single().value)
            assertEquals(mapOf(unsentPin.id to "stale_tombstone"), ledger.failures())
            assertEquals(listOf("pin"), ledger.pendingMaterializations().map { it.kind })
            val apply = ledger.pendingMaterializations().single()
            ledger.confirmMaterialized(apply.kind, apply.key, apply.seq)
            ledger.reconcileSnapshot("pin", emptyMap())
            assertEquals(listOf("network"), ledger.pending().map { it.kind })
        }
    }

    @Test
    fun authoritativeRejectionRequestsRefreshAndConvergesImmutableReply() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ref = ConversationRef.channel(network, "#room")
        YardhalDatabase.inMemory(context).use { database ->
            val store = MessageStore(database.messageDao())
            store.record(message(ref, "reply").copy(replyToMsgid = "local-parent"))
            val ledger = SyncLedger(database, account)
            ledger.localReply(ref, "reply", "local-parent")
            val rejected = ledger.pending().single()
            ledger.acknowledge(emptyList(), mapOf(rejected.id to "reply_immutable"))
            assertEquals(0, ledger.pendingCount())
            assertTrue(ledger.syncPosition().refreshRequired)
            ledger.beginSnapshotRefresh()
            val remote = JsonObject(mapOf("parentMsgid" to JsonPrimitive("server-parent")))
            ledger.applyRemote(listOf(SyncRemoteChange(1, "reply",
                SyncLedger.messageKey(network, ref.normalizedTarget, "reply"), remote, "peer")), epoch, 1)
            assertEquals("server-parent", store.findStoredMessage(message(ref, "reply"))?.replyToMsgid)
            assertTrue(ledger.pending().isEmpty())
            ledger.completeSnapshotRefresh()
            assertFalse(ledger.syncPosition().refreshRequired)
            assertEquals("reply_immutable", ledger.failures()[rejected.id])
        }
    }

    @Test
    fun purgingInactiveAccountsRetainsLocalMessagesAndActiveOutbox(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { database ->
            val store = MessageStore(database.messageDao())
            val ref = ConversationRef.channel(network, "#room")
            store.record(message(ref, "retained"))
            val first = SyncLedger(database, account)
            val activeId = UUID.randomUUID().toString()
            val active = SyncLedger(database, activeId)
            val value = JsonObject(mapOf("enabled" to JsonPrimitive(true)))
            val key = SyncLedger.conversationKey(network, "#room")
            first.reconcileSnapshot("pin", mapOf(key to value))
            active.reconcileSnapshot("pin", mapOf(key to value))
            SyncLedger.purgeInactiveAccounts(database, activeId)
            assertEquals(0, first.pendingCount())
            assertEquals(1, active.pendingCount())
            assertNotNull(store.findStoredMessage(message(ref, "retained")))
            active.purgeAccount()
            assertEquals(0, active.pendingCount())
            assertNotNull(store.findStoredMessage(message(ref, "retained")))
        }
    }

    @Test
    fun readMarkersNeverEmitNullOrRegressBelowDurableRemoteMaximum(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        YardhalDatabase.inMemory(context).use { database ->
            val ledger = SyncLedger(database, account)
            val key = SyncLedger.conversationKey(network, "#room")
            ledger.reconcileSnapshot("read", mapOf(key to SyncProjection.read(2000)))
            ledger.acknowledge(ledger.pending().map { it.id })
            ledger.reconcileSnapshot("read", mapOf(key to SyncProjection.read(1000)))
            assertEquals(0, ledger.pendingCount())
            assertEquals(SyncProjection.read(2000), ledger.pendingMaterializations().single().value)
            ledger.reconcileSnapshot("read", emptyMap())
            assertEquals(0, ledger.pendingCount())
            assertFails { ledger.reconcileSnapshot("read", mapOf(key to null)) }
        }
    }

    private fun message(ref: ConversationRef, msgid: String): StoredMessage = StoredMessage(
        networkId = ref.networkId, conversation = ref, msgid = msgid, senderNick = "alice", senderUser = null,
        senderHost = null, kind = MessageKind.NOTICE, text = "never sync this body", sentByUs = true, timestampMs = 1000,
    )
}

private inline fun <T> YardhalDatabase.use(block: (YardhalDatabase) -> T): T = try { block(this) } finally { close() }
