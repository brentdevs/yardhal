package dev.brentdevs.yardhal.core.data

import android.util.Base64
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

public data class SyncPendingChange(
    public val id: String,
    public val kind: String,
    public val key: String,
    public val value: JsonElement?,
    public val base: Long,
)

public data class SyncRemoteChange(
    public val seq: Long,
    public val kind: String,
    public val key: String,
    public val value: JsonElement?,
    public val origin: String,
)

public data class SyncMaterialization(
    public val kind: String,
    public val key: String,
    public val value: JsonElement?,
    public val seq: Long,
)

public data class SyncPosition(public val epoch: String?, public val cursor: Long, public val refreshRequired: Boolean = false)

public class SyncLedger(private val database: YardhalDatabase, private val accountId: String) {
    private val dao: SyncDao = database.syncDao()
    private val messages: MessageDao = database.messageDao()

    init { require(accountId.isNotBlank()) }

    public suspend fun syncPosition(): SyncPosition = dao.state(accountId)?.let {
        SyncPosition(it.epoch, it.cursor, it.refreshRequired)
    } ?: SyncPosition(null, 0)

    public suspend fun pending(limit: Int = 200): List<SyncPendingChange> {
        require(limit in 1..200)
        return dao.pending(accountId, limit).map { SyncPendingChange(it.id, it.kind, it.entityKey, decode(it.valueJson), it.baseSeq) }
    }
    public suspend fun pendingCount(): Int = dao.pendingCount(accountId)


    public suspend fun failures(): Map<String, String> = dao.failures(accountId).associate { it.id to requireNotNull(it.failure) }

    public suspend fun acknowledge(accepted: List<String>, rejected: Map<String, String> = emptyMap()) {
        database.withTransaction {
            for (id in accepted) if (dao.outboxById(id)?.accountId == accountId) dao.deleteOutbox(id)
            var refresh = false
            for ((id, reason) in rejected) {
                if (dao.outboxById(id)?.accountId != accountId) continue
                dao.rejectOutbox(id, reason)
                if (reason in AUTHORITATIVE_REJECTIONS) refresh = true
            }
            if (refresh) {
                val state = dao.state(accountId) ?: SyncStateRow(accountId)
                dao.putState(state.copy(refreshRequired = true))
            }
        }
    }

    public suspend fun beginSnapshotRefresh() {
        database.withTransaction {
            val state = dao.state(accountId) ?: SyncStateRow(accountId)
            dao.resetEntitySequences(accountId)
            dao.putState(state.copy(cursor = 0, refreshRequired = true))
        }
    }

    public suspend fun completeSnapshotRefresh() {
        database.withTransaction {
            val state = dao.state(accountId) ?: return@withTransaction
            dao.putState(state.copy(refreshRequired = false))
        }
    }

    public suspend fun resetEpoch(epoch: String) {
        require(epoch.isNotBlank())
        database.withTransaction {
            dao.clearInbox(accountId)
            dao.deleteAcceptedEntities(accountId)
            dao.resetEntitySequences(accountId)
            dao.resetOutboxBases(accountId)
            dao.putState(SyncStateRow(accountId))
        }
    }
    public suspend fun purgeAccount() {
        database.withTransaction { clearAccount(dao, accountId) }
    }


    public suspend fun pendingMaterializations(): List<SyncMaterialization> =
        dao.inbox(accountId).map { SyncMaterialization(it.kind, it.entityKey, decode(it.valueJson), it.remoteSeq) }

    public suspend fun confirmMaterialized(kind: String, key: String, seq: Long) {
        database.withTransaction { dao.deleteInbox(accountId, kind, key, seq) }
    }

    public suspend fun captureBaseline(kind: String, values: Map<String, JsonElement?>) {
        require(kind in CONFIG_KINDS)
        for ((key, value) in values) SyncProjection.requireSafe(kind, key, value)
        database.withTransaction {
            for ((key, value) in values) dao.putBaseline(SyncBaselineRow(accountId, kind, key, encode(value)))
        }
    }

