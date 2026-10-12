package dev.brentdevs.yardhal.sync

import dev.brentdevs.yardhal.coordinator.LiveCoordinator
import dev.brentdevs.yardhal.core.data.BouncerBinding
import dev.brentdevs.yardhal.core.data.ChannelGroup
import dev.brentdevs.yardhal.core.data.ChannelOrderState
import dev.brentdevs.yardhal.core.data.ChannelOrderStore
import dev.brentdevs.yardhal.core.data.ChatAppearanceStore
import dev.brentdevs.yardhal.core.data.ConversationAccentPreference
import dev.brentdevs.yardhal.core.data.DerivedNetworkIdentity
import dev.brentdevs.yardhal.core.data.LibraryTheme
import dev.brentdevs.yardhal.core.data.MuteStore
import dev.brentdevs.yardhal.core.data.NetworkConfig
import dev.brentdevs.yardhal.core.data.NetworkMode
import dev.brentdevs.yardhal.core.data.NetworkStore
import dev.brentdevs.yardhal.core.data.ReadMarkerStore
import dev.brentdevs.yardhal.core.data.SaslMode
import dev.brentdevs.yardhal.core.data.SyncLedger
import dev.brentdevs.yardhal.core.data.SyncMaterialization
import dev.brentdevs.yardhal.core.data.SyncProjection
import dev.brentdevs.yardhal.core.data.ThemeDefinition
import dev.brentdevs.yardhal.core.data.ThemeLibraryStore
import dev.brentdevs.yardhal.core.data.ThemeVariants
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long

