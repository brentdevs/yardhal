package dev.brentdevs.yardhal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.brentdevs.yardhal.coordinator.ConnectionStatus
import dev.brentdevs.yardhal.coordinator.ConversationBuffer
import dev.brentdevs.yardhal.coordinator.LiveCoordinator
import dev.brentdevs.yardhal.coordinator.NetworkProfiles
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.ChatAppearanceStore
import dev.brentdevs.yardhal.ui.screens.AddNetworkSheet
import dev.brentdevs.yardhal.ui.screens.ConversationScreen
import dev.brentdevs.yardhal.ui.screens.MessageSearchScreen
import dev.brentdevs.yardhal.ui.screens.NetworkDraft
import dev.brentdevs.yardhal.ui.screens.NetworkOverviewScreen
import dev.brentdevs.yardhal.ui.screens.NetworkPresetUi
import dev.brentdevs.yardhal.ui.screens.WelcomeScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun YardhalAppRoot(
    coordinator: LiveCoordinator,
    appearanceStore: ChatAppearanceStore,
    presets: List<NetworkPresetUi>,
    onNetworkSaved: (NetworkDraft) -> Unit,
    sharedTextProvider: () -> String? = { null },
    onSharedConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val networks by coordinator.networks.collectAsStateWithLifecycle()
    val buffers by coordinator.buffers.collectAsStateWithLifecycle()
    val whoisInfo by coordinator.whois.collectAsStateWithLifecycle()
    val channelList by coordinator.channelList.collectAsStateWithLifecycle()
    val rawLogVersion by coordinator.rawLogVersion.collectAsStateWithLifecycle()
    val bouncerVersion by coordinator.bouncerVersion.collectAsStateWithLifecycle()
    val mutedKeys by coordinator.mutedState.collectAsStateWithLifecycle()
    val orderState by coordinator.orderState.collectAsStateWithLifecycle()
    val profiles by coordinator.profiles.collectAsStateWithLifecycle()

    var addNetworkVisible by remember { mutableStateOf(false) }
    var pendingPreset by remember { mutableStateOf<NetworkPresetUi?>(null) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    fun selectConversation(key: String?) {
        coordinator.trackSelection(key)
        selectedKey = key
    }
    val conversationStateHolder = rememberSaveableStateHolder()
    var joinDialogVisible by remember { mutableStateOf(false) }
    var joinDraft by remember { mutableStateOf("") }
    var joinNetworkId by remember { mutableStateOf<String?>(null) }
    var joinSendFailed by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchNetworkScope by rememberSaveable { mutableStateOf<String?>(null) }
    var searchConversationScope by rememberSaveable { mutableStateOf<String?>(null) }
    var searchTargetRowId by remember { mutableStateOf<Long?>(null) }
    var returnToSearch by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf(false) }
    var searchRetry by remember { mutableStateOf(0) }
    var retryImmediately by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<dev.brentdevs.yardhal.core.data.FtsHit>>(emptyList()) }
    var bouncerVisible by remember { mutableStateOf(false) }
    var bouncerAddr by remember { mutableStateOf("ircs://") }
    var bouncerName by remember { mutableStateOf("") }
    var bouncerNick by remember { mutableStateOf("") }
    var bouncerPassword by remember { mutableStateOf("") }
    var bouncerNetworkId by remember { mutableStateOf<String?>(null) }
    var appearanceVisible by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    var appearance by remember { mutableStateOf(appearanceStore.snapshot()) }
    val wide = configuration.screenWidthDp >= 600
    val paneWidth = (configuration.screenWidthDp * 0.38f).coerceIn(280f, 360f).dp
    val sharedDraft = sharedTextProvider()
    if (sharedDraft != null && selectedKey == null) {
        val first = buffers.values
            .filter { it.ref.kind != dev.brentdevs.yardhal.core.data.ConversationKind.SERVER }
            .minByOrNull { it.displayName.lowercase() }
        selectConversation(first?.key)
    }
    val renamedSelection = coordinator.followRenamedSelection(selectedKey, buffers.keys)
    if (renamedSelection != null) {
        selectConversation(renamedSelection)
    }

    val pickLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val key = selectedKey ?: return@rememberLauncherForActivityResult
            val networkId = key.substringBefore("|")
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val name = queryDisplayName(resolver, uri)
            val bytes: ByteArray? = runCatching {
                resolver.openInputStream(uri)?.use { input -> input.readBytes() }
            }.getOrNull()
            if (bytes != null) {
                coordinator.uploadAndShare(networkId, key, name, mime, bytes)
            }
        }
    }

    fun launchAttachmentPicker() {
        pickLauncher.launch(arrayOf("*/*"))
    }

    fun conversationBufferFor(key: String?): ConversationBuffer? = key?.let { buffers[it] }

    val overviewScreen: @Composable (Modifier, () -> Unit) -> Unit = { overviewModifier, onSelected ->
        NetworkOverviewScreen(
            buffers = buffers.values
                .filter { it.ref.kind != dev.brentdevs.yardhal.core.data.ConversationKind.SERVER }
                .sortedBy { it.displayName.lowercase() },
            networks = networks,
            mutedKeys = mutedKeys,
            orderState = orderState,
            onSelect = { key ->
                coordinator.markRead(key)
                selectConversation(key)
                searchTargetRowId = null
                returnToSearch = false
                onSelected()
            },
            onSelectServer = { networkId ->
                val key = ConversationRef.server(networkId).storageKey
                coordinator.markRead(key)
                selectConversation(key)
                searchTargetRowId = null
                returnToSearch = false
                onSelected()
            },
            onAddNetwork = { addNetworkVisible = true },
            onJoinChannel = { networkId ->
                joinNetworkId = networkId
                joinSendFailed = false
                joinDialogVisible = true
            },
            onRemoveNetwork = coordinator::removeNetwork,
            onBrowseChannels = coordinator::startChannelList,
            channelList = channelList,
            onJoinFromList = { networkId, channel ->
                coordinator.sendText(networkId, ConversationRef.server(networkId).storageKey, "/join $channel")
            },
            rawLogVersion = rawLogVersion,
            rawLogProvider = coordinator::rawLog,
            showBouncerButton = coordinator.hasBouncerSession(),
            onOpenBouncer = { bouncerVisible = true },
            onOpenAppearance = { appearanceVisible = true },
            onMarkRead = coordinator::markRead,
            onToggleMute = coordinator::toggleMute,
            onLeave = { key -> coordinator.leaveConversation(key.substringBefore("|"), key) },
            onTogglePin = coordinator::togglePin,
            onRetryJoin = { key -> coordinator.retryJoin(key.substringBefore("|"), key) },
            onMoveToGroup = { key, groupId ->
                if (groupId == null) coordinator.removeFromGroup(key) else coordinator.addToGroup(groupId, key)
            },
            onCreateGroup = { name, done -> done(coordinator.createGroup(name)) },
            modifier = overviewModifier,
        )
    }

    val conversationContent: @Composable (String, ConversationBuffer, String, Boolean, (() -> Unit)?) -> Unit =
        { key, buffer, networkName, connected, onOpenBuffers ->
            val networkId = key.substringBefore("|")
            val networkFeatures = networks.firstOrNull { it.id == networkId }
            conversationStateHolder.SaveableStateProvider(key) {
                ConversationScreen(
                    buffer = buffer,
                    networkName = networkName,
                    channels = buffers.values.filter {
                        it.ref.networkId == networkId &&
                            it.ref.kind == dev.brentdevs.yardhal.core.data.ConversationKind.CHANNEL
                    }.map { it.ref.rawTarget },
                    connected = connected,
                    canSendOffline = { text -> coordinator.canSendOffline(networkId, key, text) },
                    onSend = { text ->
                        val sent = coordinator.sendText(networkId, key, text)
                        if (sent && connected && !coordinator.canSendOffline(networkId, key, text)) {
                            coordinator.sendTyping(networkId, key)
                        }
                        sent
                    },
                    onOpenJoin = {
                        joinNetworkId = networkId
                        joinSendFailed = false
                        joinDialogVisible = true
                    },
                    onLoadHistory = { coordinator.loadPersistedHistory(key) },
                    onLoadMembers = { coordinator.ensureMembers(networkId, key) },
                    onReact = { msgid, emoji -> coordinator.react(networkId, key, msgid, emoji) },
                    onSetReplyDraft = { message -> coordinator.setReplyDraft(networkId, key, message) },
                    onDelete = { msgid -> coordinator.deleteMessage(networkId, key, msgid) },
                    onOpenBuffers = onOpenBuffers,
                    onRetryJoin = { coordinator.retryJoin(networkId, key) },
                    searchTargetRowId = searchTargetRowId,
                    onSearchTargetShown = { searchTargetRowId = null },
                    appearance = appearance,
                    onOpenAppearance = { appearanceVisible = true },
                    onOpenSearch = {
                        searchVisible = true
                        returnToSearch = false
                    },
                    onMemberAction = { action, nick -> coordinator.memberAction(networkId, key, action, nick) },
                    onOpenDm = { nick ->
                        selectConversation(coordinator.directMessageKey(networkId, key, nick))
                        searchTargetRowId = null
                        returnToSearch = false
                    },
                    hasBotMode = networkFeatures?.hasBotMode == true,
                    accountBanAvailable = networkFeatures?.accountBanAvailable == true,
                    onOpenChannel = { channel ->
                        selectConversation(coordinator.ensureConversation(networkId, channel))
                        searchTargetRowId = null
                        returnToSearch = false
                    },
                    sharedDraft = sharedDraft,
                    onSharedConsumed = onSharedConsumed,
                    profiles = profiles[networkId] ?: NetworkProfiles.EMPTY,
                    onPickFile = { launchAttachmentPicker() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

    LaunchedEffect(searchVisible, searchQuery, searchNetworkScope, searchConversationScope, searchRetry) {
        if (!searchVisible || searchQuery.length < 2) {
            searchResults = emptyList()
            searching = false
            searchError = false
            retryImmediately = false
        } else {
            searching = true
            searchResults = emptyList()
            searchError = false
            val skipDebounce = retryImmediately
            retryImmediately = false
            if (!skipDebounce) delay(250)
            try {
                searchResults = coordinator.searchMessages(searchQuery, searchNetworkScope, searchConversationScope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                searchError = true
            }
            searching = false
        }
    }

    BackHandler(searchVisible) {
        searchVisible = false
        returnToSearch = false
    }

    Surface(modifier = modifier.fillMaxSize()) {
        if (addNetworkVisible) {
            AddNetworkSheet(
                presets = presets,
                initialPreset = pendingPreset,
                onSave = {
                    onNetworkSaved(it)
                    addNetworkVisible = false
                    pendingPreset = null
                },
                onDismiss = {
                    addNetworkVisible = false
                    pendingPreset = null
                },
            )
        } else if (searchVisible) {
            val searchScreen: @Composable (Modifier) -> Unit = { searchModifier -> MessageSearchScreen(
                query = searchQuery,
                results = searchResults,
                searching = searching,
                error = searchError,
                networks = networks,
                buffers = buffers.values.toList(),
                networkScope = searchNetworkScope,
                conversationScope = searchConversationScope,
                onQueryChange = { searchQuery = it },
                onNetworkScopeChange = { scope ->
                    searchNetworkScope = scope
                    searchConversationScope = null
                },
                onConversationScopeChange = { searchConversationScope = it },
                onRetry = {
                    searching = true
                    searchError = false
                    retryImmediately = true
                    searchRetry += 1
                },
                onOpenHit = { hit ->
                    selectConversation(coordinator.openSearchHit(hit))
                    searchTargetRowId = hit.rowId
                    returnToSearch = true
                    searchVisible = false
                },
                onBack = {
                    searchVisible = false
                    returnToSearch = false
                },
                modifier = searchModifier,
            ) }
            if (wide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.width(paneWidth)) {
                        overviewScreen(Modifier.fillMaxSize()) { searchVisible = false }
                    }
                    searchScreen(Modifier.weight(1f))
                }
            } else {
                searchScreen(Modifier.fillMaxSize())
            }
        } else if (wide) {
            BackHandler(returnToSearch) {
                searchVisible = true
                returnToSearch = false
            }
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.width(paneWidth)) {
                    overviewScreen(Modifier.fillMaxSize()) {}
                }
                val key = selectedKey
                val buffer = conversationBufferFor(key)
                if (key != null && buffer != null) {
                    Box(modifier = Modifier.weight(1f)) {
                        val networkId = key.substringBefore("|")
                        val network = networks.firstOrNull { it.id == networkId }
                        conversationContent(key, buffer, network?.name.orEmpty(), network?.status == ConnectionStatus.REGISTERED, null)
                    }
                } else {
                    Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Choose a conversation", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            when {
                selectedKey != null -> {
                    val key = selectedKey ?: return@Surface
                    val buffer = conversationBufferFor(key)
                    val networkId = key.substringBefore("|")
                    val network = networks.firstOrNull { it.id == networkId }
                    if (buffer == null || network == null) {
                        selectConversation(null)
                    } else {
                        BackHandler(enabled = drawerState.currentValue == DrawerValue.Closed) {
                            if (returnToSearch) {
                                searchVisible = true
                                returnToSearch = false
                            } else {
                                selectConversation(null)
                            }
                        }
                        ModalNavigationDrawer(
                            drawerState = drawerState,
                            drawerContent = {
                                ModalDrawerSheet(modifier = Modifier.widthIn(max = 360.dp)) {
                                    overviewScreen(Modifier.fillMaxSize()) {
                                        drawerScope.launch { drawerState.close() }
                                    }
                                }
                            },
                        ) {
                            conversationContent(key, buffer, network.name, network.status == ConnectionStatus.REGISTERED) {
                                drawerScope.launch { drawerState.open() }
                            }
                        }
                    }
                }

                else -> {
                    if (networks.isEmpty()) {
                        WelcomeScreen(
                            onAddNetwork = {
                                pendingPreset = null
                                addNetworkVisible = true
                            },
                            presets = presets,
                            onPickPreset = { picked ->
                                pendingPreset = picked
                                addNetworkVisible = true
                            },
                        )
                    } else {
                        overviewScreen(Modifier.fillMaxSize()) {}
                    }
                }
            }
        }
    }

    if (appearanceVisible) {
        ModalBottomSheet(onDismissRequest = { appearanceVisible = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Chat appearance", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Compact message spacing", modifier = Modifier.weight(1f))
                    Switch(
                        checked = appearance.compact,
                        onCheckedChange = { compact ->
                            appearance = appearance.copy(compact = compact)
                            appearanceStore.update(appearance)
                        },
                    )
                }
                Text("Message text size", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Small" to 0.9f, "Default" to 1f, "Large" to 1.15f).forEach { (label, scale) ->
                        FilterChip(
                            selected = appearance.textScale == scale,
                            onClick = {
                                appearance = appearance.copy(textScale = scale)
                                appearanceStore.update(appearance)
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
    }

    if (bouncerVisible) {
        val entries = remember(bouncerVersion) { coordinator.bouncerEntries() }
        val bouncerIds = coordinator.bouncerSessionIds()
        val selectedBouncerId = bouncerNetworkId ?: bouncerIds.singleOrNull()
        ModalBottomSheet(onDismissRequest = { bouncerVisible = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 700.dp).imePadding()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                    Text("Bouncer networks", style = MaterialTheme.typography.titleLarge)
                    if (bouncerIds.size > 1) {
                        Text("Management connection", style = MaterialTheme.typography.titleSmall)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            bouncerIds.forEach { id ->
                                FilterChip(
                                    selected = selectedBouncerId == id,
                                    onClick = { bouncerNetworkId = id },
                                    label = { Text(networks.firstOrNull { it.id == id }?.name ?: id) },
                                )
                            }
                        }
                    }
                    if (entries.isEmpty()) {
                        Text("No networks reported yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                            entries.forEach { entry ->
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "${networks.firstOrNull { it.id == entry.networkId }?.name.orEmpty()} · ${entry.attributes.name ?: entry.netId}",
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            Text(
                                                text = listOfNotNull(
                                                    entry.attributes.host,
                                                    entry.attributes.port?.toString(),
                                                    entry.attributes.state?.wireName,
                                                    entry.attributes.error?.let { "error: $it" },
                                                ).joinToString(" · "),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val connected = entry.attributes.state?.wireName == "connected"
                                        TextButton(onClick = {
                                            coordinator.connectBouncerNetwork(entry.networkId, entry.netId, connect = !connected)
                                        }) { Text(if (connected) "Disconnect" else "Connect") }
                                        if (!entry.isBoundToThisConnection) {
                                            TextButton(onClick = {
                                                coordinator.deleteBouncerNetwork(entry.networkId, entry.netId)
                                            }) { Text("Delete") }
                                        }
                                    }
                                }
                        }
                    }

                    androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Add network", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = bouncerAddr,
                        onValueChange = { bouncerAddr = it },
                        label = { Text("Address (ircs://host[:port])") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = bouncerName,
                        onValueChange = { bouncerName = it },
                        label = { Text("Name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = bouncerNick,
                            onValueChange = { bouncerNick = it },
                            label = { Text("Nick (optional)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = bouncerPassword,
                            onValueChange = { bouncerPassword = it },
                            label = { Text("Pass (optional)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    TextButton(
                        enabled = bouncerAddr.isNotBlank() && selectedBouncerId != null,
                        onClick = {
                            val networkId = selectedBouncerId
                            if (networkId != null) {
                                coordinator.addBouncerNetwork(
                                    networkId,
                                    dev.brentdevs.yardhal.core.data.BouncerNetworkDraft(
                                        addr = bouncerAddr.trim(),
                                        name = bouncerName.trim(),
                                        nick = bouncerNick.trim(),
                                        password = bouncerPassword,
                                    ),
                                )
                                bouncerAddr = "ircs://"
                                bouncerName = ""
                                bouncerNick = ""
                                bouncerPassword = ""
                            }
                        },
                    ) { Text("Add") }
            }
        }
    }

    whoisInfo?.let { info ->
        ModalBottomSheet(onDismissRequest = coordinator::dismissWhois) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Whois · ${info.nick}", style = MaterialTheme.typography.titleLarge)
                    info.realName?.let { Text(it) }
                    if (info.user != null || info.host != null) {
                        Text("${info.user ?: "?"}@${info.host ?: "?"}", style = MaterialTheme.typography.bodySmall)
                    }
                    info.account?.let { Text("Account: $it", style = MaterialTheme.typography.bodySmall) }
                    if (info.isBot) Text("Bot", style = MaterialTheme.typography.bodySmall)
                    info.server?.let { Text("Server: $it ${info.serverInfo.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
                    info.idleSeconds?.let { Text("Idle: ${it / 60} min", style = MaterialTheme.typography.bodySmall) }
                    if (info.channels.isNotEmpty()) {
                        Text(info.channels.joinToString(" "), style = MaterialTheme.typography.bodySmall)
                    }
                }
        }
    }

    if (joinDialogVisible) {
        val activeNetworkId = joinNetworkId ?: networks.singleOrNull()?.id
        val networkReady = networks.any { it.id == activeNetworkId && it.status == ConnectionStatus.REGISTERED }
        AlertDialog(
            onDismissRequest = { joinDialogVisible = false },
            title = { Text("Join channel") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (networks.size > 1) {
                        Text("Network", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            networks.forEach { network ->
                                FilterChip(
                                    selected = activeNetworkId == network.id,
                                    onClick = {
                                        joinNetworkId = network.id
                                        joinSendFailed = false
                                    },
                                    label = { Text(network.name) },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = joinDraft,
                        onValueChange = {
                            joinDraft = it
                            joinSendFailed = false
                        },
                        placeholder = { Text("#channel") },
                        singleLine = true,
                    )
                    if (!networkReady || joinSendFailed) {
                        Text(
                            when {
                                joinSendFailed -> "Could not send Join. Try again when connected."
                                networks.size > 1 -> "Choose a connected network to join."
                                else -> "Connect to this network to join."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = joinDraft.startsWith("#") && joinDraft.length > 1 && networkReady,
                    onClick = {
                        val sent = activeNetworkId?.let { networkId ->
                            coordinator.sendText(
                                networkId,
                                ConversationRef.server(networkId).storageKey,
                                "/join $joinDraft",
                            )
                        } == true
                        if (sent) {
                            joinDraft = ""
                            joinDialogVisible = false
                        } else {
                            joinSendFailed = true
                        }
                    },
                ) { Text("Join") }
            },
            dismissButton = {
                TextButton(onClick = { joinDialogVisible = false }) { Text("Cancel") }
            },
        )
    }
}

private fun queryDisplayName(resolver: android.content.ContentResolver, uri: android.net.Uri): String =
    runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "upload.bin"