    public suspend fun clearBaselines() {
        database.withTransaction { dao.clearBaselines(accountId) }
    }

    public suspend fun reconcileSnapshot(kind: String, values: Map<String, JsonElement?>) {
        require(kind in CONFIG_KINDS)
        for ((key, value) in values) SyncProjection.requireSafe(kind, key, value)
        database.withTransaction {
            val known = dao.entities(accountId, kind).associateBy { it.entityKey }
            for ((key, value) in values) reconcileObserved(kind, key, encode(value), known[key])
            for ((key, previous) in known) {
                if (key !in values) reconcileObserved(kind, key, null, previous)
            }
        }
    }

    private suspend fun reconcileObserved(kind: String, key: String, valueJson: String?, previous: SyncEntityRow?) {
        if (kind == "read") {
            if (valueJson == null) return
            if (previous?.valueJson != null && previous.valueJson != valueJson) {
                val older = ((decode(previous.valueJson) as JsonObject)["timestampMs"] as JsonPrimitive).longOrNull
                val current = ((decode(valueJson) as JsonObject)["timestampMs"] as JsonPrimitive).longOrNull
                if (older != null && current != null && current <= older) {
                    if (dao.inboxFor(accountId, kind, key) == null) {
                        val preimages = JsonArray(listOf(JsonPrimitive(valueJson))).toString()
                        dao.putInbox(SyncInboxRow(accountId, kind, key, previous.valueJson, previous.remoteSeq, preimages))
                    }
                    return
                }
            }
        }
        val incoming = dao.inboxFor(accountId, kind, key)
        if (incoming != null) {
            val candidates = Json.parseToJsonElement(incoming.preimagesJson).jsonArray
            if (valueJson == incoming.valueJson || candidates.any { it.jsonPrimitive.content == (valueJson ?: "") }) return
            if (dao.hasFailure(accountId, kind, key)) return
            dao.deleteInbox(accountId, kind, key, incoming.remoteSeq)
        }
        if (valueJson == null && isNeutralValue(kind, previous?.valueJson)) return
        if (isNeutralValue(kind, valueJson) && (previous == null || previous.valueJson == null)) return
        if (previous == null && valueJson == null) return
        if (previous == null || previous.valueJson != valueJson) enqueue(kind, key, valueJson)
    }

    public suspend fun applyRemote(changes: List<SyncRemoteChange>, epoch: String, cursor: Long): List<SyncMaterialization> =
        database.withTransaction {
            require(epoch.isNotBlank() && cursor >= 0)
            val position = dao.state(accountId) ?: SyncStateRow(accountId)
            require(position.epoch == null || position.epoch == epoch) { "Reset epoch before applying a different account generation" }
            require(cursor >= position.cursor) { "Remote cursor cannot move backwards" }
            for (change in changes) {
                require(change.seq > 0 && change.seq <= cursor)
                SyncProjection.requireSafe(change.kind, change.key, change.value)
                val prior = dao.entity(accountId, change.kind, change.key)
                if (change.seq <= (prior?.remoteSeq ?: 0)) continue
                val valueJson = encode(change.value)
                val outstanding = dao.outboxFor(accountId, change.kind, change.key)
                val reactionRemoved = change.kind == "reaction" &&
                    change.value == JsonObject(mapOf("present" to JsonPrimitive(false)))
                if ((valueJson == null || reactionRemoved) && outstanding.isNotEmpty()) {
                    for (item in outstanding) {
                        if (item.valueJson == null || (reactionRemoved && item.valueJson == valueJson)) dao.deleteOutbox(item.id)
                        else dao.rejectOutbox(item.id, if (reactionRemoved) "stale_reaction_add" else "stale_tombstone")
                    }
                } else if (outstanding.isNotEmpty()) {
                    dao.rebasePending(accountId, change.kind, change.key, change.seq)
                    dao.putEntity(SyncEntityRow(accountId, change.kind, change.key, prior?.valueJson, change.seq))
                    continue
                }
                dao.putEntity(SyncEntityRow(accountId, change.kind, change.key, valueJson, change.seq))
                if (change.kind in INTERACTION_KINDS) applyInteraction(change)
                else {
                    val previousInbox = dao.inboxFor(accountId, change.kind, change.key)
                    val candidates = linkedSetOf<String>()
                    if (previousInbox == null) {
                        candidates.add(dao.baselineFor(accountId, change.kind, change.key)?.valueJson ?: prior?.valueJson ?: "")
                    } else {
                        for (candidate in Json.parseToJsonElement(previousInbox.preimagesJson).jsonArray) {
                            candidates.add(candidate.jsonPrimitive.content)
                        }
                        candidates.add(previousInbox.valueJson ?: "")
                    }
                    val preimages = JsonArray(candidates.map(::JsonPrimitive)).toString()
                    require(preimages.length <= 128 * 1024) { "Materialize pending remote configuration before accepting more changes" }
                    dao.putInbox(SyncInboxRow(accountId, change.kind, change.key, valueJson, change.seq, preimages))
                }
            }
            dao.putState(position.copy(epoch = epoch, cursor = cursor))
            pendingMaterializations()
        }

