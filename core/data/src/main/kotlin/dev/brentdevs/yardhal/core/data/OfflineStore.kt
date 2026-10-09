package dev.brentdevs.yardhal.core.data

import dev.brentdevs.yardhal.core.protocol.CaseMapping
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public class OfflineStore(private val dao: OfflineDao) {
    public suspend fun shells(
        networkId: String? = null,
        limit: Int = OfflineCachePolicy.MAX_CONVERSATIONS_PER_NETWORK,
    ): List<OfflineConversationShell> {
        dao.restoreNotices()
        val boundedLimit = limit.coerceIn(0, OfflineCachePolicy.MAX_CONVERSATIONS_PER_NETWORK)
        val networks = networkId?.let { listOf(it) } ?: dao.shellNetworks()
        return buildList {
            for (network in networks) {
                for (row in dao.loadShells(network, boundedLimit)) add(row.toShell())
            }
        }
    }

    public suspend fun shell(ref: ConversationRef): OfflineConversationShell? {
        dao.restoreNotices()
        return dao.loadShell(ref.networkId, ref.normalizedTarget)?.toShell()
    }

    private fun OfflineShellRow.toShell(): OfflineConversationShell = OfflineConversationShell(
        ref = ConversationRef(networkId, ConversationKind.valueOf(kind), rawTarget, conversation),
        displayName = displayName,
        observedAtMs = observedAtMs,
        caseMapping = requireNotNull(CaseMapping.fromWireName(caseMapping)),
        topic = topic,
        topicObservedAtMs = topicObservedAtMs,
        modes = OfflineCodec.json.decodeFromString(modesJson),
        modesObservedAtMs = modesObservedAtMs,
    )

    public suspend fun selection(): ConversationRef? = dao.loadSelection()?.let { row ->
        ConversationRef(row.networkId, ConversationKind.valueOf(row.kind), row.rawTarget, row.conversation)
    }

    public suspend fun select(ref: ConversationRef?, observedAtMs: Long) {
        dao.select(ref?.let { LaunchSelectionRow(networkId = it.networkId, conversation = it.normalizedTarget,
            rawTarget = it.rawTarget, kind = it.kind.name, observedAtMs = observedAtMs) })
    }

    public suspend fun saveConversation(shell: OfflineConversationShell, roster: OfflineRoster? = null) {
        val boundedRoster = roster?.let { OfflineCodec.boundRoster(it, shell.caseMapping) }
        dao.saveConversation(
            OfflineConversationRow(
                networkId = shell.ref.networkId,
                conversation = shell.ref.normalizedTarget,
                rawTarget = shell.ref.rawTarget,
                kind = shell.ref.kind.name,
                displayName = shell.displayName,
                caseMapping = shell.caseMapping.wireName,
                observedAtMs = maxOf(shell.observedAtMs, shell.topicObservedAtMs ?: Long.MIN_VALUE,
                    shell.modesObservedAtMs ?: Long.MIN_VALUE, roster?.observedAtMs ?: Long.MIN_VALUE),
                topic = shell.topic,
                topicObservedAtMs = shell.topicObservedAtMs,
                modesJson = OfflineCodec.json.encodeToString(shell.modes),
                modesObservedAtMs = shell.modesObservedAtMs,
                rosterJson = boundedRoster?.let { OfflineCodec.encodeRoster(it) },
                rosterObservedAtMs = boundedRoster?.observedAtMs,
            ),
        )
    }

    public suspend fun roster(ref: ConversationRef): OfflineRoster? =
        dao.loadRoster(ref.networkId, ref.normalizedTarget)?.let { OfflineCodec.decodeRoster(it) }

    public suspend fun saveWhois(
        networkId: String,
        info: WhoisInfo,
        observedAtMs: Long,
        caseMapping: CaseMapping = CaseMapping.RFC1459,
    ) {
        dao.saveUser(WhoisCacheRow(networkId, caseMapping.fold(info.nick), info.nick, caseMapping.wireName,
            observedAtMs, OfflineCodec.json.encodeToString(info), observedAtMs, null, null))
    }

    public suspend fun whois(
        networkId: String,
        nick: String,
        caseMapping: CaseMapping = CaseMapping.RFC1459,
    ): CachedWhois? {
        val row = dao.loadUser(networkId, caseMapping.fold(nick)) ?: return null
        val json = row.whoisJson ?: return null
        val observedAtMs = row.whoisObservedAtMs ?: return null
        return CachedWhois(OfflineCodec.json.decodeFromString(json), observedAtMs)
    }

    public suspend fun saveUser(networkId: String, user: CachedUser, caseMapping: CaseMapping = CaseMapping.RFC1459) {
        dao.saveUser(WhoisCacheRow(networkId, caseMapping.fold(user.nick), user.nick, caseMapping.wireName,
            user.observedAtMs, null, null, OfflineCodec.json.encodeToString(user), user.observedAtMs))
    }

    public suspend fun user(
        networkId: String,
        nick: String,
        caseMapping: CaseMapping = CaseMapping.RFC1459,
    ): CachedUser? = dao.loadUser(networkId, caseMapping.fold(nick))?.userJson?.let {
        OfflineCodec.json.decodeFromString(it)
    }

    public suspend fun renameConversation(
        from: ConversationRef,
        to: ConversationRef,
        caseMapping: CaseMapping = CaseMapping.RFC1459,
    ) {
        dao.renameConversation(from, to, caseMapping)
    }

    public suspend fun renameUser(networkId: String, oldNick: String, newNick: String, caseMapping: CaseMapping = CaseMapping.RFC1459) {
        dao.renameUser(networkId, oldNick, newNick, caseMapping)
    }

    public suspend fun rekeyNetwork(networkId: String, caseMapping: CaseMapping) {
        dao.rekeyNetwork(networkId, caseMapping)
    }

    public suspend fun deleteConversation(ref: ConversationRef) {
        dao.deleteConversation(ref.networkId, ref.normalizedTarget)
    }

    public suspend fun deleteNetwork(networkId: String) {
        dao.deleteNetwork(networkId)
        StorageRecovery.clearScope("offline:$networkId")
    }

    public suspend fun maintain(nowMs: Long) {
        dao.maintain(nowMs)
    }
}

