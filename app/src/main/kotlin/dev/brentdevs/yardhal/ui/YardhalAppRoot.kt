package dev.brentdevs.yardhal.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.brentdevs.yardhal.coordinator.ConnectionStatus
import dev.brentdevs.yardhal.coordinator.ConversationBuffer
import dev.brentdevs.yardhal.coordinator.LiveCoordinator
import dev.brentdevs.yardhal.coordinator.NetworkProfiles
import dev.brentdevs.yardhal.core.data.ChatAppearancePreferences
import dev.brentdevs.yardhal.core.data.ChatAppearanceStore
import dev.brentdevs.yardhal.core.data.ConversationRef
import dev.brentdevs.yardhal.core.data.StorageRecovery
import dev.brentdevs.yardhal.ui.screens.BouncerManagementSheet
import dev.brentdevs.yardhal.ui.screens.NetworkEditorSheet
import dev.brentdevs.yardhal.ui.screens.ConversationScreen
import dev.brentdevs.yardhal.ui.screens.MessageSearchScreen
import dev.brentdevs.yardhal.ui.screens.NetworkDraft
import dev.brentdevs.yardhal.ui.screens.NetworkOverviewScreen
import dev.brentdevs.yardhal.ui.screens.NetworkPresetUi
import dev.brentdevs.yardhal.ui.screens.WelcomeScreen
import dev.brentdevs.yardhal.ui.image.MediaEnvironment
import dev.brentdevs.yardhal.ui.image.MediaSettingsSheet
import androidx.compose.runtime.CompositionLocalProvider
import dev.brentdevs.yardhal.core.data.AttachmentDestination
import dev.brentdevs.yardhal.core.data.AttachmentStageStatus
import dev.brentdevs.yardhal.core.data.RecentEmojiStore
import dev.brentdevs.yardhal.core.data.RelayConfigurationStore
import dev.brentdevs.yardhal.core.data.UploadSettingsStore
import dev.brentdevs.yardhal.media.AttachmentStageManager
import dev.brentdevs.yardhal.media.AttachmentStagingPanel
import dev.brentdevs.yardhal.media.UploadSettingsSheet
import dev.brentdevs.yardhal.ui.components.LocalRecentEmojiStore
import dev.brentdevs.yardhal.ui.screens.RelaySettingsSheet

