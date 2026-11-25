package com.bitvoid.network.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitvoid.network.channel.BitVoidChannel
import com.bitvoid.network.media.BitVoidMediaItem
import com.bitvoid.network.model.AppBottomTab
import com.bitvoid.network.model.ClientState
import com.bitvoid.network.model.HostState
import com.bitvoid.network.ui.theme.VoidAmber
import com.bitvoid.network.ui.theme.VoidBorder
import com.bitvoid.network.ui.theme.VoidCrimson
import com.bitvoid.network.ui.theme.VoidCyan
import com.bitvoid.network.ui.theme.VoidMint
import com.bitvoid.network.ui.theme.VoidObsidian
import com.bitvoid.network.ui.theme.VoidSurface
import com.bitvoid.network.ui.theme.VoidTextPrimary
import com.bitvoid.network.ui.theme.VoidTextSecondary
import com.bitvoid.network.R
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun BitVoidDashboard(
    viewModel: BitVoidViewModel
) {
    val selectedBottomTab by viewModel.selectedBottomTab.collectAsStateWithLifecycle()
    val backendState by viewModel.backendState.collectAsStateWithLifecycle()
    val unreadCounts by viewModel.unreadCounts.collectAsStateWithLifecycle()
    val hostState by viewModel.hostState.collectAsStateWithLifecycle()
    val clientState by viewModel.clientState.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()

    val clientChannels by viewModel.clientChannels.collectAsStateWithLifecycle()
    val discoveredChannels by viewModel.discoveredChannels.collectAsStateWithLifecycle()
    val activeSpaceDetails by viewModel.activeSpaceDetails.collectAsStateWithLifecycle()
    val isLoadingSpaceDetails by viewModel.isLoadingSpaceDetails.collectAsStateWithLifecycle()
    val spaceError by viewModel.spaceError.collectAsStateWithLifecycle()
    val cachedConversationPreviews by viewModel.cachedConversationPreviews.collectAsStateWithLifecycle()
    val activeChannelSession by viewModel.activeChannelSession.collectAsStateWithLifecycle()
    val channelMessages by viewModel.channelMessages.collectAsStateWithLifecycle()
    val clientWsStatus by viewModel.clientWsStatus.collectAsStateWithLifecycle()
    val channelPeersCount by viewModel.channelPeersCount.collectAsStateWithLifecycle()
    val isJoiningChannel by viewModel.isJoiningChannel.collectAsStateWithLifecycle()
    val channelJoinError by viewModel.channelJoinError.collectAsStateWithLifecycle()

    val myUsername by viewModel.myUsername.collectAsStateWithLifecycle()
    val isUserIdDiscoverable by viewModel.isUserIdDiscoverable.collectAsStateWithLifecycle()
    val hasCompletedAccountAccess by viewModel.hasCompletedAccountAccess.collectAsStateWithLifecycle()
    val isCompletingAccountAccess by viewModel.isCompletingAccountAccess.collectAsStateWithLifecycle()
    val accountAccessError by viewModel.accountAccessError.collectAsStateWithLifecycle()
    val masterPassphrase by viewModel.masterPassphrase.collectAsStateWithLifecycle()
    val directConversations by viewModel.directConversations.collectAsStateWithLifecycle()
    val knownUsers by viewModel.knownUsers.collectAsStateWithLifecycle()
    val activeDirectSession by viewModel.activeDirectSession.collectAsStateWithLifecycle()
    val directMessages by viewModel.directMessages.collectAsStateWithLifecycle()
    val directWsStatus by viewModel.directWsStatus.collectAsStateWithLifecycle()
    val isOpeningDirectChat by viewModel.isOpeningDirectChat.collectAsStateWithLifecycle()
    val directChatError by viewModel.directChatError.collectAsStateWithLifecycle()
    val isUploadingChatFile by viewModel.isUploadingChatFile.collectAsStateWithLifecycle()
    val fileUploadError by viewModel.fileUploadError.collectAsStateWithLifecycle()
    val callUiState by viewModel.callUiState.collectAsStateWithLifecycle()
    val liveClasses by viewModel.liveClasses.collectAsStateWithLifecycle()

    if (!backendState.isConnected) {
        BitVoidConnectionGateScreen(
            state = backendState,
            onRetry = viewModel::refreshMessagingData,
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    if (!hasCompletedAccountAccess) {
        BitVoidAccountAccessScreen(
            backendState = backendState,
            suggestedUsername = myUsername,
            isSubmitting = isCompletingAccountAccess,
            errorMessage = accountAccessError,
            onSubmit = viewModel::completeAccountAccess,
            onClearError = viewModel::clearAccountAccessError,
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    val callActions = remember(viewModel) {
        BitVoidCallActions(
            onStartVoiceCall = { channelId, channelName, token ->
                viewModel.startVoiceCall(channelId, channelName, token)
            },
            onStartLiveClassHost = { channelId, channelName, token ->
                viewModel.startLiveClassAsHost(channelId, channelName, token)
            },
            onJoinLiveClassStudent = { channelId, channelName, token ->
                viewModel.joinLiveClassAsStudent(channelId, channelName, token)
            },
            onJoinLiveClassWithCode = { classCode, onDone ->
                viewModel.joinLiveClassByCode(classCode, onDone)
            },
            onStartDirectCall = { dmId, peerUsername, token ->
                viewModel.startDirectCall(dmId, peerUsername, token)
            },
            onAcceptDirectCall = { dmId, peerUsername, token ->
                viewModel.acceptDirectCall(dmId, peerUsername, token)
            },
            onDeclineDirectCall = { dmId, token ->
                viewModel.declineDirectCall(dmId, token)
            },
            onCancelDirectCall = { dmId, token ->
                viewModel.cancelDirectCall(dmId, token)
            },
            onToggleMicMute = { viewModel.toggleCallMicMute() },
            onToggleSpeakerphone = { viewModel.toggleCallSpeakerphone() },
            onLeaveSession = { viewModel.leaveCallSession() },
            onEndClassroomSession = { viewModel.endLiveClassSession() },
            onClearCallError = { viewModel.clearCallError() }
        )
    }

    var showChannelDialog by rememberSaveable { mutableStateOf(false) }
    var showCreateGroupDialog by rememberSaveable { mutableStateOf(false) }
    var showCreateBroadcastDialog by rememberSaveable { mutableStateOf(false) }
    var showDiscoverChannelsDialog by rememberSaveable { mutableStateOf(false) }
    var editingChannelId by rememberSaveable { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(fileUploadError) {
        fileUploadError?.let { err ->
            snackbarHostState.showSnackbar(err)
            viewModel.clearFileUploadError()
        }
    }

    fun copyToClipboard(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        coroutineScope.launch {
            snackbarHostState.showSnackbar("$label copied to clipboard")
        }
    }

    val settingsIdentityContent: @Composable () -> Unit = {
        SettingsUserIdentityCard(
            username = myUsername,
            isDiscoverable = isUserIdDiscoverable,
            onSetDiscoverable = { viewModel.setUserIdDiscoverable(it) },
            onCopyUsername = ::copyToClipboard,
            masterPassphrase = masterPassphrase,
            onExportMasterPassphrase = viewModel::exportMasterPassphrase
        )
    }

    val pChatScreenContent: @Composable () -> Unit = {
        BitVoidPrivateChatScreen(
            myUsername = myUsername,
            directConversations = directConversations,
            knownUsers = knownUsers,
            activeDirectSession = activeDirectSession,
            directMessages = directMessages,
            directWsStatus = directWsStatus,
            isOpeningDirectChat = isOpeningDirectChat,
            directChatError = directChatError,
            isUploadingFile = isUploadingChatFile,
            callUiState = callUiState,
            callActions = callActions,
            onOpenDirectChat = { target -> viewModel.openDirectChat(target) },
            onSendDirectMessage = { text -> viewModel.sendDirectChatMessage(text) },
            onLeaveDirectChat = { viewModel.leaveDirectChat() },
            onClearDirectChatError = { viewModel.clearDirectChatError() },
            onUploadDirectFile = { uri, caption ->
                viewModel.uploadFileToActiveDirectChat(uri, caption)
            },
            onBuildAttachmentUrl = { path, token -> viewModel.buildAttachmentUrl(path, token) }
        )
    }

    val chatsScreenContent: @Composable () -> Unit = {
        BitVoidChatsScreen(
            myUsername = myUsername,
            backendState = backendState,
            hostChannels = channels,
            remoteChannels = clientChannels,
            directConversations = directConversations,
            cachedConversations = cachedConversationPreviews,
            knownUsers = knownUsers,
            unreadCounts = unreadCounts,
            onRefresh = viewModel::refreshMessagingData,
            onOpenChannel = { channelId ->
                viewModel.markConversationRead(channelId)
                viewModel.selectBottomTab(AppBottomTab.HUB)
                viewModel.openHubChannel(channelId)
            },
            onOpenDirectChat = { username ->
                val conversationId = directConversations
                    .firstOrNull { it.peerUsername.equals(username, ignoreCase = true) }
                    ?.dmId
                if (conversationId != null) viewModel.markConversationRead(conversationId)
                viewModel.selectBottomTab(AppBottomTab.PCHAT)
                viewModel.openDirectChat(username)
            },
            onCreateGroup = { showCreateGroupDialog = true },
            onCreateBroadcastChannel = { showCreateBroadcastDialog = true },
            onDiscoverChannels = { showDiscoverChannelsDialog = true }
        )
    }

    val hubScreenContent: @Composable () -> Unit = {
        BitVoidHubScreen(
            clientState = clientState,
            myUsername = myUsername,
            hostChannels = channels,
            remoteChannels = clientChannels,
            directConversations = directConversations,
            knownUsers = knownUsers,
            activeChannelSession = activeChannelSession,
            channelMessages = channelMessages,
            wsStatus = clientWsStatus,
            channelPeersCount = channelPeersCount,
            isJoiningChannel = isJoiningChannel,
            channelJoinError = channelJoinError,
            activeSpaceDetails = activeSpaceDetails,
            isLoadingSpaceDetails = isLoadingSpaceDetails,
            spaceError = spaceError,
            activeDirectSession = activeDirectSession,
            directMessages = directMessages,
            directWsStatus = directWsStatus,
            isOpeningDirectChat = isOpeningDirectChat,
            directChatError = directChatError,
            onOpenCreateChannel = {
                editingChannelId = null
                showChannelDialog = true
            },
            onOpenCreateGroup = { showCreateGroupDialog = true },
            onOpenCreateBroadcastChannel = { showCreateBroadcastDialog = true },
            onOpenDiscoverChannels = { showDiscoverChannelsDialog = true },
            onEditChannel = { channelId ->
                editingChannelId = channelId
                showChannelDialog = true
            },
            onDeleteChannel = { channelId ->
                viewModel.deleteChannel(channelId)
                    .onFailure { error ->
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                error.message ?: "Unable to delete channel"
                            )
                        }
                    }
            },
            onCopyChannelId = ::copyToClipboard,
            onOpenChannel = { channelId, privateKey ->
                viewModel.openHubChannel(channelId, privateKey)
            },
            onSendChatMessage = { viewModel.sendChannelChatMessage(it) },
            onLeaveChannel = { viewModel.leaveChannel() },
            onRefreshSpaceDetails = viewModel::refreshActiveSpaceDetails,
            onAddSpaceMember = viewModel::addActiveGroupMember,
            onRemoveSpaceMember = viewModel::removeActiveSpaceMember,
            onSetSpaceMemberRole = viewModel::setActiveSpaceMemberRole,
            onUpdateSpace = viewModel::updateActiveSpace,
            onCreateSpaceInvitation = viewModel::createActiveChannelInvitation,
            onLeaveSpace = viewModel::leaveActiveSpace,
            onDeleteSpace = viewModel::deleteActiveSpace,
            onOpenDirectChat = { target -> viewModel.openDirectChat(target) },
            onSendDirectMessage = { text -> viewModel.sendDirectChatMessage(text) },
            onLeaveDirectChat = { viewModel.leaveDirectChat() },
            onClearDirectChatError = { viewModel.clearDirectChatError() },
            onCopyUsername = ::copyToClipboard,
            onBuildStreamUrl = { item, token -> viewModel.buildClientStreamUrl(item, token) },
            isUploadingFile = isUploadingChatFile,
            onUploadChannelFile = { uri, caption -> viewModel.uploadFileToActiveChannel(uri, caption) },
            onUploadDirectFile = { uri, caption -> viewModel.uploadFileToActiveDirectChat(uri, caption) },
            onBuildAttachmentUrl = { path, token -> viewModel.buildAttachmentUrl(path, token) },
            callUiState = callUiState,
            callActions = callActions,
            liveClasses = liveClasses,
            onStartLiveClass = { name, isHidden, onDone ->
                viewModel.startNamedLiveClass(name, isHidden, onDone)
            },
            onOpenListedLiveClass = { liveClass ->
                viewModel.openListedLiveClass(liveClass)
            }
        )
    }

    val isInsideActiveConversation =
        (selectedBottomTab == AppBottomTab.HUB &&
            (activeChannelSession != null ||
                (callUiState.activeRoomId != null &&
                    !callUiState.activeRoomId!!.startsWith("dm__") &&
                    callUiState.callState != com.bitvoid.network.call.BitVoidCallState.IDLE))) ||
            (selectedBottomTab == AppBottomTab.PCHAT && activeDirectSession != null)

    BackHandler(
        enabled = selectedBottomTab != AppBottomTab.HOME && !isInsideActiveConversation
    ) {
        viewModel.selectBottomTab(AppBottomTab.HOME)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (!isInsideActiveConversation) {
                BitVoidBottomNavigationBar(
                    selectedTab = selectedBottomTab,
                    onSelectTab = { viewModel.selectBottomTab(it) }
                )
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            val isWideLayout = maxWidth >= 720.dp

            if (isWideLayout) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 1120.dp)
                        .align(Alignment.TopCenter)
                ) {
                    WideDashboardContent(
                        selectedBottomTab = selectedBottomTab,
                        chatsScreenContent = chatsScreenContent,
                        hubScreenContent = hubScreenContent,
                        pChatScreenContent = pChatScreenContent,
                        settingsIdentityContent = settingsIdentityContent
                    )
                }
            } else {
                CompactDashboardContent(
                    selectedBottomTab = selectedBottomTab,
                    chatsScreenContent = chatsScreenContent,
                    hubScreenContent = hubScreenContent,
                    pChatScreenContent = pChatScreenContent,
                    settingsIdentityContent = settingsIdentityContent,
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 1120.dp)
                        .align(Alignment.TopCenter)
                )
            }
        }
    }

    if (showCreateGroupDialog) {
        CreateGroupDialog(
            onDismiss = { showCreateGroupDialog = false },
            onCreate = { name, description, members, avatar, done ->
                viewModel.createGroup(name, description, members, avatar) { result ->
                    if (result.isSuccess) viewModel.selectBottomTab(AppBottomTab.HUB)
                    done(result)
                }
            }
        )
    }

    if (showCreateBroadcastDialog) {
        CreateBroadcastChannelDialog(
            onDismiss = { showCreateBroadcastDialog = false },
            onCreate = { name, description, isPublic, avatar, done ->
                viewModel.createBroadcastChannel(name, description, isPublic, avatar) { result ->
                    if (result.isSuccess) viewModel.selectBottomTab(AppBottomTab.HUB)
                    done(result)
                }
            }
        )
    }

    if (showDiscoverChannelsDialog) {
        DiscoverChannelsDialog(
            channels = discoveredChannels,
            errorMessage = spaceError,
            onDismiss = {
                showDiscoverChannelsDialog = false
                viewModel.clearSpaceError()
            },
            onSearch = viewModel::discoverBroadcastChannels,
            onSubscribe = { channelId, done ->
                viewModel.subscribeToBroadcastChannel(channelId) { result ->
                    if (result.isSuccess) viewModel.selectBottomTab(AppBottomTab.HUB)
                    done(result)
                }
            },
            onJoinInvitation = { invitation, done ->
                viewModel.joinBroadcastInvitation(invitation) { result ->
                    if (result.isSuccess) viewModel.selectBottomTab(AppBottomTab.HUB)
                    done(result)
                }
            }
        )
    }

    if (showChannelDialog) {
        val editingChannel = editingChannelId?.let { target -> channels.find { it.channelId == target } }
        ChannelEditorDialog(
            existingChannel = editingChannel,
            suggestedPrivateId = remember(showChannelDialog, editingChannelId) {
                editingChannel?.channelId ?: viewModel.suggestPrivateChannelId()
            },
            vaultItems = hostState.sharedFiles,
            onSuggestPrivateId = { viewModel.suggestPrivateChannelId() },
            onDismiss = {
                showChannelDialog = false
                editingChannelId = null
            },
            onSave = { name, isPublic, isHidden, customChannelId, privateKey, selectedMediaIds ->
                if (editingChannel != null) {
                    val effectivePublic = isPublic && !isHidden
                    val result = viewModel.updateChannel(
                        channelId = editingChannel.channelId,
                        name = name,
                        isPublic = effectivePublic,
                        isHidden = isHidden,
                        privateKey = if (effectivePublic) null else privateKey,
                        mediaIds = selectedMediaIds
                    )
                    if (result.isSuccess) {
                        showChannelDialog = false
                        editingChannelId = null
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Updated channel #$name")
                        }
                        null
                    } else {
                        result.exceptionOrNull()?.message ?: "Unable to update channel"
                    }
                } else {
                    val effectivePublic = isPublic && !isHidden
                    val result = viewModel.createChannel(
                        name = name,
                        isPublic = effectivePublic,
                        customChannelId = if (effectivePublic) null else customChannelId,
                        privateKey = if (effectivePublic) null else privateKey,
                        mediaIds = selectedMediaIds,
                        isHidden = isHidden
                    )
                    if (result.isSuccess) {
                        val created = result.getOrNull()
                        showChannelDialog = false
                        editingChannelId = null
                        if (created != null && (!created.isPublic || created.isHidden)) {
                            copyToClipboard(
                                if (created.isHidden) "Hidden Channel ID" else "Private Channel ID",
                                created.channelId
                            )
                        } else {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Public Channel #${created?.name} created")
                            }
                        }
                        null
                    } else {
                        result.exceptionOrNull()?.message ?: "Unable to create channel"
                    }
                }
            }
        )
    }
}