    public suspend fun localReaction(
        conversation: ConversationRef, msgid: String, sender: String, emoji: String, added: Boolean,
    ): Set<Long> {
        val key = reactionKey(conversation.networkId, conversation.normalizedTarget, msgid, emoji, sender)
        val value = JsonObject(mapOf("present" to JsonPrimitive(added)))
        return database.withTransaction {
            val row = messages.byMsgid(conversation.networkId, conversation.normalizedTarget, msgid)
            if (row?.redacted == true || messages.tombstoned(conversation.networkId, conversation.normalizedTarget, "msgid:$msgid", true)) {
                return@withTransaction emptySet()
            }
            val changed = messages.applyReactionAtomic(MessageReactionRow(conversation.networkId, conversation.normalizedTarget, msgid,
                emoji, sender, System.currentTimeMillis()), added)
            enqueue("reaction", key, encode(value))
            changed
        }
    }

    public suspend fun localRedaction(conversation: ConversationRef, msgid: String) {
        val key = messageKey(conversation.networkId, conversation.normalizedTarget, msgid)
        database.withTransaction {
            messages.redactAtomic(conversation.networkId, conversation.normalizedTarget, msgid, System.currentTimeMillis())
            enqueue("redaction", key, encode(JsonObject(mapOf("deleted" to JsonPrimitive(true)))))
        }
    }

    public suspend fun localReply(conversation: ConversationRef, msgid: String, parentMsgid: String) {
        val key = messageKey(conversation.networkId, conversation.normalizedTarget, msgid)
        val value = JsonObject(mapOf("parentMsgid" to JsonPrimitive(parentMsgid)))
        SyncProjection.requireSafe("reply", key, value)
        database.withTransaction {
            val row = messages.byMsgid(conversation.networkId, conversation.normalizedTarget, msgid)
                ?: throw IllegalArgumentException("Reply metadata needs an existing message")
            require(!row.redacted && (row.replyToMsgid == null || row.replyToMsgid == parentMsgid))
            if (row.replyToMsgid == null) {
                val parent = messages.byMsgid(conversation.networkId, conversation.normalizedTarget, parentMsgid)
                messages.update(row.copy(replyToMsgid = parentMsgid, replyParentRowId = parent?.rowId))
            }
            enqueue("reply", key, encode(value))
        }
    }

    public suspend fun localAttachment(
        conversation: ConversationRef, msgid: String, name: String?, mimeType: String?,
        sizeBytes: Long?, width: Int?, height: Int?,
    ) {
        val key = messageKey(conversation.networkId, conversation.normalizedTarget, msgid)
        val value = attachmentValue(name, mimeType, sizeBytes, width, height)
        SyncProjection.requireSafe("attachment", key, value)
        database.withTransaction {
            val row = messages.byMsgid(conversation.networkId, conversation.normalizedTarget, msgid)
                ?: throw IllegalArgumentException("Attachment metadata needs an existing message")
            require(!row.redacted)
            val updated = row.copy(attachmentName = name, attachmentMimeType = mimeType, attachmentSizeBytes = sizeBytes,
                attachmentWidth = width, attachmentHeight = height)
            if (row != updated) messages.update(updated)
            enqueue("attachment", key, encode(value))
        }
    }