private val NETWORK_ID_SET_SAVER = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun YardhalAppRoot(
    coordinator: LiveCoordinator,
    appearanceStore: ChatAppearanceStore,
    mediaEnvironment: MediaEnvironment,
    attachmentStages: AttachmentStageManager,
    uploadSettings: UploadSettingsStore,
    recentEmojiStore: RecentEmojiStore,
    relayConfigurations: RelayConfigurationStore,
    presets: List<NetworkPresetUi>,
    onNetworkSaved: (NetworkDraft) -> Boolean,
    sharedTextProvider: () -> String? = { null },
    onSharedConsumed: () -> Unit = {},
    incomingShareError: String? = null,
    onSharedErrorConsumed: (String) -> Unit = {},
    onAppearanceChanged: (ChatAppearancePreferences) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val networks by coordinator.networks.collectAsStateWithLifecycle()
    val buffers by coordinator.buffers.collectAsStateWithLifecycle()
    val stagedAttachments by attachmentStages.attachments.collectAsStateWithLifecycle()
    val whoisPresentation by coordinator.whoisPresentation.collectAsStateWithLifecycle()
    val restoredSelection by coordinator.restoredSelection.collectAsStateWithLifecycle()
    val restorationReady by coordinator.restorationReady.collectAsStateWithLifecycle()
    val recoveryNotices by StorageRecovery.notices.collectAsStateWithLifecycle()
    var recoveryDetailsVisible by rememberSaveable { mutableStateOf(false) }
    var recoveryAcknowledging by remember { mutableStateOf(false) }
    val recoveryBanner = storageRecoveryBanner(recoveryNotices)
    LaunchedEffect(recoveryNotices) {
        if (recoveryNotices.isEmpty()) recoveryDetailsVisible = false
    }
    val channelList by coordinator.channelList.collectAsStateWithLifecycle()
    val rawLogVersion by coordinator.rawLogVersion.collectAsStateWithLifecycle()
    val bouncerAccounts by coordinator.bouncerManagement.accounts.collectAsStateWithLifecycle()
    val mutedKeys by coordinator.mutedState.collectAsStateWithLifecycle()
    val orderState by coordinator.orderState.collectAsStateWithLifecycle()
    val profiles by coordinator.profiles.collectAsStateWithLifecycle()
    val operationError by coordinator.operationError.collectAsStateWithLifecycle()
    val operationSnackbarState = remember { SnackbarHostState() }
    LaunchedEffect(operationError) {
        val error = operationError ?: return@LaunchedEffect
        operationSnackbarState.showSnackbar(error, withDismissAction = true)
        coordinator.dismissOperationError(error)
    }
    LaunchedEffect(incomingShareError) {
        incomingShareError?.let {
            operationSnackbarState.showSnackbar(it, withDismissAction = true)
            onSharedErrorConsumed(it)
        }
    }

    var networkEditorVisible by rememberSaveable { mutableStateOf(false) }
    var selectionSettled by rememberSaveable { mutableStateOf(false) }
    var editingNetworkId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPresetId by rememberSaveable { mutableStateOf<String?>(null) }
    fun openNetworkEditor(networkId: String? = null, preset: NetworkPresetUi? = null) {
        editingNetworkId = networkId
        selectionSettled = true
        pendingPresetId = preset?.id
        networkEditorVisible = true
    }
    fun dismissNetworkEditor() {
        networkEditorVisible = false
        editingNetworkId = null
        pendingPresetId = null
    }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var collapsedNetworkIds by rememberSaveable(stateSaver = NETWORK_ID_SET_SAVER) {
        mutableStateOf(emptySet<String>())
    }
    fun selectConversation(key: String?) {
        selectionSettled = true
        coordinator.trackSelection(key)
        selectedKey = key
    }
    LaunchedEffect(restorationReady, restoredSelection, buffers.keys) {
        if (restorationReady && !selectionSettled) {
            val restored = restoredLaunchSelection(selectedKey, selectionSettled, true, restoredSelection, buffers.keys)
            selectionSettled = true
            selectedKey = restored
            if (restored != null) coordinator.trackSelection(restored)
        }
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
    var bouncerInitialId by remember { mutableStateOf<String?>(null) }
    var removingAccountId by remember { mutableStateOf<String?>(null) }
    var appearanceVisible by remember { mutableStateOf(false) }
    var mediaSettingsVisible by rememberSaveable { mutableStateOf(false) }
    var uploadSettingsVisible by rememberSaveable { mutableStateOf(false) }
    var relaySettingsVisible by rememberSaveable { mutableStateOf(false) }
    var relaySettingsRevision by remember { mutableStateOf(0) }
    var shareChooserBatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var dismissedShareBatches by remember { mutableStateOf(emptySet<String>()) }
    var attachmentPickerKey by rememberSaveable { mutableStateOf<String?>(null) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    LaunchedEffect(attachmentError) {
        attachmentError?.let { operationSnackbarState.showSnackbar(it, withDismissAction = true) }
        attachmentError = null
    }
    LaunchedEffect(stagedAttachments) {
        val unbound = stagedAttachments.firstOrNull { it.destination == null && it.batchId !in dismissedShareBatches }
        if (shareChooserBatchId == null && unbound != null) shareChooserBatchId = unbound.batchId
        val uploaded = stagedAttachments.filter {
            it.status == AttachmentStageStatus.UPLOADED && it.insertionToken == null && it.destination != null
        }.groupBy { it.destination }
        for (entries in uploaded.values) {
            try {
                attachmentStages.prepareInsertion(entries.map { it.id })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                attachmentError = "Upload completed. Its URL remains in staged attachments, but could not be inserted. Reopen the original conversation or retry insertion."
            }
        }
    }

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
        if (first != null) selectConversation(first.key)
    }
    val renamedSelection = coordinator.followRenamedSelection(selectedKey, buffers.keys)
    if (renamedSelection != null) {
        selectConversation(renamedSelection)
    }

    val pickLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            val key = attachmentPickerKey
            attachmentPickerKey = null
            val original = key?.let { buffers[it] }
            val destination = original?.let {
                AttachmentDestination(it.ref.networkId, it.key, "${networks.firstOrNull { network -> network.id == it.ref.networkId }?.name.orEmpty()} · ${it.displayName}")
            }
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            drawerScope.launch {
                try {
                    attachmentStages.stageIncoming(uris, destination = destination)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    attachmentError = "Unable to preserve selected files. Free local storage or select the files again."
                }
            }
        }
    }

    fun launchAttachmentPicker(key: String) {
        attachmentPickerKey = key
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
            collapsedNetworkIds = collapsedNetworkIds,
            onToggleNetwork = { networkId ->
                selectionSettled = true
                collapsedNetworkIds = if (networkId in collapsedNetworkIds) {
                    collapsedNetworkIds - networkId
                } else {
                    collapsedNetworkIds + networkId
                }
            },
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
            onAddNetwork = { openNetworkEditor() },
            onEditNetwork = { networkId ->
                val config = coordinator.networkStore.byId(networkId)
                if (config?.bouncerBinding != null) {
                    bouncerInitialId = networkId
                    bouncerVisible = true
                } else if (config != null) openNetworkEditor(networkId = networkId)
            },
            onConnectNetwork = coordinator::connectNetwork,
            onDisconnectNetwork = { coordinator.disconnect(it) },
            onTrustCertificate = coordinator::trustCertificate,
            onRemoveCertificateTrust = coordinator::removeCertificateTrust,
            onJoinChannel = { networkId ->
                selectionSettled = true
                joinNetworkId = networkId
                joinSendFailed = false
                joinDialogVisible = true
            },
            onRemoveNetwork = { networkId ->
                if (coordinator.dependentNetworks(networkId).isNotEmpty()) removingAccountId = networkId
                else coordinator.removeNetwork(networkId)
            },
            onBrowseChannels = {
                selectionSettled = true
                coordinator.startChannelList(it)
            },
            channelList = channelList,
            onJoinFromList = { networkId, channel ->
                coordinator.sendText(networkId, ConversationRef.server(networkId).storageKey, "/join $channel")
            },
            rawLogVersion = rawLogVersion,
            rawLogProvider = coordinator::rawLog,
            showBouncerButton = coordinator.networkStore.all().any { it.mode != dev.brentdevs.yardhal.core.data.NetworkMode.DIRECT } ||
                bouncerAccounts.values.any { it.mode != dev.brentdevs.yardhal.core.data.NetworkMode.DIRECT },
            onOpenBouncer = { selectionSettled = true; bouncerInitialId = null; bouncerVisible = true },
            onOpenAppearance = { selectionSettled = true; appearanceVisible = true },
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
            val insertion = pendingAttachmentInsertion(key, attachmentStages.pendingInsertions())
            val incomingDraft = listOfNotNull(sharedDraft, insertion?.let(::attachmentInsertionDraft)).joinToString("\n").takeIf { it.isNotEmpty() }
            val destination = AttachmentDestination(networkId, key, "$networkName · ${buffer.displayName}")
            val relayPresenter = remember(networkId, relaySettingsRevision) {
                { message: dev.brentdevs.yardhal.coordinator.ChatMessage -> coordinator.relayPresentation(networkId, message) }
            }
            conversationStateHolder.SaveableStateProvider(key) {
                ConversationScreen(
                    buffer = buffer,
                    networkName = networkName,
                    channels = buffers.values.filter {
                        it.ref.networkId == networkId &&
                            it.ref.kind == dev.brentdevs.yardhal.core.data.ConversationKind.CHANNEL
                    }.map { it.ref.rawTarget },
                    connected = connected,
                    network = networkFeatures,
                    onConnectNetwork = { coordinator.connectNetwork(networkId) },
                    onDisconnectNetwork = { coordinator.disconnect(networkId) },
                    onEditNetwork = { openNetworkEditor(networkId = networkId) },
                    onTrustCertificate = { inspection -> coordinator.trustCertificate(networkId, inspection) },
                    onRemoveCertificateTrust = { coordinator.removeCertificateTrust(networkId) },
                    canSendOffline = { text -> coordinator.canSendOffline(networkId, key, text) },
                    onSend = { text ->
                        val uploads = messageUploads(key, text, stagedAttachments)
                        val sent = coordinator.sendText(networkId, key, text, attachments = uploads)
                        if (sent && uploads.isNotEmpty()) {
                            val urls = uploads.map { it.url }.toSet()
                            stagedAttachments.filter { it.destination?.storageKey == key && it.uploaded?.url in urls }
                                .forEach { attachmentStages.remove(it.id) }
                        }
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
                    onLoadOlderHistory = { coordinator.loadOlderHistory(key) },
                    onRetryHistory = { coordinator.retryHistory(key) },
                    onFillHistoryGap = { gapId -> coordinator.fillHistoryGap(key, gapId) },
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
                        selectionSettled = true
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
                        val channelKey = coordinator.openChannel(networkId, channel)
                        if (channelKey != null) {
                            selectConversation(channelKey)
                            searchTargetRowId = null
                            returnToSearch = false
                        } else {
                            joinNetworkId = networkId
                            joinDraft = channel
                            joinSendFailed = true
                            joinDialogVisible = true
                        }
                    },
                    sharedDraft = incomingDraft,
                    sharedDraftToken = insertion?.token ?: sharedDraft,
                    onSharedConsumed = {
                        if (sharedTextProvider() == sharedDraft) onSharedConsumed()
                        insertion?.let { attachmentStages.ackInserted(it.token) }
                    },
                    profiles = profiles[networkId] ?: NetworkProfiles.EMPTY,
                    onPickFile = { launchAttachmentPicker(key) },
                    relayPresentation = relayPresenter,
                    stagingContent = {
                        AttachmentStagingPanel(
                            manager = attachmentStages,
                            destination = destination,
                            onInsert = { attachmentError = "Uploaded URL ready for ${it.destination.label}. Review the draft and send explicitly." },
                            onChooseDestination = { shareChooserBatchId = it },
                            onOpenSettings = { uploadSettingsVisible = true },
                        )
                    },
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

    val initialNetworkConfig = if (networkEditorVisible) editingNetworkId?.let(coordinator.networkStore::byId) else null
    val missingEditTarget = networkEditorVisible && editingNetworkId != null && initialNetworkConfig == null
    if (missingEditTarget) {
        LaunchedEffect(editingNetworkId) { dismissNetworkEditor() }
    }

    val mainContent: @Composable () -> Unit = content@{
        if (networkEditorVisible && !missingEditTarget) {
            NetworkEditorSheet(
                presets = presets,
                initialPreset = presets.firstOrNull { it.id == pendingPresetId },
                initialConfig = initialNetworkConfig,
                onSave = {
                    val saved = onNetworkSaved(it)
                    if (saved) dismissNetworkEditor()
                    saved
                },
                onDismiss = ::dismissNetworkEditor,
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
                    val key = selectedKey ?: return@content
                    val buffer = conversationBufferFor(key)
                    val networkId = key.substringBefore("|")
                    val network = networks.firstOrNull { it.id == networkId }
                    if (buffer == null || network == null) {
                        if (restorationReady) {
                            LaunchedEffect(key) { selectConversation(null) }
                        }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (restorationReady) "Conversation unavailable" else "Restoring stored conversation…")
                        }
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
                            onAddNetwork = { openNetworkEditor() },
                            presets = presets,
                            onPickPreset = { picked -> openNetworkEditor(preset = picked) },
                        )
                    } else {
                        overviewScreen(Modifier.fillMaxSize()) {}
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(operationSnackbarState) },
    ) { contentPadding ->
        Column(Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding)) {
            if (recoveryBanner != null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when (recoveryBanner) {
                                StorageRecoveryBanner.TEMPORARY_SESSION -> "Storage warning · temporary session"
                                StorageRecoveryBanner.WARNING -> "Storage recovery warning · evidence is not restored messages"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { recoveryDetailsVisible = true }) { Text("Details") }
                    }
                }
            }
            if (selectedKey == null && stagedAttachments.any { it.destination == null }) {
                AttachmentStagingPanel(
                    manager = attachmentStages,
                    destination = null,
                    onInsert = { attachmentError = "Uploaded URL ready for ${it.destination.label}. Open that conversation to review and send." },
                    onChooseDestination = { shareChooserBatchId = it },
                    onOpenSettings = { uploadSettingsVisible = true },
                )
            }
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalRecentEmojiStore provides recentEmojiStore) { mainContent() }
            }
        }
    }

    if (recoveryDetailsVisible && recoveryNotices.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { recoveryDetailsVisible = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Storage recovery", style = MaterialTheme.typography.titleLarge)
                recoveryNotices.forEach { notice ->
                    Text(storageRecoveryExplanation(notice), style = MaterialTheme.typography.bodyMedium)
                    if (StorageRecovery.canAcknowledge(notice)) {
                        TextButton(
                            enabled = !recoveryAcknowledging,
                            onClick = {
                                recoveryAcknowledging = true
                                drawerScope.launch {
                                    val acknowledged = try {
                                        withContext(Dispatchers.IO) { StorageRecovery.acknowledge(notice) }
                                    } finally {
                                        recoveryAcknowledging = false
                                    }
                                    if (!acknowledged) {
                                        operationSnackbarState.showSnackbar(
                                            "Could not save acknowledgement. The storage warning remains active.",
                                            withDismissAction = true,
                                        )
                                    }
                                }
                            },
                        ) { Text("Acknowledge · keep evidence") }
                    }
                }
            }
        }
    }

    if (appearanceVisible) {
        val updateAppearance: (ChatAppearancePreferences) -> Unit = { updated ->
            appearance = updated
            appearanceStore.update(updated)
            onAppearanceChanged(updated)
        }
        ModalBottomSheet(onDismissRequest = { appearanceVisible = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Chat appearance", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { appearanceVisible = false; mediaSettingsVisible = true }) {
                    Text("Media and privacy")
                }
                TextButton(onClick = { appearanceVisible = false; uploadSettingsVisible = true }) {
                    Text("Uploads and photo privacy")
                }
                if (selectedKey != null) {
                    TextButton(onClick = { appearanceVisible = false; relaySettingsVisible = true }) {
                        Text("Configured relay senders")
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .toggleable(
                            value = appearance.compact,
                            role = Role.Switch,
                            onValueChange = { updateAppearance(appearance.copy(compact = it)) },
                        ),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Compact spacing", style = MaterialTheme.typography.bodyLarge)
                        Text("Reduce padding between messages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = appearance.compact,
                        onCheckedChange = null,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .toggleable(
                            value = appearance.monospaceFont,
                            role = Role.Switch,
                            onValueChange = { updateAppearance(appearance.copy(monospaceFont = it)) },
                        ),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Monospace font", style = MaterialTheme.typography.bodyLarge)
                        Text("Render messages in fixed-width font", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = appearance.monospaceFont,
                        onCheckedChange = null,
                    )
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .toggleable(
                                value = appearance.dynamicColor,
                                role = Role.Switch,
                                onValueChange = { updateAppearance(appearance.copy(dynamicColor = it)) },
                            ),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Material You Dynamic Colors", style = MaterialTheme.typography.bodyLarge)
                            Text("Match wallpaper palette on Android 12+", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = appearance.dynamicColor,
                            onCheckedChange = null,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .toggleable(
                            value = appearance.amoledDark,
                            role = Role.Switch,
                            onValueChange = { updateAppearance(appearance.copy(amoledDark = it)) },
                        ),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AMOLED pure black", style = MaterialTheme.typography.bodyLarge)
                        Text("Pitch-black background in dark mode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = appearance.amoledDark,
                        onCheckedChange = null,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Message text size", style = MaterialTheme.typography.titleSmall)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        listOf("Small" to 0.9f, "Default" to 1f, "Large" to 1.15f, "Extra Large" to 1.25f).forEach { (label, scale) ->
                            FilterChip(
                                selected = kotlin.math.abs(appearance.textScale - scale) < 0.01f,
                                onClick = { updateAppearance(appearance.copy(textScale = scale)) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (mediaSettingsVisible) {
        MediaSettingsSheet(environment = mediaEnvironment, onDismiss = { mediaSettingsVisible = false })
    }
    if (uploadSettingsVisible) {
        UploadSettingsSheet(
            settings = uploadSettings,
            networks = coordinator.networkStore.all(),
            initialNetworkId = selectedKey?.substringBefore('|'),
            onDismiss = { uploadSettingsVisible = false },
        )
    }
    if (relaySettingsVisible) {
        val relayNetworkId = selectedKey?.substringBefore('|')
        val relayNetwork = networks.firstOrNull { it.id == relayNetworkId }
        if (relayNetwork != null) {
            RelaySettingsSheet(
                networkId = relayNetwork.id,
                networkName = relayNetwork.name,
                store = relayConfigurations,
                onDismiss = { relaySettingsVisible = false },
                onChanged = { relaySettingsRevision += 1 },
            )
        }
    }
    shareChooserBatchId?.let { batchId ->
        ShareDestinationDialog(
            batchId = batchId,
            staged = stagedAttachments,
            buffers = buffers.values,
            networks = networks,
            onChoose = { destination ->
                attachmentStages.assignDestination(batchId, destination)
                dismissedShareBatches = dismissedShareBatches + batchId
                shareChooserBatchId = null
                selectConversation(destination.storageKey)
            },
            onDismiss = {
                dismissedShareBatches = dismissedShareBatches + batchId
                shareChooserBatchId = null
            },
        )
    }


    if (bouncerVisible) {
        BouncerManagementSheet(
            management = coordinator.bouncerManagement,
            configs = coordinator.networkStore.all(),
            initialNetworkId = bouncerInitialId,
            onDismiss = { bouncerVisible = false },
        )
    }

    removingAccountId?.let { accountId ->
        val dependents = coordinator.dependentNetworks(accountId)
        AlertDialog(
            onDismissRequest = { removingAccountId = null },
            title = { Text("Remove bouncer account and upstreams?") },
            text = {
                Text(
                    "Removing this account also removes ${dependents.joinToString { it.name }} and their local history, pins, groups, read markers and mutes. " +
                        "It does not delete the upstream configuration on the bouncer.",
                )
            },
            confirmButton = {
                TextButton(onClick = { removingAccountId = null; coordinator.removeNetwork(accountId) }) { Text("Remove account and upstreams") }
            },
            dismissButton = { TextButton(onClick = { removingAccountId = null }) { Text("Cancel") } },
        )
    }

    whoisPresentation?.let { presentation ->
        val info = presentation.info
        ModalBottomSheet(onDismissRequest = coordinator::dismissWhois) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Whois · ${info.nick}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        metadataFreshnessLabel(presentation.cached, !presentation.offline, presentation.fetchedAtMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (presentation.cached) {
                        Text("Previously observed user information, not current server permissions or presence.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (presentation.refreshing) {
                        Text("Refreshing… showing previously observed information", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!presentation.offline) {
                        TextButton(
                            enabled = !presentation.refreshing,
                            onClick = {
                                coordinator.memberAction(
                                    presentation.networkId,
                                    ConversationRef.server(presentation.networkId).storageKey,
                                    dev.brentdevs.yardhal.ui.screens.MemberAction.WHOIS,
                                    info.nick,
                                )
                            },
                        ) { Text("Refresh WHOIS") }
                    }
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
