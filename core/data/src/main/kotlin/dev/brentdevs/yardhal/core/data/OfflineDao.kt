package dev.brentdevs.yardhal.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.brentdevs.yardhal.core.protocol.CaseMapping
import java.security.MessageDigest
import kotlinx.serialization.encodeToString

@Dao
public abstract class OfflineDao {
    @Query(
        "SELECT networkId, conversation, rawTarget, kind, displayName, caseMapping, observedAtMs, " +
            "topic, topicObservedAtMs, modesJson, modesObservedAtMs FROM offline_conversations " +
            "WHERE networkId = :networkId ORDER BY " +
            "CASE WHEN conversation = (SELECT conversation FROM launch_selection WHERE slot = 0 AND networkId = :networkId) " +
            "THEN 0 ELSE 1 END, observedAtMs DESC, conversation ASC LIMIT :limit",
    )
    public abstract suspend fun shells(networkId: String, limit: Int): List<OfflineShellRow>

    @Query(
        "SELECT networkId, conversation, rawTarget, kind, displayName, caseMapping, observedAtMs, " +
            "topic, topicObservedAtMs, modesJson, modesObservedAtMs FROM offline_conversations " +
            "WHERE networkId = :networkId AND conversation = :conversation",
    )
    public abstract suspend fun shell(networkId: String, conversation: String): OfflineShellRow?

    @Query("SELECT DISTINCT networkId FROM offline_conversations ORDER BY networkId")
    public abstract suspend fun shellNetworks(): List<String>

    @Query("SELECT * FROM offline_conversations WHERE networkId = :networkId AND conversation = :conversation")
    public abstract suspend fun conversation(networkId: String, conversation: String): OfflineConversationRow?

    @Query("SELECT * FROM offline_conversations WHERE networkId = :networkId ORDER BY conversation")
    public abstract suspend fun conversationRows(networkId: String): List<OfflineConversationRow>

    @Query("SELECT conversation, rosterJson FROM offline_conversations WHERE networkId = :networkId AND rosterJson IS NOT NULL ORDER BY conversation")
    public abstract suspend fun rosterRows(networkId: String): List<OfflineRosterRow>

    @Query("UPDATE offline_conversations SET rosterJson = :rosterJson WHERE networkId = :networkId AND conversation = :conversation")
    public abstract suspend fun updateRoster(networkId: String, conversation: String, rosterJson: String)

    @Query("SELECT * FROM whois_cache WHERE networkId = :networkId AND nick = :nick")
    public abstract suspend fun cachedUser(networkId: String, nick: String): WhoisCacheRow?

    @Query("SELECT * FROM whois_cache WHERE networkId = :networkId ORDER BY nick")
    public abstract suspend fun userRows(networkId: String): List<WhoisCacheRow>