    public suspend fun renameConversation(networkId: String, from: String, to: String): ConversationMerge {
        if (from == to) return ConversationMerge(0, emptyList())
        val oldPrefix = conversationKeyOrNull(networkId, from)?.plus("/")
        val newPrefix = conversationKeyOrNull(networkId, to)?.plus("/")
        return database.withTransaction {
            val moved = messages.renameAtomic(networkId, from, to)
            for (kind in INTERACTION_KINDS) {
                for (row in dao.entities(accountId, kind)) {
                    if (oldPrefix == null || !row.entityKey.startsWith(oldPrefix)) continue
                    val newKey = newPrefix?.plus(row.entityKey.removePrefix(oldPrefix))
                    val existing = newKey?.let { dao.entity(accountId, kind, it) }
                    for (pending in dao.outboxFor(accountId, kind, row.entityKey)) {
                        if (kind != "redaction") dao.deleteOutbox(pending.id)
                    }
                    if (newKey != null && SyncProjection.canSync(kind, newKey, decode(row.valueJson)) &&
                        (existing == null || row.remoteSeq >= existing.remoteSeq)) enqueue(kind, newKey, row.valueJson)
                    if (kind != "redaction" && row.remoteSeq > 0 && row.valueJson != null) {
                        enqueue(kind, row.entityKey, null)
                    } else if (kind != "redaction" && row.remoteSeq == 0L) {
                        dao.deleteEntity(accountId, kind, row.entityKey)
                    }
                }
            }
            moved
        }
    }

    public suspend fun hydrateOrphanMetadata(conversation: ConversationRef, msgid: String) {
        val key = messageKeyOrNull(conversation.networkId, conversation.normalizedTarget, msgid) ?: return
        database.withTransaction {
            val existing = messages.byMsgid(conversation.networkId, conversation.normalizedTarget, msgid)
            for (kind in listOf("redaction", "reply", "attachment")) {
                if (kind != "redaction" && dao.outboxFor(accountId, kind, key).isNotEmpty()) continue
                if (kind == "reply" && existing?.replyToMsgid != null) continue
                if (kind == "attachment" && existing != null && (
                        existing.attachmentName != null || existing.attachmentMimeType != null ||
                            existing.attachmentSizeBytes != null || existing.attachmentWidth != null || existing.attachmentHeight != null
                    )) continue
                val entity = dao.entity(accountId, kind, key) ?: continue
                applyInteraction(SyncRemoteChange(entity.remoteSeq, kind, key, decode(entity.valueJson), ""))
            }
        }
    }

    public suspend fun reconcileInteractionSnapshot(networkIds: Set<String>) {
        if (networkIds.isEmpty()) return
        database.withTransaction {
            for (record in messages.authoredInteractionMetadata(networkIds)) {
                val key = messageKeyOrNull(record.networkId, record.conversation, record.msgid) ?: continue
                record.replyToMsgid?.let { parent ->
                    val value = JsonObject(mapOf("parentMsgid" to JsonPrimitive(parent)))
                    if (SyncProjection.canSync("reply", key, value)) enqueue("reply", key, encode(value))
                }
                if (record.attachmentName != null || record.attachmentMimeType != null || record.attachmentSizeBytes != null ||
                    record.attachmentWidth != null || record.attachmentHeight != null) {
                    val value = attachmentValue(record.attachmentName, record.attachmentMimeType, record.attachmentSizeBytes,
                        record.attachmentWidth, record.attachmentHeight)
                    if (SyncProjection.canSync("attachment", key, value)) {
                        enqueue("attachment", key, encode(value))
                    }
                }
            }
            for (reaction in messages.retainedSyncReactions(networkIds)) {
                val key = reactionKeyOrNull(reaction.networkId, reaction.conversation, reaction.msgid,
                    reaction.emoji, reaction.sender) ?: continue
                if (dao.entity(accountId, "reaction", key) != null) continue
                val value = JsonObject(mapOf("present" to JsonPrimitive(true)))
                if (SyncProjection.canSync("reaction", key, value)) {
                    enqueue("reaction", key, encode(value))
                }
            }
            for (redaction in messages.privateRedactions(networkIds)) {
                val key = messageKeyOrNull(redaction.networkId, redaction.conversation, redaction.msgid) ?: continue
                val value = JsonObject(mapOf("deleted" to JsonPrimitive(true)))
                if (SyncProjection.canSync("redaction", key, value)) enqueue("redaction", key, encode(value))
            }
        }
    }

