package com.bitvoid.network.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitvoid.network.channel.BitVoidSpaceKind
import com.bitvoid.network.channel.BitVoidSpaceRole
import com.bitvoid.network.client.ClientChannelSession
import com.bitvoid.network.client.RemoteChannelSummary
import com.bitvoid.network.client.RemoteSpaceDetails
import com.bitvoid.network.client.RemoteSpaceInvitation
import com.bitvoid.network.client.RemoteSpaceMember
import com.bitvoid.network.ui.theme.VoidAmber
import com.bitvoid.network.ui.theme.VoidCrimson
import com.bitvoid.network.ui.theme.VoidCyan
import com.bitvoid.network.ui.theme.VoidMint
import com.bitvoid.network.ui.theme.VoidMintContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onCreate: (
        name: String,
        description: String,
        members: Collection<String>,
        avatarUri: Uri?,
        onDone: (Result<String>) -> Unit
    ) -> Unit
) {
    CreateSpaceDialog(
        title = "CREATE GROUP",
        nameLabel = "Group name",
        descriptionHint = "What is this group for?",
        submitLabel = "CREATE GROUP",
        isGroup = true,
        onDismiss = onDismiss,
        onSubmit = { name, description, members, _, avatar, done ->
            onCreate(name, description, members, avatar, done)
        }
    )
}

@Composable
fun CreateBroadcastChannelDialog(
    onDismiss: () -> Unit,
    onCreate: (
        name: String,
        description: String,
        isPublic: Boolean,
        avatarUri: Uri?,
        onDone: (Result<String>) -> Unit
    ) -> Unit
) {
    CreateSpaceDialog(
        title = "CREATE CHANNEL",
        nameLabel = "Channel name",
        descriptionHint = "Describe what subscribers will receive",
        submitLabel = "CREATE CHANNEL",
        isGroup = false,
        onDismiss = onDismiss,
        onSubmit = { name, description, _, isPublic, avatar, done ->
            onCreate(name, description, isPublic, avatar, done)
        }
    )
}

@Composable
private fun CreateSpaceDialog(
    title: String,
    nameLabel: String,
    descriptionHint: String,
    submitLabel: String,
    isGroup: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (
        String,
        String,
        Collection<String>,
        Boolean,
        Uri?,
        (Result<String>) -> Unit
    ) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var membersText by rememberSaveable { mutableStateOf("") }
    var isPublic by rememberSaveable { mutableStateOf(true) }
    var avatarUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        avatarUri = it
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = VoidMint,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        cursorColor = VoidMint
    )

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag(if (isGroup) "create_group_dialog" else "create_broadcast_channel_dialog"),
        title = { Text(title, color = VoidMint, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        .clickable(enabled = !isSubmitting) {
                            photoPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SpaceAvatar(
                        dataUrl = null,
                        imageUri = avatarUri,
                        fallback = if (isGroup) Icons.Filled.Group else Icons.Filled.Campaign,
                        modifier = Modifier.size(62.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Profile picture", fontWeight = FontWeight.Bold)
                        Text(
                            if (avatarUri == null) "Optional · tap to select" else "Selected · tap to replace",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Icon(Icons.Filled.AddAPhoto, contentDescription = null, tint = VoidMint)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(48); error = null },
                    label = { Text(nameLabel) },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(500); error = null },
                    label = { Text("Description") },
                    placeholder = { Text(descriptionHint) },
                    minLines = 2,
                    maxLines = 4,
                    supportingText = { Text("${description.length}/500") },
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isGroup) {
                    OutlinedTextField(
                        value = membersText,
                        onValueChange = { membersText = it; error = null },
                        label = { Text("Members by username") },
                        placeholder = { Text("ama, kojo, user-4210") },
                        minLines = 2,
                        maxLines = 4,
                        supportingText = { Text("Separate usernames with commas, spaces, or new lines") },
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isPublic) Icons.Filled.Public else Icons.Filled.VisibilityOff,
                            contentDescription = null,
                            tint = if (isPublic) VoidCyan else VoidAmber
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (isPublic) "Public channel" else "Private channel", fontWeight = FontWeight.Bold)
                            Text(
                                if (isPublic) "Discoverable by everyone" else "Invitation required to join",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = isPublic, onCheckedChange = { isPublic = it })
                    }
                }
                error?.let { Text(it, color = VoidCrimson, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            Button(
                enabled = !isSubmitting,
                colors = ButtonDefaults.buttonColors(containerColor = VoidMint),
                onClick = {
                    if (name.trim().isEmpty()) {
                        error = "Enter a name."
                        return@Button
                    }
                    val members = membersText.split(Regex("[,\\s]+"))
                        .map { it.trim().removePrefix("@") }
                        .filter { it.isNotBlank() }
                        .distinctBy { it.lowercase(Locale.US) }
                    isSubmitting = true
                    onSubmit(name.trim(), description.trim(), members, isPublic, avatarUri) { result ->
                        isSubmitting = false
                        result.onSuccess { onDismiss() }
                            .onFailure { error = it.message ?: "Unable to create this space." }
                    }
                },
                modifier = Modifier.testTag(if (isGroup) "create_group_submit" else "create_channel_submit")
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(submitLabel, color = MaterialTheme.colorScheme.background)
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !isSubmitting, onClick = onDismiss) { Text("CANCEL") }
        }
    )
}