    @Query("SELECT * FROM launch_selection WHERE slot = 0")
    public abstract suspend fun selection(): LaunchSelectionRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract suspend fun putConversation(row: OfflineConversationRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract suspend fun putUser(row: WhoisCacheRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract suspend fun putSelection(row: LaunchSelectionRow)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public abstract suspend fun putEvidence(row: OfflineQuarantineRow): Long

    @Query("SELECT * FROM offline_quarantine ORDER BY evidenceId")
    public abstract suspend fun evidence(): List<OfflineQuarantineRow>

    @Query("SELECT evidenceId, networkId, kind, identity FROM offline_quarantine ORDER BY evidenceId")
    public abstract suspend fun evidenceNotices(): List<OfflineEvidenceNoticeRow>

    @Query("DELETE FROM launch_selection")
    public abstract suspend fun clearSelection()

    @Query("DELETE FROM offline_conversations WHERE networkId = :networkId AND conversation = :conversation")
    public abstract suspend fun removeConversation(networkId: String, conversation: String)

    @Query("DELETE FROM offline_conversations WHERE networkId = :networkId")
    public abstract suspend fun removeConversations(networkId: String)

    @Query("DELETE FROM whois_cache WHERE networkId = :networkId AND nick = :nick")
    public abstract suspend fun removeUser(networkId: String, nick: String)

    @Query("DELETE FROM whois_cache WHERE networkId = :networkId")
    public abstract suspend fun removeUsers(networkId: String)

    @Query("DELETE FROM offline_quarantine WHERE networkId = :networkId")
    public abstract suspend fun removeEvidence(networkId: String)

    @Query("SELECT DISTINCT networkId FROM offline_conversations UNION SELECT DISTINCT networkId FROM whois_cache")
    public abstract suspend fun networks(): List<String>
    @Query("DELETE FROM offline_conversations WHERE observedAtMs < :cutoffMs")
    public abstract suspend fun expireConversations(cutoffMs: Long)

    @Query("UPDATE offline_conversations SET topic = NULL, topicObservedAtMs = NULL WHERE topicObservedAtMs < :cutoffMs")
    public abstract suspend fun expireTopics(cutoffMs: Long)

    @Query("UPDATE offline_conversations SET modesJson = '{}', modesObservedAtMs = NULL WHERE modesObservedAtMs < :cutoffMs")
    public abstract suspend fun expireModes(cutoffMs: Long)

    @Query("UPDATE offline_conversations SET rosterJson = NULL, rosterObservedAtMs = NULL WHERE rosterObservedAtMs < :cutoffMs")
    public abstract suspend fun expireRosters(cutoffMs: Long)

    @Query("UPDATE whois_cache SET whoisJson = NULL, whoisObservedAtMs = NULL WHERE whoisObservedAtMs < :cutoffMs")
    public abstract suspend fun expireWhois(cutoffMs: Long)

    @Query("UPDATE whois_cache SET userJson = NULL, userObservedAtMs = NULL WHERE userObservedAtMs < :cutoffMs")
    public abstract suspend fun expireUserMetadata(cutoffMs: Long)

    @Query("DELETE FROM whois_cache WHERE whoisJson IS NULL AND userJson IS NULL")
    public abstract suspend fun removeEmptyUsers()

    @Query(
        "DELETE FROM offline_conversations WHERE networkId = :networkId AND conversation NOT IN " +
            "(SELECT conversation FROM offline_conversations WHERE networkId = :networkId " +
            "ORDER BY CASE WHEN conversation = " +
            "(SELECT conversation FROM launch_selection WHERE slot = 0 AND networkId = :networkId) THEN 0 ELSE 1 END, " +
            "observedAtMs DESC, conversation ASC LIMIT :limit)",
    )
    public abstract suspend fun capConversations(networkId: String, limit: Int)

    @Query(
        "DELETE FROM whois_cache WHERE networkId = :networkId AND nick NOT IN " +
            "(SELECT nick FROM whois_cache WHERE networkId = :networkId ORDER BY observedAtMs DESC, nick ASC LIMIT :limit)",
    )
    public abstract suspend fun capUsers(networkId: String, limit: Int)


    @Query(
        "DELETE FROM launch_selection WHERE NOT EXISTS " +
            "(SELECT 1 FROM offline_conversations WHERE offline_conversations.networkId = launch_selection.networkId " +
            "AND offline_conversations.conversation = launch_selection.conversation) AND NOT EXISTS " +
            "(SELECT 1 FROM messages WHERE messages.networkId = launch_selection.networkId " +
            "AND messages.conversation = launch_selection.conversation)",
    )
    public abstract suspend fun removeMissingSelection()

    public open suspend fun restoreNotices() {
        for (row in evidenceNotices()) OfflineCodec.notice(row.networkId, row.identity, row.evidenceId)
    }

    private suspend fun quarantine(networkId: String, kind: String, identity: String, payloadJson: String, noticedAtMs: Long) {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$networkId\u0000$kind\u0000$identity\u0000$payloadJson".toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val evidenceId = buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 255
                append(hex[value ushr 4])
                append(hex[value and 15])
            }
        }
        putEvidence(OfflineQuarantineRow(evidenceId, networkId, kind, identity, payloadJson, noticedAtMs))
        OfflineCodec.notice(networkId, identity, evidenceId)
    }

    private suspend fun recoverConversation(row: OfflineConversationRow, noticedAtMs: Long): OfflineConversationRow? {
        val validIdentity = OfflineCodec.decodeOrNull {
            ConversationKind.valueOf(row.kind)
            requireNotNull(CaseMapping.fromWireName(row.caseMapping))
        } != null
        val validModes = OfflineCodec.decodeOrNull {
            OfflineCodec.json.decodeFromString<Map<String, List<String>>>(row.modesJson)
        } != null
        val validRoster = row.rosterJson == null || OfflineCodec.decodeOrNull { OfflineCodec.decodeRoster(row.rosterJson) } != null
        if (validIdentity && validModes && validRoster) return row
        quarantine(row.networkId, "conversation", row.conversation, OfflineCodec.json.encodeToString(row), noticedAtMs)
        if (!validIdentity) {
            removeConversation(row.networkId, row.conversation)
            return null
        }
        val healed = row.copy(
            modesJson = if (validModes) row.modesJson else "{}",
            modesObservedAtMs = if (validModes) row.modesObservedAtMs else null,
            rosterJson = if (validRoster) row.rosterJson else null,
            rosterObservedAtMs = if (validRoster) row.rosterObservedAtMs else null,
        )
        putConversation(healed)
        return healed
    }