    private suspend fun enqueue(kind: String, key: String, valueJson: String?) {
        SyncProjection.requireSafe(kind, key, decode(valueJson))
        val previous = dao.entity(accountId, kind, key)
        if (previous != null && previous.valueJson == valueJson) return
        dao.putOutbox(SyncOutboxRow(UUID.randomUUID().toString(), accountId, kind, key, valueJson,
            previous?.remoteSeq ?: 0, dao.nextOrdinal()))
        dao.putEntity(SyncEntityRow(accountId, kind, key, valueJson, previous?.remoteSeq ?: 0))
    }

    private suspend fun applyInteraction(change: SyncRemoteChange) {
        val parts = change.key.split('/')
        val networkId = parts[0]
        val target = decodeKeyPart(parts[1])
        val msgid = decodeKeyPart(parts[2])
        val value = change.value as? JsonObject ?: return
        when (change.kind) {
            "redaction" -> if (value["deleted"] == JsonPrimitive(true)) messages.redactAtomic(networkId, target, msgid, System.currentTimeMillis())
            "reaction" -> {
                val emoji = decodeKeyPart(parts[3])
                val sender = decodeKeyPart(parts[4])
                messages.applyReactionAtomic(MessageReactionRow(networkId, target, msgid, emoji, sender, System.currentTimeMillis()),
                    value["present"] == JsonPrimitive(true))
            }
            "reply" -> {
                val row = messages.byMsgid(networkId, target, msgid)
                val parentId = (value["parentMsgid"] as JsonPrimitive).content
                if (row != null && !row.redacted && row.replyToMsgid != parentId) {
                    val parent = messages.byMsgid(networkId, target, parentId)
                    messages.update(row.copy(replyToMsgid = parentId, replyParentRowId = parent?.rowId))
                }
            }
            "attachment" -> {
                val row = messages.byMsgid(networkId, target, msgid)
                if (row != null && !row.redacted) messages.update(row.copy(
                    attachmentName = value.optionalString("name"), attachmentMimeType = value.optionalString("mimeType"),
                    attachmentSizeBytes = value.optionalLong("sizeBytes"), attachmentWidth = value.optionalInt("width"),
                    attachmentHeight = value.optionalInt("height"),
                ))
            }
        }
    }