internal object OfflineCodec {
    val json: Json = Json { ignoreUnknownKeys = true }

    fun <T> decodeOrNull(decode: () -> T): T? = try {
        decode()
    } catch (_: IllegalArgumentException) {
        null
    }

    fun notice(networkId: String, identity: String, evidenceId: String) {
        StorageRecovery.report(StorageRecoveryNotice(
            message = "Saved cached metadata for $networkId|$identity could not be read. Original metadata is retained as database evidence; this information was not recovered.",
            quarantinePath = null,
            temporary = false,
            scopeKey = "offline:$networkId",
            evidenceKey = evidenceId,
        ))
    }

    fun encodeRoster(roster: OfflineRoster): String = json.encodeToString(roster)

    fun decodeRoster(source: String): OfflineRoster = json.decodeFromString(source)

    private fun newer(incoming: Long?, previous: Long?): Boolean = incoming != null && (previous == null || incoming >= previous)

    fun mergeConversation(
        previous: OfflineConversationRow,
        incoming: OfflineConversationRow,
        collision: Boolean = false,
    ): OfflineConversationRow {
        val newest = if (incoming.observedAtMs >= previous.observedAtMs) incoming else previous
        val topic = if (newer(incoming.topicObservedAtMs, previous.topicObservedAtMs)) incoming else previous
        val modes = if (newer(incoming.modesObservedAtMs, previous.modesObservedAtMs)) incoming else previous
        val mapping = CaseMapping.fromWireName(incoming.caseMapping) ?: CaseMapping.RFC1459
        val previousRoster = previous.rosterJson?.let { decodeRoster(it) }
        val incomingRoster = incoming.rosterJson?.let { decodeRoster(it) }
        val roster = when {
            incomingRoster == null -> previousRoster
            previousRoster == null -> incomingRoster
            collision -> mergeRoster(previousRoster, incomingRoster, mapping)
            !newer(incoming.rosterObservedAtMs, previous.rosterObservedAtMs) -> previousRoster
            incomingRoster.completeAtObservation -> incomingRoster
            else -> mergeRoster(previousRoster, incomingRoster, mapping)
        }
        return newest.copy(
            topic = topic.topic,
            topicObservedAtMs = topic.topicObservedAtMs,
            modesJson = modes.modesJson,
            modesObservedAtMs = modes.modesObservedAtMs,
            rosterJson = roster?.let { encodeRoster(boundRoster(it, mapping)) },
            rosterObservedAtMs = roster?.observedAtMs,
        )
    }

    fun mergeUser(previous: WhoisCacheRow, incoming: WhoisCacheRow): WhoisCacheRow {
        val newest = if (incoming.observedAtMs >= previous.observedAtMs) incoming else previous
        val whois = if (newer(incoming.whoisObservedAtMs, previous.whoisObservedAtMs)) incoming else previous
        val user = if (newer(incoming.userObservedAtMs, previous.userObservedAtMs)) incoming else previous
        return newest.copy(whoisJson = whois.whoisJson, whoisObservedAtMs = whois.whoisObservedAtMs,
            userJson = user.userJson, userObservedAtMs = user.userObservedAtMs)
    }