    private suspend fun recoverUser(row: WhoisCacheRow, noticedAtMs: Long): WhoisCacheRow? {
        val validIdentity = CaseMapping.fromWireName(row.caseMapping) != null
        val validWhois = row.whoisJson == null || OfflineCodec.decodeOrNull {
            OfflineCodec.json.decodeFromString<WhoisInfo>(row.whoisJson)
        } != null
        val validUser = row.userJson == null || OfflineCodec.decodeOrNull {
            OfflineCodec.json.decodeFromString<CachedUser>(row.userJson)
        } != null
        if (validIdentity && validWhois && validUser) return row
        quarantine(row.networkId, "user", row.nick, OfflineCodec.json.encodeToString(row), noticedAtMs)
        if (!validIdentity) {
            removeUser(row.networkId, row.nick)
            return null
        }
        val healed = row.copy(
            whoisJson = if (validWhois) row.whoisJson else null,
            whoisObservedAtMs = if (validWhois) row.whoisObservedAtMs else null,
            userJson = if (validUser) row.userJson else null,
            userObservedAtMs = if (validUser) row.userObservedAtMs else null,
        )
        if (healed.whoisJson == null && healed.userJson == null) {
            removeUser(row.networkId, row.nick)
            return null
        }
        putUser(healed)
        return healed
    }

    private suspend fun recoverShell(row: OfflineShellRow): OfflineShellRow? {
        val valid = OfflineCodec.decodeOrNull {
            ConversationKind.valueOf(row.kind)
            requireNotNull(CaseMapping.fromWireName(row.caseMapping))
            OfflineCodec.json.decodeFromString<Map<String, List<String>>>(row.modesJson)
        } != null
        if (valid) return row
        val original = conversation(row.networkId, row.conversation) ?: return null
        val healed = recoverConversation(original, System.currentTimeMillis()) ?: return null
        return OfflineShellRow(healed.networkId, healed.conversation, healed.rawTarget, healed.kind,
            healed.displayName, healed.caseMapping, healed.observedAtMs, healed.topic, healed.topicObservedAtMs,
            healed.modesJson, healed.modesObservedAtMs)
    }

    @Transaction
    public open suspend fun loadShells(networkId: String, limit: Int): List<OfflineShellRow> =
        shells(networkId, limit).mapNotNull { recoverShell(it) }

    @Transaction
    public open suspend fun loadShell(networkId: String, conversation: String): OfflineShellRow? =
        shell(networkId, conversation)?.let { recoverShell(it) }

    @Transaction
    public open suspend fun loadRoster(networkId: String, conversation: String): String? =
        conversation(networkId, conversation)?.let { recoverConversation(it, System.currentTimeMillis())?.rosterJson }

    @Transaction
    public open suspend fun loadUser(networkId: String, nick: String): WhoisCacheRow? =
        cachedUser(networkId, nick)?.let { recoverUser(it, System.currentTimeMillis()) }

    @Transaction
    public open suspend fun loadSelection(): LaunchSelectionRow? {
        restoreNotices()
        val row = selection() ?: return null
        if (OfflineCodec.decodeOrNull { ConversationKind.valueOf(row.kind) } != null) return row
        quarantine(row.networkId, "selection", row.conversation, OfflineCodec.json.encodeToString(row), System.currentTimeMillis())
        clearSelection()
        return null
    }

    @Transaction
    public open suspend fun saveConversation(row: OfflineConversationRow) {
        val previous = conversation(row.networkId, row.conversation)?.let { recoverConversation(it, System.currentTimeMillis()) }
        putConversation(if (previous == null) row else OfflineCodec.mergeConversation(previous, row))
        removeMissingSelection()
    }

    @Transaction
    public open suspend fun saveUser(row: WhoisCacheRow) {
        val previous = cachedUser(row.networkId, row.nick)?.let { recoverUser(it, System.currentTimeMillis()) }
        putUser(if (previous == null) row else OfflineCodec.mergeUser(previous, row))
    }