public class PortableSyncState(
    private val networkStore: NetworkStore,
    private val channelOrder: ChannelOrderStore,
    private val muteStore: MuteStore,
    private val readMarkerStore: ReadMarkerStore,
    private val themeLibrary: ThemeLibraryStore,
    private val chatAppearanceStore: ChatAppearanceStore,
    private val coordinator: LiveCoordinator,
) {
    private val mutex = Mutex()
    private val scopedKeyProbe = SyncProjection.read(0)
    private var stagedOrders: Map<String, String?> = emptyMap()
    private var stagedNetworks: Set<String> = emptySet()
    private var stagedThemes: Map<String, JsonElement> = emptyMap()
    private var stagedDeletedThemes: Set<String> = emptySet()
    private var stagedGroups: Map<String, JsonElement> = emptyMap()

    public fun stage(changes: List<SyncMaterialization>) {
        stagedOrders = changes.filter { it.kind == "order" && it.value != null && it.value != JsonNull }
            .associate { change -> change.key to (change.value as JsonObject)["after"].stringOrNull() }
        stagedNetworks = changes.filter { it.kind == "network" && it.value != null && it.value != JsonNull }
            .mapTo(mutableSetOf()) { it.key }
        stagedThemes = changes.filter { it.kind == "theme" && it.value != null && it.value != JsonNull }
            .associate { it.key to requireNotNull(it.value) }
        stagedDeletedThemes = changes.filter { it.kind == "theme" && (it.value == null || it.value == JsonNull) }
            .mapTo(mutableSetOf()) { it.key }
        stagedGroups = changes.filter { it.kind == "group" && it.value != null && it.value != JsonNull }
            .associate { it.key to requireNotNull(it.value) }
    }

    public suspend fun snapshot(): Map<String, Map<String, JsonElement?>> = mutex.withLock {
        val networks = networkStore.all()
        val knownIds = networks.mapTo(mutableSetOf()) { it.id }
        val order = channelOrder.snapshot()
        val themes = themeLibrary.snapshot()
        val result = linkedMapOf<String, Map<String, JsonElement?>>()
        result["network"] = networks.associate { it.id to SyncProjection.network(it) }
        val ordering = linkedMapOf<String, JsonElement?>()
        fun addOrdering(prefix: String, ids: List<String>) {
            ids.forEachIndexed { index, id ->
                ordering["$prefix/$id"] = obj("after" to text(ids.getOrNull(index - 1)?.let { "$prefix/$it" }))
            }
        }
        addOrdering("network", order.networkOrder.filter { it in knownIds })
        addOrdering("group", order.groupOrder.filter { id -> order.groups.any { it.id == id } })
        val explicitChannels = order.channelOrder.toSet()
        val orderedChannels = listOf(order.pinnedKeys) + order.groups.map { it.memberKeys } + listOf(order.channelOrder)
        val emitted = mutableSetOf<String>()
        for (section in orderedChannels) {
            val byNetwork = section.filter { emitted.add(it) }
                .mapNotNull { storage ->
                    conversationFromStorage(storage, knownIds)?.let { (network, key) -> network to (storage to key) }
                }
                .groupBy({ it.first }, { it.second })
            for ((_, peers) in byNetwork) {
                peers.forEachIndexed { index, (storage, key) ->
                    if (storage in explicitChannels) {
                        ordering["channel/$key"] = obj("after" to text(
                            peers.getOrNull(index - 1)?.second?.let { "channel/$it" },
                        ))
                    }
                }
            }
        }
        result["order"] = ordering
        result["group"] = order.groups.associate { it.id to obj("name" to JsonPrimitive(it.name)) }
        result["pin"] = order.pinnedKeys.mapNotNull { conversationFromStorage(it, knownIds)?.second }
            .associateWith { obj("enabled" to JsonPrimitive(true)) }
        result["membership"] = order.groups.flatMap { group -> group.memberKeys.map { it to group.id } }
            .mapNotNull { (storage, groupId) -> conversationFromStorage(storage, knownIds)?.second?.let { it to groupId } }
            .associate { (key, groupId) -> key to obj("group" to JsonPrimitive(groupId)) }
        result["mute"] = muteStore.all().mapNotNull { conversationFromStorage(it, knownIds)?.second }
            .associateWith { obj("enabled" to JsonPrimitive(true)) }
        result["theme"] = themes.themes.associate { it.id to themeValue(it.variants) }
        result["selection"] = mapOf("global" to obj("id" to text(themes.selectedId)))
        result["accent"] = themes.accents.mapNotNull { accent ->
            conversationFromStorage("${accent.networkId}|${accent.normalizedTarget}", knownIds)?.second?.let {
                it to obj("color" to JsonPrimitive(accent.color))
            }
        }.toMap()
        result["avatar"] = mapOf("global" to obj("show" to JsonPrimitive(chatAppearanceStore.snapshot().showAvatars)))
        result["read"] = readMarkerStore.all().mapNotNull { (storage, timestamp) ->
            if (timestamp <= 0) null else conversationFromStorage(storage, knownIds)?.second?.let { it to timestamp }
        }.associate { (key, timestamp) -> key to SyncProjection.read(timestamp) }
        result
    }

    public suspend fun materialize(change: SyncMaterialization): Unit = mutex.withLock {
        SyncProjection.requireSafe(change.kind, change.key, change.value)
        val value = change.value?.takeUnless { it == JsonNull } as? JsonObject
        when (change.kind) {
            "network" -> {
                if (value == null) {
                    check(coordinator.removeSyncedNetwork(change.key)) { "Could not remove synced network" }
                } else {
                    val prior = networkStore.byId(change.key)
                    val updated = network(value, prior)
                    check(if (prior == null) networkStore.add(updated) else coordinator.updateNetwork(updated, preserveParted = true)) {
                        "Could not save synced network"
                    }
                    coordinator.connect(updated)
                }
                applyStagedOrders()
            }
            "order" -> {
                val key = change.key
                val orders = if (value == null) stagedOrders - key
                    else stagedOrders + (key to value["after"].stringOrNull())
                val state = if (value == null) removeOrder(channelOrder.snapshot(), key) else channelOrder.snapshot()
                channelOrder.applySyncState(mergeStagedOrders(state, orders))
            }
            "group" -> {
                val state = channelOrder.snapshot()
                val groups = if (value == null) state.groups.filterNot { it.id == change.key }
                else if (state.groups.any { it.id == change.key }) state.groups.map {
                    if (it.id == change.key) it.copy(name = value.string("name")) else it
                } else state.groups + ChannelGroup(change.key, value.string("name"))
                val next = state.copy(groups = groups, groupOrder = if (value == null) state.groupOrder - change.key
                    else if ("group/${change.key}" in stagedOrders) (state.groupOrder + change.key).distinct()
                    else state.groupOrder)
                channelOrder.applySyncState(mergeStagedOrders(next, stagedOrders))
            }
            "pin" -> {
                val storage = storageKey(change.key)
                if (knownNetwork(storage.substringBefore('|'))) {
                    val state = channelOrder.snapshot()
                    val enabled = value?.bool("enabled") == true
                    channelOrder.applySyncState(mergeStagedOrders(state.copy(
                        pinnedKeys = if (enabled) insertRanked(state.pinnedKeys, storage, state.channelOrder)
                            else state.pinnedKeys - storage,
                    ), stagedOrders))
                }
            }
            "membership" -> {
                val storage = storageKey(change.key)
                if (knownNetwork(storage.substringBefore('|'))) {
                    val groupId = value?.get("group")?.stringOrNull()
                    val current = channelOrder.snapshot()
                    val pending = groupId?.let { stagedGroups[it] as? JsonObject }
                    val state = if (groupId != null && current.groups.none { it.id == groupId } && pending != null) {
                        current.copy(groups = current.groups + ChannelGroup(groupId, pending.string("name")))
                    } else current
                    val groups = state.groups.map { group ->
                        group.copy(memberKeys = if (group.id == groupId) insertRanked(group.memberKeys, storage, state.channelOrder)
                            else group.memberKeys - storage)
                    }
                    channelOrder.applySyncState(mergeStagedOrders(state.copy(groups = groups), stagedOrders))
                }
            }
            "mute" -> {
                val storage = storageKey(change.key)
                if (knownNetwork(storage.substringBefore('|'))) {
                    if (value?.bool("enabled") == true) muteStore.mute(storage) else muteStore.unmute(storage)
                }
            }
            "theme" -> {
                val state = themeLibrary.snapshot()
                val themes = if (value == null) state.themes.filterNot { it.id == change.key }
                else (state.themes.filterNot { it.id == change.key } + LibraryTheme(change.key, themeVariants(value)))
                themeLibrary.applySyncState(state.copy(themes = themes,
                    selectedId = state.selectedId?.takeIf { id -> themes.any { it.id == id } }))
            }
            "selection" -> {
                val id = value?.get("id")?.stringOrNull()
                val current = themeLibrary.snapshot()
                val pending = id?.takeIf { key -> key !in stagedDeletedThemes && current.themes.none { it.id == key } }
                    ?.let { key -> (stagedThemes[key] as? JsonObject)?.let { LibraryTheme(key, themeVariants(it)) } }
                val themes = if (pending == null) current.themes else current.themes + pending
                val selectedId = id?.takeIf { key -> key !in stagedDeletedThemes && themes.any { it.id == key } }
                themeLibrary.applySyncState(current.copy(themes = themes, selectedId = selectedId))
            }
            "accent" -> {
                val storage = storageKey(change.key)
                if (knownNetwork(storage.substringBefore('|'))) {
                    val state = themeLibrary.snapshot()
                    val accents = state.accents.filterNot {
                        it.networkId == storage.substringBefore('|') && it.normalizedTarget == storage.substringAfter('|')
                    } + if (value == null) emptyList() else listOf(ConversationAccentPreference(
                        storage.substringBefore('|'), storage.substringAfter('|'), value.number("color"),
                    ))
                    themeLibrary.applySyncState(state.copy(accents = accents))
                }
            }
            "avatar" -> chatAppearanceStore.update { preferences ->
                preferences.copy(showAvatars = value?.bool("show") ?: true)
            }
            "read" -> {
                val storage = storageKey(change.key)
                if (value != null && knownNetwork(storage.substringBefore('|'))) {
                    readMarkerStore.reconcileRemote(storage, value.number("timestampMs"))
                }
            }
            "reply", "reaction", "redaction", "attachment" -> Unit
            else -> error("Unsupported sync kind")
        }
    }

    private fun knownNetwork(id: String): Boolean = networkStore.byId(id) != null || id in stagedNetworks

    private fun network(value: JsonObject, prior: NetworkConfig?): NetworkConfig {
        val parent = value["bouncerParentId"].stringOrNull()
        val netId = value["bouncerNetId"].stringOrNull()
        require((parent == null) == (netId == null)) { "Incomplete bouncer binding" }
        if (parent != null && netId != null) {
            require(DerivedNetworkIdentity.sojuNetworkId(parent, netId) == value.string("id")) {
                "Synced bouncer network ID does not match its upstream identity"
            }
        }
        val oldBinding = prior?.bouncerBinding
        val binding = if (parent == null || netId == null) null else if (
            oldBinding?.parentId == parent && oldBinding.netId == netId
        ) oldBinding else BouncerBinding(parent, netId, enabled = false)
        return NetworkConfig(
            id = value.string("id"), name = value.string("name"), host = value.string("host"),
            port = value.integer("port"), tls = value.bool("tls"), nick = value.string("nick"),
            username = value.string("username"), realName = value.string("realName"),
            autojoin = value.strings("autojoin"), alternateNicks = value.strings("alternateNicks"),
            saslAuthcid = value["saslAuthcid"].stringOrNull(),
            saslPasswordRef = prior?.saslPasswordRef, serverPasswordRef = prior?.serverPasswordRef,
            saslPassword = prior?.saslPassword,
            autoConnect = prior?.autoConnect ?: false, userDisconnected = prior?.userDisconnected ?: true,
            saslMode = SaslMode.valueOf(value.string("saslMode")),
            nickServAccount = value["nickServAccount"].stringOrNull(),
            nickServPasswordRef = prior?.nickServPasswordRef,
            nickServService = value.string("nickServService"), waitForNickServ = value.bool("waitForNickServ"),
            proxy = prior?.proxy, tlsClientAlias = prior?.tlsClientAlias,
            certificatePin = prior?.certificatePin, mode = NetworkMode.valueOf(value.string("mode")),
            bouncerBinding = binding, zncNetwork = value["zncNetwork"].stringOrNull(),
            serverPassword = prior?.serverPassword, nickServPassword = prior?.nickServPassword,
            proxyPassword = prior?.proxyPassword, tlsClientIdentity = prior?.tlsClientIdentity,
            knownSecrets = prior?.knownSecrets.orEmpty(),
        )
    }

    private fun applyStagedOrders() {
        if (stagedOrders.isNotEmpty()) {
            channelOrder.applySyncState(mergeStagedOrders(channelOrder.snapshot(), stagedOrders))
        }
    }

    private fun mergeStagedOrders(state: ChannelOrderState, orders: Map<String, String?>): ChannelOrderState {
        var next = state
        for (key in orders.keys) {
            if (key.startsWith("group/")) {
                val id = key.removePrefix("group/")
                val pending = stagedGroups[id] as? JsonObject
                if (pending != null && next.groups.none { it.id == id }) {
                    next = next.copy(groups = next.groups + ChannelGroup(id, pending.string("name")))
                }
            }
            next = insertOrder(next, key)
        }
        return reorder(next, orders)
    }

    private fun insertRanked(existing: List<String>, key: String, channelRank: List<String>): List<String> {
        if (key in existing) return existing
        val rank = channelRank.indexOf(key).let { if (it < 0) Int.MAX_VALUE else it }
        val index = existing.indexOfFirst { other ->
            val otherRank = channelRank.indexOf(other).let { if (it < 0) Int.MAX_VALUE else it }
            otherRank > rank
        }
        return existing.toMutableList().also { it.add(if (index < 0) it.size else index, key) }
    }

    private fun insertOrder(state: ChannelOrderState, key: String): ChannelOrderState {
        val parts = key.split('/')
        return when (parts[0]) {
            "network" -> if (knownNetwork(parts[1])) state.copy(networkOrder = (state.networkOrder + parts[1]).distinct()) else state
            "group" -> if (state.groups.any { it.id == parts[1] })
                state.copy(groupOrder = (state.groupOrder + parts[1]).distinct()) else state
            else -> {
                val storage = storageKey(parts[1] + "/" + parts[2])
                if (!knownNetwork(parts[1])) state else state.copy(channelOrder = (state.channelOrder + storage).distinct())
            }
        }
    }

    private fun removeOrder(state: ChannelOrderState, key: String): ChannelOrderState {
        val parts = key.split('/')
        return when (parts[0]) {
            "network" -> state.copy(networkOrder = state.networkOrder - parts[1])
            "group" -> state.copy(groupOrder = state.groupOrder - parts[1])
            else -> {
                val storage = storageKey(parts[1] + "/" + parts[2])
                state.copy(channelOrder = state.channelOrder - storage)
            }
        }
    }

    private fun reorder(state: ChannelOrderState, orders: Map<String, String?>): ChannelOrderState {
        val networkAnchors = orders.filterKeys { it.startsWith("network/") }.mapKeys { it.key.removePrefix("network/") }
            .mapValues { it.value?.removePrefix("network/") }
        val groupAnchors = orders.filterKeys { it.startsWith("group/") }.mapKeys { it.key.removePrefix("group/") }
            .mapValues { it.value?.removePrefix("group/") }
        val channelAnchors = orders.filterKeys { it.startsWith("channel/") }.mapNotNull { (key, after) ->
            val storage = runCatching { storageKey(key.removePrefix("channel/")) }.getOrNull()
            storage?.let { it to after?.removePrefix("channel/")?.let { anchor ->
                runCatching { storageKey(anchor) }.getOrNull()
            } }
        }.toMap()
        return state.copy(
            networkOrder = anchored(state.networkOrder, networkAnchors.filterKeys { it in state.networkOrder }),
            groupOrder = anchored(state.groupOrder, groupAnchors.filterKeys { it in state.groupOrder }),
            pinnedKeys = anchored(state.pinnedKeys, channelAnchors.filterKeys { it in state.pinnedKeys }),
            groups = state.groups.map { group -> group.copy(memberKeys = anchored(group.memberKeys,
                channelAnchors.filterKeys { it in group.memberKeys })) },
            channelOrder = anchored(state.channelOrder, channelAnchors.filterKeys { it in state.channelOrder }),
        )
    }

    private fun anchored(existing: List<String>, anchors: Map<String, String?>): List<String> {
        val base = existing.distinct()
        if (anchors.isEmpty()) return base
        val targets = base.filter { it in anchors }.toSet()
        val children = base.filter { it in targets }.groupBy { key ->
            anchors[key]?.takeIf { it in base && it != key } ?: ""
        }.mapValues { (_, siblings) -> siblings.sorted() }
        val result = ArrayList<String>(base.size)
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        fun visit(key: String) {
            if (key in visited || !visiting.add(key)) return
            val parent = anchors[key]
            if (parent in targets && parent !in visited) visit(requireNotNull(parent))
            if (visited.add(key)) result.add(key)
            visiting.remove(key)
            for (child in children[key].orEmpty()) visit(child)
        }
        for (root in children[""].orEmpty()) visit(root)
        for (key in base) if (key !in targets) visit(key)
        for (key in base) visit(key)
        return result
    }

    private fun themeVariants(value: JsonObject): ThemeVariants = ThemeVariants(
        name = value.string("name"),
        light = value["light"]?.takeUnless { it == JsonNull }?.let { Json.decodeFromJsonElement<ThemeDefinition>(it) },
        dark = value["dark"]?.takeUnless { it == JsonNull }?.let { Json.decodeFromJsonElement<ThemeDefinition>(it) },
    ).validated()

    private fun themeValue(variants: ThemeVariants): JsonObject = obj(
        "name" to JsonPrimitive(variants.name),
        "light" to (variants.light?.let { Json.encodeToJsonElement(it) } ?: JsonNull),
        "dark" to (variants.dark?.let { Json.encodeToJsonElement(it) } ?: JsonNull),
    )

    private fun storageKey(key: String): String {
        val parts = key.split('/')
        require(parts.size == 2 && isUuid(parts[0])) { "Invalid synced conversation" }
        return "${parts[0]}|${SyncLedger.decodeKeyPart(parts[1])}"
    }

    private fun conversationFromStorage(storage: String, networks: Set<String>): Pair<String, String>? {
        val network = storage.substringBefore('|')
        val target = storage.substringAfter('|', "")
        if (network !in networks || !isUuid(network) || target.isEmpty()) return null
        return try {
            val key = SyncLedger.conversationKey(network, target)
            SyncProjection.requireSafe("read", key, scopedKeyProbe)
            network to key
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun isUuid(text: String): Boolean = runCatching { UUID.fromString(text).toString() == text }.getOrDefault(false)

    private fun obj(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*fields))
    private fun text(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.string(key: String): String = requireNotNull(get(key).stringOrNull())
    private fun JsonObject.bool(key: String): Boolean = (get(key) as JsonPrimitive).boolean
    private fun JsonObject.number(key: String): Long = (get(key) as JsonPrimitive).long
    private fun JsonObject.integer(key: String): Int = (get(key) as JsonPrimitive).int
    private fun JsonObject.strings(key: String): List<String> = (get(key) as kotlinx.serialization.json.JsonArray)
        .map { (it as JsonPrimitive).content }
}
