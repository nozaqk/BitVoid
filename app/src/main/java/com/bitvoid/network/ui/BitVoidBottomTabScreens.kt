package com.bitvoid.network.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitvoid.network.model.AppBottomTab
import com.bitvoid.network.ui.theme.VoidBorder
import com.bitvoid.network.ui.theme.VoidCyan
import com.bitvoid.network.ui.theme.VoidMint
import com.bitvoid.network.ui.theme.VoidObsidian
import com.bitvoid.network.ui.theme.VoidSurface
import com.bitvoid.network.ui.theme.VoidTextPrimary
import com.bitvoid.network.ui.theme.VoidTextSecondary

@Composable
fun BitVoidBottomNavigationBar(
    selectedTab: AppBottomTab,
    onSelectTab: (AppBottomTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp
        ) {
            AppBottomTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                val selectedIcon = when (tab) {
                    AppBottomTab.HOME -> Icons.Filled.Forum
                    AppBottomTab.HUB -> Icons.Filled.Groups
                    AppBottomTab.PCHAT -> Icons.Filled.Person
                    AppBottomTab.SETTINGS -> Icons.Filled.Settings
                }
                val unselectedIcon = when (tab) {
                    AppBottomTab.HOME -> Icons.Outlined.Forum
                    AppBottomTab.HUB -> Icons.Filled.Groups
                    AppBottomTab.PCHAT -> Icons.Filled.Person
                    AppBottomTab.SETTINGS -> Icons.Outlined.Settings
                }
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelectTab(tab) },
                    icon = {
                        Icon(
                            imageVector = if (selected) selectedIcon else unselectedIcon,
                            contentDescription = tab.label,
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    label = {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("bottom_nav_${tab.name.lowercase()}")
                )
            }
        }
    }
}

@Composable
fun SettingsUserIdentityCard(
    username: String,
    isDiscoverable: Boolean,
    onSetDiscoverable: (Boolean) -> Unit,
    onCopyUsername: (String, String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_user_identity_card"),
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(
            1.dp,
            if (isDiscoverable) VoidMint.copy(alpha = 0.65f) else VoidBorder
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "USER ID · @$username",
                    style = MaterialTheme.typography.labelLarge,
                    color = VoidTextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("settings_username_value")
                )
                Text(
                    text = if (isDiscoverable) {
                        "Shown automatically to users on this server"
                    } else {
                        "Hidden from automatic user lists"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isDiscoverable) VoidMint else VoidTextSecondary,
                    maxLines = 2
                )
            }
            IconButton(
                onClick = { onCopyUsername("User ID", username) },
                modifier = Modifier
                    .size(40.dp)
                    .testTag("settings_copy_username_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy User ID",
                    tint = VoidCyan,
                    modifier = Modifier.size(17.dp)
                )
            }
            Switch(
                checked = isDiscoverable,
                onCheckedChange = onSetDiscoverable,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = VoidObsidian,
                    checkedTrackColor = VoidMint,
                    uncheckedThumbColor = VoidTextSecondary,
                    uncheckedTrackColor = VoidBorder
                ),
                modifier = Modifier.testTag("settings_user_discoverability_switch")
            )
        }
    }
}