    @Transaction
    public open suspend fun select(row: LaunchSelectionRow?) {
        val previous = loadSelection()
        if (row == null) {
            clearSelection()
        } else if (previous?.observedAtMs?.let { it > row.observedAtMs } != true) {
            putSelection(row)
        }
    }

    @Transaction
    public open suspend fun deleteConversation(networkId: String, conversation: String) {
        removeConversation(networkId, conversation)
        val selected = selection()
        if (selected?.networkId == networkId && selected.conversation == conversation) clearSelection()
    }

    @Transaction
    public open suspend fun deleteNetwork(networkId: String) {
        removeConversations(networkId)
        removeUsers(networkId)
        removeEvidence(networkId)
        if (selection()?.networkId == networkId) clearSelection()
    }

    @Transaction
    public open suspend fun renameConversation(from: ConversationRef, to: ConversationRef, mapping: CaseMapping) {
        require(from.networkId == to.networkId)
        val source = conversation(from.networkId, from.normalizedTarget)?.let { recoverConversation(it, System.currentTimeMillis()) }
        val destination = conversation(to.networkId, to.normalizedTarget)?.let { recoverConversation(it, System.currentTimeMillis()) }
        if (source != null) {
            val renamed = source.copy(
                conversation = to.normalizedTarget,
                rawTarget = to.rawTarget,
                kind = to.kind.name,
                displayName = if (source.displayName == source.rawTarget) to.rawTarget else source.displayName,
                caseMapping = mapping.wireName,
            )
            val merged = if (destination == null || from.normalizedTarget == to.normalizedTarget) renamed
            else OfflineCodec.mergeConversation(destination, renamed, collision = true)
            removeConversation(from.networkId, from.normalizedTarget)
            putConversation(merged.copy(rawTarget = to.rawTarget, kind = to.kind.name, caseMapping = mapping.wireName))
        }
        val selected = loadSelection()
        if (selected?.networkId == from.networkId && selected.conversation == from.normalizedTarget) {
            putSelection(selected.copy(conversation = to.normalizedTarget, rawTarget = to.rawTarget, kind = to.kind.name))
        }
        if (from.kind == ConversationKind.DIRECT_MESSAGE && to.kind == ConversationKind.DIRECT_MESSAGE) {
            renameUser(from.networkId, from.rawTarget, to.rawTarget, mapping)
        }
        removeMissingSelection()
    }

    @Transaction
    public open suspend fun renameUser(networkId: String, oldNick: String, newNick: String, mapping: CaseMapping) {
        val oldKey = mapping.fold(oldNick)
        val newKey = mapping.fold(newNick)
        val source = cachedUser(networkId, oldKey)?.let { recoverUser(it, System.currentTimeMillis()) }
        val destination = cachedUser(networkId, newKey)?.let { recoverUser(it, System.currentTimeMillis()) }
        if (source != null) {
            val renamed = OfflineCodec.renameUser(source, newNick, mapping)
            val merged = if (destination == null || oldKey == newKey) renamed else OfflineCodec.mergeUser(destination, renamed)
            removeUser(networkId, oldKey)
            putUser(OfflineCodec.renameUser(merged, newNick, mapping))
        }
        for (row in rosterRows(networkId)) {
            val roster = OfflineCodec.decodeOrNull { OfflineCodec.decodeRoster(row.rosterJson) }
                ?: conversation(networkId, row.conversation)?.let {
                    recoverConversation(it, System.currentTimeMillis())?.rosterJson?.let(OfflineCodec::decodeRoster)
                } ?: continue
            val renamed = OfflineCodec.renameRoster(roster, oldNick, newNick, mapping)
            if (renamed !== roster) updateRoster(networkId, row.conversation, OfflineCodec.encodeRoster(renamed))
        }
    }