@Composable
fun DiscoverChannelsDialog(
    channels: List<RemoteChannelSummary>,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
    onSubscribe: (String, (Result<String>) -> Unit) -> Unit,
    onJoinInvitation: (String, (Result<String>) -> Unit) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var invitation by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { onSearch("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("discover_channels_dialog"),
        title = { Text("DISCOVER CHANNELS", color = VoidCyan) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; localError = null },
                        label = { Text("Search public channels") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onSearch(query) }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = VoidCyan)
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (channels.isEmpty()) {
                        item {
                            Text(
                                "No public channels match this search.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 18.dp)
                            )
                        }
                    }
                    items(channels, key = { it.channelId ?: it.name }) { channel ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                SpaceAvatar(
                                    dataUrl = channel.avatarDataUrl,
                                    fallback = Icons.Filled.Campaign,
                                    modifier = Modifier.size(44.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(channel.name, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(
                                        channel.description.ifBlank { "${channel.subscriberCount} subscribers" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                val id = channel.channelId
                                if (channel.isSubscribed) {
                                    Icon(Icons.Filled.Check, contentDescription = "Subscribed", tint = VoidMint)
                                } else if (id != null) {
                                    TextButton(
                                        enabled = busyId == null,
                                        onClick = {
                                            busyId = id
                                            onSubscribe(id) { result ->
                                                busyId = null
                                                result.onSuccess { onDismiss() }
                                                    .onFailure { localError = it.message }
                                            }
                                        }
                                    ) { Text(if (busyId == id) "JOINING…" else "SUBSCRIBE") }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("PRIVATE INVITATION", style = MaterialTheme.typography.labelSmall, color = VoidAmber)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = invitation,
                        onValueChange = { invitation = it; localError = null },
                        label = { Text("Invitation code or link") },
                        leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        enabled = invitation.isNotBlank() && busyId == null,
                        onClick = {
                            busyId = "invite"
                            onJoinInvitation(invitation) { result ->
                                busyId = null
                                result.onSuccess { onDismiss() }
                                    .onFailure { localError = it.message }
                            }
                        }
                    ) { Text(if (busyId == "invite") "JOINING…" else "JOIN") }
                }
                (localError ?: errorMessage)?.let {
                    Text(it, color = VoidCrimson, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } },
        dismissButton = {
            TextButton(onClick = { onSearch(query) }) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("REFRESH")
            }
        }
    )
}

@Composable
fun BitVoidSpaceInfoScreen(
    session: ClientChannelSession,
    details: RemoteSpaceDetails?,
    isLoading: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAddMember: (String, (Result<RemoteSpaceDetails>) -> Unit) -> Unit,
    onRemoveMember: (String, (Result<RemoteSpaceDetails>) -> Unit) -> Unit,
    onSetRole: (String, BitVoidSpaceRole, (Result<RemoteSpaceDetails>) -> Unit) -> Unit,
    onUpdate: (
        String,
        String,
        Uri?,
        Boolean,
        Boolean?,
        (Result<RemoteSpaceDetails>) -> Unit
    ) -> Unit,
    onCreateInvitation: ((Result<RemoteSpaceInvitation>) -> Unit) -> Unit,
    onLeave: ((Result<Unit>) -> Unit) -> Unit,
    onDelete: ((Result<Unit>) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    var showEdit by rememberSaveable { mutableStateOf(false) }
    var showAddMember by rememberSaveable { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<RemoteSpaceMember?>(null) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val space = details
    val role = space?.myRole ?: session.role
    val isOwner = role == BitVoidSpaceRole.OWNER
    val isAdmin = role == BitVoidSpaceRole.ADMINISTRATOR
    val canManage = isOwner || isAdmin

    LaunchedEffect(session.channelId) { onRefresh() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                if (session.spaceKind == BitVoidSpaceKind.GROUP) "GROUP INFO" else "CHANNEL INFO",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (isLoading && space == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = VoidMint)
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
        ) {
            item(key = "space_profile") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SpaceAvatar(
                        dataUrl = space?.avatarDataUrl ?: session.avatarDataUrl,
                        fallback = if (session.spaceKind == BitVoidSpaceKind.GROUP) Icons.Filled.Group else Icons.Filled.Campaign,
                        modifier = Modifier.size(92.dp)
                    )
                    Text(space?.name ?: session.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (!(space?.description ?: session.description).isNullOrBlank()) {
                        Text(
                            space?.description ?: session.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        buildString {
                            append(roleLabel(role))
                            append(" · ")
                            if (session.spaceKind == BitVoidSpaceKind.GROUP) {
                                append("${space?.memberCount ?: session.memberCount} members")
                            } else {
                                append("${space?.subscriberCount ?: session.subscriberCount} subscribers")
                                append(if (space?.isPublic == true) " · PUBLIC" else " · PRIVATE")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (space?.isPublic == false) VoidAmber else VoidCyan
                    )
                }
            }
            if (canManage) {
                item(key = "space_manage_actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showEdit = true }, modifier = Modifier.weight(1f)) {
                            Text("EDIT INFO")
                        }
                        if (session.spaceKind == BitVoidSpaceKind.GROUP) {
                            OutlinedButton(onClick = { showAddMember = true }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("ADD")
                            }
                        } else {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    onCreateInvitation { result ->
                                        busy = false
                                        result.onSuccess { invite ->
                                            val send = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(
                                                    Intent.EXTRA_TEXT,
                                                    "Join ${space?.name ?: session.name} on BitVoid: ${invite.inviteLink}\nCode: ${invite.code}"
                                                )
                                            }
                                            context.startActivity(Intent.createChooser(send, "Share channel invitation"))
                                        }.onFailure { localError = it.message }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("INVITE")
                            }
                        }
                    }
                }
            }
            item(key = "space_members_header") {
                Text(
                    if (session.spaceKind == BitVoidSpaceKind.GROUP) "MEMBERS" else "ADMINISTRATORS & SUBSCRIBERS",
                    style = MaterialTheme.typography.labelSmall,
                    color = VoidMint,
                    fontWeight = FontWeight.Bold
                )
            }
            items(space?.members.orEmpty(), key = { it.username.lowercase(Locale.US) }) { member ->
                SpaceMemberRow(
                    member = member,
                    canChangeRole = isOwner && member.role != BitVoidSpaceRole.OWNER,
                    canRemove = canManage && member.role != BitVoidSpaceRole.OWNER &&
                        !(isAdmin && member.role == BitVoidSpaceRole.ADMINISTRATOR),
                    spaceKind = session.spaceKind,
                    busy = busy,
                    onToggleAdmin = {
                        busy = true
                        val regularRole = if (session.spaceKind == BitVoidSpaceKind.GROUP) {
                            BitVoidSpaceRole.MEMBER
                        } else {
                            BitVoidSpaceRole.SUBSCRIBER
                        }
                        val next = if (member.role == BitVoidSpaceRole.ADMINISTRATOR) regularRole
                        else BitVoidSpaceRole.ADMINISTRATOR
                        onSetRole(member.username, next) { result ->
                            busy = false
                            result.onFailure { localError = it.message }
                        }
                    },
                    onRemove = { pendingRemove = member }
                )
            }
            if (space?.members.isNullOrEmpty()) {
                item(key = "space_members_empty") {
                    Text("Member information is unavailable.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "space_danger_actions") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!isOwner) {
                        TextButton(onClick = { confirmLeave = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (session.spaceKind == BitVoidSpaceKind.GROUP) "LEAVE GROUP" else "UNSUBSCRIBE", color = VoidAmber)
                        }
                    }
                    if (isOwner) {
                        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = VoidCrimson)
                            Spacer(Modifier.width(6.dp))
                            Text("DELETE FOR EVERYONE", color = VoidCrimson)
                        }
                    }
                }
            }
            (localError ?: errorMessage)?.let { message ->
                item(key = "space_error") { Text(message, color = VoidCrimson) }
            }
        }
    }

    if (showAddMember) {
        UsernameActionDialog(
            title = "ADD GROUP MEMBER",
            actionLabel = "ADD",
            onDismiss = { showAddMember = false },
            onSubmit = { username, done -> onAddMember(username, done) }
        )
    }
    if (showEdit && space != null) {
        EditSpaceDialog(
            details = space,
            onDismiss = { showEdit = false },
            onSave = { name, description, avatar, keepAvatar, public, done ->
                onUpdate(name, description, avatar, keepAvatar, public, done)
            }
        )
    }
    pendingRemove?.let { member ->
        ConfirmDialog(
            title = "Remove @${member.username}?",
            detail = "They will immediately lose access to this space.",
            actionLabel = "REMOVE",
            onDismiss = { pendingRemove = null },
            onConfirm = {
                busy = true
                onRemoveMember(member.username) { result ->
                    busy = false
                    pendingRemove = null
                    result.onFailure { localError = it.message }
                }
            }
        )
    }
    if (confirmLeave) {
        ConfirmDialog(
            title = if (session.spaceKind == BitVoidSpaceKind.GROUP) "Leave this group?" else "Unsubscribe from this channel?",
            detail = "You will no longer receive messages from this space.",
            actionLabel = "LEAVE",
            onDismiss = { confirmLeave = false },
            onConfirm = {
                busy = true
                onLeave { result ->
                    busy = false
                    confirmLeave = false
                    result.onSuccess { onBack() }.onFailure { localError = it.message }
                }
            }
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete for everyone?",
            detail = "This permanently removes the space and its server-side message history.",
            actionLabel = "DELETE",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                busy = true
                onDelete { result ->
                    busy = false
                    confirmDelete = false
                    result.onSuccess { onBack() }.onFailure { localError = it.message }
                }
            }
        )
    }
}

@Composable
private fun SpaceMemberRow(
    member: RemoteSpaceMember,
    canChangeRole: Boolean,
    canRemove: Boolean,
    spaceKind: BitVoidSpaceKind,
    busy: Boolean,
    onToggleAdmin: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(VoidMintContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(member.username.take(2).uppercase(Locale.US), color = VoidMint, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("@${member.username}", fontWeight = FontWeight.SemiBold)
                Text(roleLabel(member.role), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (canChangeRole) {
                TextButton(enabled = !busy, onClick = onToggleAdmin) {
                    Text(if (member.role == BitVoidSpaceRole.ADMINISTRATOR) "DEMOTE" else "MAKE ADMIN")
                }
            }
            if (canRemove) {
                IconButton(enabled = !busy, onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove ${member.username}", tint = VoidCrimson)
                }
            }
        }
    }
}

@Composable
private fun UsernameActionDialog(
    title: String,
    actionLabel: String,
    onDismiss: () -> Unit,
    onSubmit: (String, (Result<RemoteSpaceDetails>) -> Unit) -> Unit
) {
    var username by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; error = null },
                    label = { Text("Username") },
                    singleLine = true
                )
                error?.let { Text(it, color = VoidCrimson) }
            }
        },
        confirmButton = {
            Button(
                enabled = username.trim().isNotEmpty() && !busy,
                onClick = {
                    busy = true
                    onSubmit(username.trim().removePrefix("@")) { result ->
                        busy = false
                        result.onSuccess { onDismiss() }.onFailure { error = it.message }
                    }
                }
            ) { Text(if (busy) "ADDING…" else actionLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

@Composable
private fun EditSpaceDialog(
    details: RemoteSpaceDetails,
    onDismiss: () -> Unit,
    onSave: (String, String, Uri?, Boolean, Boolean?, (Result<RemoteSpaceDetails>) -> Unit) -> Unit
) {
    var name by rememberSaveable(details.channelId) { mutableStateOf(details.name) }
    var description by rememberSaveable(details.channelId) { mutableStateOf(details.description) }
    var isPublic by rememberSaveable(details.channelId) { mutableStateOf(details.isPublic) }
    var avatarUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var keepAvatar by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) {
            avatarUri = it
            keepAvatar = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("EDIT SPACE INFO") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SpaceAvatar(
                        dataUrl = if (keepAvatar) details.avatarDataUrl else null,
                        imageUri = avatarUri,
                        fallback = if (details.spaceKind == BitVoidSpaceKind.GROUP) Icons.Filled.Group else Icons.Filled.Campaign,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Tap to change profile picture", modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.AddAPhoto, contentDescription = null, tint = VoidMint)
                }
                OutlinedTextField(name, { name = it.take(48) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    description,
                    { description = it.take(500) },
                    label = { Text("Description") },
                    minLines = 2,
                    maxLines = 4
                )
                if (details.spaceKind == BitVoidSpaceKind.BROADCAST_CHANNEL && details.myRole == BitVoidSpaceRole.OWNER) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (isPublic) Icons.Filled.Public else Icons.Filled.VisibilityOff, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isPublic) "Public" else "Private", modifier = Modifier.weight(1f))
                        Switch(checked = isPublic, onCheckedChange = { isPublic = it })
                    }
                }
                error?.let { Text(it, color = VoidCrimson) }
            }
        },
        confirmButton = {
            Button(
                enabled = name.trim().isNotEmpty() && !busy,
                onClick = {
                    busy = true
                    onSave(
                        name.trim(),
                        description.trim(),
                        avatarUri,
                        keepAvatar,
                        if (details.spaceKind == BitVoidSpaceKind.BROADCAST_CHANNEL) isPublic else null
                    ) { result ->
                        busy = false
                        result.onSuccess { onDismiss() }.onFailure { error = it.message }
                    }
                }
            ) { Text(if (busy) "SAVING…" else "SAVE") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    detail: String,
    actionLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = VoidCrimson)
            ) { Text(actionLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

@Composable
fun SpaceAvatar(
    dataUrl: String?,
    modifier: Modifier = Modifier,
    imageUri: Uri? = null,
    fallback: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Group
) {
    val context = LocalContext.current
    val image = remember(dataUrl, imageUri) {
        runCatching {
            val bytes = when {
                imageUri != null -> context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                !dataUrl.isNullOrBlank() -> Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
                else -> null
            } ?: return@runCatching null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(VoidMintContainer)
            .border(1.dp, VoidMint.copy(alpha = 0.65f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(fallback, contentDescription = null, tint = VoidMint, modifier = Modifier.size(28.dp))
        }
    }
}

private fun roleLabel(role: BitVoidSpaceRole?): String = when (role) {
    BitVoidSpaceRole.OWNER -> "Owner"
    BitVoidSpaceRole.ADMINISTRATOR -> "Administrator"
    BitVoidSpaceRole.MEMBER -> "Member"
    BitVoidSpaceRole.SUBSCRIBER -> "Subscriber"
    null -> "Participant"
}

private fun invitationExpiry(expiresAtMs: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(expiresAtMs))