    fun boundRoster(roster: OfflineRoster, mapping: CaseMapping): OfflineRoster {
        val members = linkedMapOf<String, ChannelMember>()
        val memberTimes = linkedMapOf<String, Long>()
        for (member in roster.members) {
            val key = mapping.fold(member.nick)
            val observed = roster.memberObservedAtMs[member.nick] ?: roster.observedAtMs
            if (observed >= (memberTimes[key] ?: Long.MIN_VALUE)) {
                members[key] = member
                memberTimes[key] = observed
            }
        }
        val sorted = members.entries.sortedBy { it.key }
        val retained = sorted.take(OfflineCachePolicy.MAX_ROSTER_ENTRIES).map { it.value }
        val foldedPresence = linkedMapOf<String, CachedPresence>()
        val presenceTimes = linkedMapOf<String, Long>()
        for ((nick, state) in roster.presence) {
            val key = mapping.fold(nick)
            val observed = roster.presenceObservedAtMs[nick] ?: roster.observedAtMs
            if (observed >= (presenceTimes[key] ?: Long.MIN_VALUE)) {
                foldedPresence[key] = state
                presenceTimes[key] = observed
            }
        }
        val presence = retained.mapNotNull { member -> foldedPresence[mapping.fold(member.nick)]?.let { member.nick to it } }.toMap()
        val truncated = roster.truncated || sorted.size > OfflineCachePolicy.MAX_ROSTER_ENTRIES
        return roster.copy(members = retained, presence = presence, truncated = truncated,
            memberObservedAtMs = retained.associate { it.nick to (memberTimes[mapping.fold(it.nick)] ?: roster.observedAtMs) },
            presenceObservedAtMs = presence.keys.associateWith { presenceTimes[mapping.fold(it)] ?: roster.observedAtMs })
    }

    private fun mergeRoster(previous: OfflineRoster, incoming: OfflineRoster, mapping: CaseMapping): OfflineRoster {
        val older = if (incoming.observedAtMs >= previous.observedAtMs) previous else incoming
        val newer = if (incoming.observedAtMs >= previous.observedAtMs) incoming else previous
        val members = linkedMapOf<String, ChannelMember>()
        val presence = linkedMapOf<String, CachedPresence>()
        val memberTimes = linkedMapOf<String, Long>()
        val presenceTimes = linkedMapOf<String, Long>()
        for (snapshot in listOf(older, newer)) {
            for (member in snapshot.members) {
                val key = mapping.fold(member.nick)
                val observed = snapshot.memberObservedAtMs[member.nick] ?: snapshot.observedAtMs
                if (observed >= (memberTimes[key] ?: Long.MIN_VALUE)) {
                    members[key] = member
                    memberTimes[key] = observed
                }
            }
            for ((nick, state) in snapshot.presence) {
                val key = mapping.fold(nick)
                val observed = snapshot.presenceObservedAtMs[nick] ?: snapshot.observedAtMs
                if (observed >= (presenceTimes[key] ?: Long.MIN_VALUE)) {
                    presence[key] = state
                    presenceTimes[key] = observed
                }
            }
        }
        val byNick = members.values.mapNotNull { member -> presence[mapping.fold(member.nick)]?.let { member.nick to it } }.toMap()
        return boundRoster(OfflineRoster(members.values.toList(), byNick, newer.observedAtMs,
            completeAtObservation = false, truncated = older.truncated || newer.truncated,
            memberObservedAtMs = members.values.associate { it.nick to memberTimes.getValue(mapping.fold(it.nick)) },
            presenceObservedAtMs = byNick.keys.associateWith { presenceTimes.getValue(mapping.fold(it)) }), mapping)
    }

    fun renameUser(row: WhoisCacheRow, nick: String, mapping: CaseMapping): WhoisCacheRow = row.copy(
        nick = mapping.fold(nick), rawNick = nick, caseMapping = mapping.wireName,
        whoisJson = row.whoisJson?.let { json.encodeToString(json.decodeFromString<WhoisInfo>(it).copy(nick = nick)) },
        userJson = row.userJson?.let { json.encodeToString(json.decodeFromString<CachedUser>(it).copy(nick = nick)) },
    )

    fun renameRoster(roster: OfflineRoster, oldNick: String, newNick: String, mapping: CaseMapping): OfflineRoster {
        val oldKey = mapping.fold(oldNick)
        if (roster.members.none { mapping.fold(it.nick) == oldKey } && roster.presence.keys.none { mapping.fold(it) == oldKey }) return roster
        val sourceMembers = roster.members.filter { mapping.fold(it.nick) == oldKey }
        val sourcePresence = roster.presence.filterKeys { mapping.fold(it) == oldKey }
        val source = roster.copy(
            members = sourceMembers.map { it.copy(nick = newNick) },
            presence = sourcePresence.values.firstOrNull()?.let { mapOf(newNick to it) }.orEmpty(),
            memberObservedAtMs = sourceMembers.firstOrNull()?.let {
                mapOf(newNick to (roster.memberObservedAtMs[it.nick] ?: roster.observedAtMs))
            }.orEmpty(),
            presenceObservedAtMs = sourcePresence.keys.firstOrNull()?.let {
                mapOf(newNick to (roster.presenceObservedAtMs[it] ?: roster.observedAtMs))
            }.orEmpty(),
        )
        val remaining = roster.copy(
            members = roster.members.filter { mapping.fold(it.nick) != oldKey },
            presence = roster.presence.filterKeys { mapping.fold(it) != oldKey },
        )
        return mergeRoster(remaining, source, mapping).copy(completeAtObservation = roster.completeAtObservation)
    }

}