    public companion object {
        public suspend fun purgeInactiveAccounts(database: YardhalDatabase, activeAccountId: String?) {
            database.withTransaction {
                val dao = database.syncDao()
                for (account in dao.accountIds()) {
                    if (account != activeAccountId) clearAccount(dao, account)
                }
            }
        }

        public fun conversationKey(networkId: String, target: String): String = "$networkId/${encodeKeyPart(target)}"
        public fun messageKey(networkId: String, target: String, msgid: String): String = "${conversationKey(networkId, target)}/${encodeKeyPart(msgid)}"
        public fun conversationKeyOrNull(networkId: String, target: String): String? =
            try { conversationKey(networkId, target) } catch (_: IllegalArgumentException) { null }

        public fun messageKeyOrNull(networkId: String, target: String, msgid: String): String? =
            try { messageKey(networkId, target, msgid) } catch (_: IllegalArgumentException) { null }

        public fun reactionKeyOrNull(networkId: String, target: String, msgid: String, emoji: String, sender: String): String? =
            try { reactionKey(networkId, target, msgid, emoji, sender) } catch (_: IllegalArgumentException) { null }

        public fun reactionKey(networkId: String, target: String, msgid: String, emoji: String, sender: String): String =
            "${messageKey(networkId, target, msgid)}/${encodeKeyPart(emoji)}/${encodeKeyPart(sender)}"

        public fun encodeKeyPart(part: String): String {
            require(part.isNotEmpty() && part.length <= 256 && part.none { it.isISOControl() })
            return Base64.encodeToString(part.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }

        public fun decodeKeyPart(encoded: String): String {
            require(encoded.isNotEmpty() && encoded.matches(Regex("[A-Za-z0-9_-]+")))
            val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val decoded = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            require(encodeKeyPart(decoded) == encoded)
            return decoded
        }
    }
}

private suspend fun clearAccount(dao: SyncDao, accountId: String) {
    dao.clearEntities(accountId)
    dao.clearOutbox(accountId)
    dao.clearInbox(accountId)
    dao.clearBaselines(accountId)
    dao.clearState(accountId)
}

private val AUTHORITATIVE_REJECTIONS: Set<String> = setOf(
    "identity_deleted", "stale_tombstone", "network_deleted", "stale_reaction_add", "reply_immutable",
    "message_deleted", "group_deleted", "redaction_immutable", "read_immutable", "future_revision",
)

internal val CONFIG_KINDS: Set<String> = setOf("network", "order", "pin", "group", "membership", "mute", "theme", "selection", "accent", "avatar", "read")
internal val INTERACTION_KINDS: Set<String> = setOf("reply", "reaction", "attachment", "redaction")

private fun encode(value: JsonElement?): String? = value?.takeUnless { it == JsonNull }?.let {
    Json.encodeToString(JsonElement.serializer(), canonicalJson(it))
}
private fun canonicalJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> {
        val sorted = linkedMapOf<String, JsonElement>()
        for (key in value.keys.sorted()) sorted[key] = canonicalJson(value.getValue(key))
        JsonObject(sorted)
    }
    is JsonArray -> JsonArray(value.map(::canonicalJson))
    else -> value
}
private fun decode(value: String?): JsonElement? = value?.let { Json.parseToJsonElement(it) }
private fun JsonObject.optionalString(key: String): String? = this[key]?.takeUnless { it == JsonNull }?.let { (it as JsonPrimitive).content }
private fun JsonObject.optionalLong(key: String): Long? = this[key]?.takeUnless { it == JsonNull }?.let { (it as JsonPrimitive).longOrNull }
private fun JsonObject.optionalInt(key: String): Int? = this[key]?.takeUnless { it == JsonNull }?.let { (it as JsonPrimitive).intOrNull }

private fun isNeutralValue(kind: String, valueJson: String?): Boolean = when (kind) {
    "pin", "mute" -> valueJson == "{\"enabled\":false}"
    "membership" -> valueJson == "{\"group\":null}"
    "selection" -> valueJson == "{\"id\":null}"
    "read" -> valueJson == "{\"timestampMs\":0}"
    "avatar" -> valueJson == "{\"show\":true}"
    else -> false
}

private fun attachmentValue(name: String?, mimeType: String?, sizeBytes: Long?, width: Int?, height: Int?): JsonObject = JsonObject(mapOf(
    "name" to (name?.let(::JsonPrimitive) ?: JsonNull),
    "mimeType" to (mimeType?.let(::JsonPrimitive) ?: JsonNull),
    "sizeBytes" to (sizeBytes?.let(::JsonPrimitive) ?: JsonNull),
    "width" to (width?.let(::JsonPrimitive) ?: JsonNull),
    "height" to (height?.let(::JsonPrimitive) ?: JsonNull),
))