@Composable
private fun CompactDashboardContent(
    selectedBottomTab: AppBottomTab,
    chatsScreenContent: @Composable () -> Unit,
    hubScreenContent: @Composable () -> Unit,
    pChatScreenContent: @Composable () -> Unit,
    settingsIdentityContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selectedBottomTab == AppBottomTab.HUB) {
        Box(modifier = modifier.fillMaxSize()) {
            hubScreenContent()
        }
        return
    }
    if (selectedBottomTab == AppBottomTab.PCHAT) {
        Box(modifier = modifier.fillMaxSize()) {
            pChatScreenContent()
        }
        return
    }
    if (selectedBottomTab == AppBottomTab.HOME) {
        Box(modifier = modifier.fillMaxSize()) {
            chatsScreenContent()
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        when (selectedBottomTab) {
            AppBottomTab.HOME -> Unit

            AppBottomTab.HUB -> Unit

            AppBottomTab.PCHAT -> Unit

            AppBottomTab.SETTINGS -> {
                item(key = "settings_user_identity") {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        settingsIdentityContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun WideDashboardContent(
    selectedBottomTab: AppBottomTab,
    chatsScreenContent: @Composable () -> Unit,
    hubScreenContent: @Composable () -> Unit,
    pChatScreenContent: @Composable () -> Unit,
    settingsIdentityContent: @Composable () -> Unit
) {
    when (selectedBottomTab) {
        AppBottomTab.HOME -> {
            Box(modifier = Modifier.fillMaxSize()) {
                chatsScreenContent()
            }
        }

        AppBottomTab.HUB -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 860.dp)
                ) {
                    hubScreenContent()
                }
            }
        }

        AppBottomTab.PCHAT -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 860.dp)
                ) {
                    pChatScreenContent()
                }
            }
        }

        AppBottomTab.SETTINGS -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    settingsIdentityContent()
                }
            }
        }
    }
}