    @Transaction
    public open suspend fun rekeyNetwork(networkId: String, mapping: CaseMapping) {
        val conversations = conversationRows(networkId).mapNotNull { recoverConversation(it, System.currentTimeMillis()) }
        val users = userRows(networkId).mapNotNull { recoverUser(it, System.currentTimeMillis()) }
        val selected = loadSelection()
        for (row in conversations) removeConversation(networkId, row.conversation)
        for (row in users) removeUser(networkId, row.nick)
        val mergedConversations = linkedMapOf<String, OfflineConversationRow>()
        for (row in conversations.sortedBy { it.observedAtMs }) {
            val key = if (row.kind == ConversationKind.SERVER.name) ConversationRef.SERVER_TARGET else mapping.fold(row.rawTarget)
            val rekeyed = row.copy(
                conversation = key,
                caseMapping = mapping.wireName,
                rosterJson = row.rosterJson?.let { OfflineCodec.encodeRoster(OfflineCodec.boundRoster(OfflineCodec.decodeRoster(it), mapping)) },
            )
            mergedConversations[key] = mergedConversations[key]?.let { OfflineCodec.mergeConversation(it, rekeyed, collision = true) } ?: rekeyed
        }
        for (row in mergedConversations.values) putConversation(row)
        val mergedUsers = linkedMapOf<String, WhoisCacheRow>()
        for (row in users.sortedBy { it.observedAtMs }) {
            val rekeyed = OfflineCodec.renameUser(row, row.rawNick, mapping)
            mergedUsers[rekeyed.nick] = mergedUsers[rekeyed.nick]?.let { OfflineCodec.mergeUser(it, rekeyed) } ?: rekeyed
        }
        for (row in mergedUsers.values) putUser(row)
        if (selected?.networkId == networkId) {
            val source = conversations.firstOrNull { it.conversation == selected.conversation }
            val rawTarget = source?.rawTarget ?: selected.rawTarget
            val key = if (selected.kind == ConversationKind.SERVER.name) ConversationRef.SERVER_TARGET else mapping.fold(rawTarget)
            val surviving = mergedConversations[key]
            putSelection(selected.copy(conversation = key, rawTarget = surviving?.rawTarget ?: rawTarget,
                kind = surviving?.kind ?: selected.kind))
        }
        removeMissingSelection()
    }

    @Transaction
    public open suspend fun maintain(nowMs: Long) {
        val cutoff = if (nowMs < Long.MIN_VALUE + OfflineCachePolicy.RETENTION_MS) Long.MIN_VALUE
        else nowMs - OfflineCachePolicy.RETENTION_MS
        expireConversations(cutoff)
        expireTopics(cutoff)
        expireModes(cutoff)
        expireRosters(cutoff)
        expireWhois(cutoff)
        expireUserMetadata(cutoff)
        removeEmptyUsers()
        for (network in networks()) {
            capConversations(network, OfflineCachePolicy.MAX_CONVERSATIONS_PER_NETWORK)
            capUsers(network, OfflineCachePolicy.MAX_USERS_PER_NETWORK)
        }
        loadSelection()
        removeMissingSelection()
    }
}

internal fun createOfflineTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS offline_conversations (networkId TEXT NOT NULL, conversation TEXT NOT NULL, " +
            "rawTarget TEXT NOT NULL, kind TEXT NOT NULL, displayName TEXT NOT NULL, caseMapping TEXT NOT NULL, " +
            "observedAtMs INTEGER NOT NULL, topic TEXT, topicObservedAtMs INTEGER, modesJson TEXT NOT NULL, " +
            "modesObservedAtMs INTEGER, rosterJson TEXT, rosterObservedAtMs INTEGER, PRIMARY KEY(networkId, conversation))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_conversations_networkId_observedAtMs ON offline_conversations(networkId, observedAtMs)")
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS whois_cache (networkId TEXT NOT NULL, nick TEXT NOT NULL, rawNick TEXT NOT NULL, " +
            "caseMapping TEXT NOT NULL, observedAtMs INTEGER NOT NULL, whoisJson TEXT, whoisObservedAtMs INTEGER, " +
            "userJson TEXT, userObservedAtMs INTEGER, PRIMARY KEY(networkId, nick))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_whois_cache_networkId_observedAtMs ON whois_cache(networkId, observedAtMs)")
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS launch_selection (slot INTEGER NOT NULL, networkId TEXT NOT NULL, conversation TEXT NOT NULL, " +
            "rawTarget TEXT NOT NULL, kind TEXT NOT NULL, observedAtMs INTEGER NOT NULL, PRIMARY KEY(slot))",
    )
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS offline_quarantine (evidenceId TEXT NOT NULL, networkId TEXT NOT NULL, " +
            "kind TEXT NOT NULL, identity TEXT NOT NULL, payloadJson TEXT NOT NULL, noticedAtMs INTEGER NOT NULL, PRIMARY KEY(evidenceId))",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_offline_quarantine_networkId_noticedAtMs ON offline_quarantine(networkId, noticedAtMs)")
}