@Composable
private fun ChannelEditorDialog(
    existingChannel: BitVoidChannel?,
    suggestedPrivateId: String,
    vaultItems: List<BitVoidMediaItem>,
    onSuggestPrivateId: () -> String,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        isPublic: Boolean,
        isHidden: Boolean,
        customChannelId: String?,
        privateKey: String?,
        selectedMediaIds: Set<String>
    ) -> String?
) {
    var channelName by rememberSaveable(existingChannel?.channelId) {
        mutableStateOf(existingChannel?.name ?: "")
    }
    var isPublic by rememberSaveable(existingChannel?.channelId) {
        mutableStateOf(existingChannel?.isPublic ?: true)
    }
    var isHidden by rememberSaveable(existingChannel?.channelId) {
        mutableStateOf(existingChannel?.isHidden ?: false)
    }
    var privateChannelId by rememberSaveable(existingChannel?.channelId) {
        mutableStateOf(existingChannel?.channelId ?: suggestedPrivateId)
    }
    var privateKey by rememberSaveable(existingChannel?.channelId) {
        mutableStateOf(existingChannel?.privateKey.orEmpty())
    }
    var selectedMediaIds by remember(existingChannel?.channelId) {
        mutableStateOf<Set<String>>(existingChannel?.mediaList?.map { it.id }?.toSet() ?: emptySet())
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = VoidMint,
        unfocusedBorderColor = VoidBorder,
        focusedLabelColor = VoidMint,
        unfocusedLabelColor = VoidTextSecondary,
        focusedTextColor = VoidTextPrimary,
        unfocusedTextColor = VoidTextPrimary,
        cursorColor = VoidMint
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VoidSurface,
        titleContentColor = VoidMint,
        textContentColor = VoidTextPrimary,
        title = {
            Text(
                text = if (existingChannel != null) {
                    "EDIT CHANNEL // #${existingChannel.name}"
                } else {
                    "CREATE NEW CHANNEL"
                },
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                        value = channelName,
                        onValueChange = {
                            channelName = it
                            validationError = null
                        },
                        label = { Text("Channel Name (e.g. Field Briefings)") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("channel_name_dialog_input")
                    )

                    Text(
                        text = "CHANNEL ACCESS",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidCyan
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isPublic = true
                                isHidden = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPublic && !isHidden) VoidMint else VoidObsidian,
                                contentColor = if (isPublic && !isHidden) VoidObsidian else VoidTextSecondary
                            ),
                            border = BorderStroke(1.dp, if (isPublic && !isHidden) VoidMint else VoidBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("channel_toggle_public")
                        ) {
                            Text("PUBLIC", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = { isPublic = false },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!isPublic || isHidden) VoidAmber else VoidObsidian,
                                contentColor = if (!isPublic || isHidden) VoidObsidian else VoidTextSecondary
                            ),
                            border = BorderStroke(1.dp, if (!isPublic || isHidden) VoidAmber else VoidBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("channel_toggle_private")
                        ) {
                            Text("PRIVATE (ID)", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Text(
                        text = "HUB DIRECTORY VISIBILITY",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidCyan
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { isHidden = false },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!isHidden) VoidCyan else VoidObsidian,
                                contentColor = if (!isHidden) VoidObsidian else VoidTextSecondary
                            ),
                            border = BorderStroke(1.dp, if (!isHidden) VoidCyan else VoidBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("channel_toggle_show")
                        ) {
                            Text("SHOW", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = {
                                isHidden = true
                                isPublic = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isHidden) VoidCrimson else VoidObsidian,
                                contentColor = if (isHidden) VoidTextPrimary else VoidTextSecondary
                            ),
                            border = BorderStroke(1.dp, if (isHidden) VoidCrimson else VoidBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("channel_toggle_hidden")
                        ) {
                            Text("HIDDEN", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    if (isHidden) {
                        Text(
                            text = "Hidden from other users' Hub lists. You will still see and open your own hidden channel; others need its exact Private Channel ID.",
                            style = MaterialTheme.typography.labelSmall,
                            color = VoidAmber
                        )
                    }

                    AnimatedVisibility(visible = !isPublic || isHidden) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = privateChannelId,
                                    onValueChange = {
                                        privateChannelId = it
                                        validationError = null
                                    },
                                    label = {
                                        Text(if (existingChannel == null) "Private Channel ID" else "Channel ID (locked)")
                                    },
                                    singleLine = true,
                                    enabled = existingChannel == null,
                                    colors = fieldColors,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("channel_id_dialog_input")
                                )
                                OutlinedButton(
                                    onClick = { privateChannelId = onSuggestPrivateId() },
                                    enabled = existingChannel == null,
                                    border = BorderStroke(1.dp, VoidAmber),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidAmber),
                                    modifier = Modifier.height(52.dp)
                                ) {
                                    Text("GEN", style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            OutlinedTextField(
                                value = privateKey,
                                onValueChange = { privateKey = it },
                                label = { Text("Optional Private Key (blank = ID only)") },
                                singleLine = true,
                                colors = fieldColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("channel_key_dialog_input")
                            )
                        }
                    }

                Text(
                    text = "ASSIGN VAULT MEDIA (${selectedMediaIds.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = VoidCyan
                )

                if (vaultItems.isEmpty()) {
                    Text(
                        text = "Vault is empty. Import videos anytime and assign them right from the Vault cards.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VoidTextSecondary
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(VoidObsidian)
                            .border(1.dp, VoidBorder, RoundedCornerShape(8.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        vaultItems.forEach { item ->
                            val isChecked = item.id in selectedMediaIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        selectedMediaIds = if (isChecked) {
                                            selectedMediaIds - item.id
                                        } else {
                                            selectedMediaIds + item.id
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selectedMediaIds = if (checked) {
                                            selectedMediaIds + item.id
                                        } else {
                                            selectedMediaIds - item.id
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = VoidMint,
                                        checkmarkColor = VoidObsidian,
                                        uncheckedColor = VoidTextSecondary
                                    )
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = VoidTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${item.category.badge} · ${formatBytes(item.size)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VoidTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                validationError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VoidCrimson
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    validationError = onSave(
                        channelName,
                        isPublic,
                        isHidden,
                        privateChannelId,
                        privateKey.ifBlank { null },
                        selectedMediaIds
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = VoidMint,
                    contentColor = VoidObsidian
                ),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("confirm_create_channel_button")
            ) {
                Text(
                    text = if (existingChannel != null) "SAVE MEDIA" else "CREATE CHANNEL",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    text = "CANCEL",
                    style = MaterialTheme.typography.labelLarge,
                    color = VoidTextSecondary
                )
            }
        }
    )
}

private fun formatDuration(totalSeconds: Long): String {
    val hrs = totalSeconds / 3600
    val mins = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hrs > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
