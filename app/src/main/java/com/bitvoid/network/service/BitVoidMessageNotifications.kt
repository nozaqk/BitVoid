package com.bitvoid.network.service

import com.bitvoid.network.client.ClientIncomingMessageEvent

internal enum class MessageNotificationDestination(val wireValue: String) {
    CHANNEL("channel"),
    DIRECT("direct")
}

internal data class MessageNotificationSpec(
    val destination: MessageNotificationDestination,
    val conversationId: String,
    val peerUsername: String?,
    val title: String,
    val body: String,
    val notificationId: Int
)

/** Pure notification policy kept separate from Android delivery so every exclusion is testable. */
internal object BitVoidMessageNotifications {
    private const val CHANNEL_ID_BASE = 10_000
    private const val DIRECT_ID_BASE = 510_000
    private const val ID_RANGE = 490_000

    fun createSpec(event: ClientIncomingMessageEvent): MessageNotificationSpec? {
        val message = event.message
        if (message.isSystemNotice || message.sender.equals("SYSTEM", ignoreCase = true)) return null
        if (message.sender.isBlank()) return null

        val body = message.text.trim().ifBlank {
            message.attachment?.name?.let { "Shared file: $it" } ?: "New message"
        }

        return when (event) {
            is ClientIncomingMessageEvent.Channel -> {
                if (event.session.isLiveClassRoom ||
                    message.sender.equals(event.session.callsign, ignoreCase = true)
                ) {
                    null
                } else {
                    val channelName = event.session.name.trim().ifBlank { event.session.channelId }
                    MessageNotificationSpec(
                        destination = MessageNotificationDestination.CHANNEL,
                        conversationId = event.session.channelId,
                        peerUsername = null,
                        title = "#$channelName · ${message.sender}",
                        body = body,
                        notificationId = stableId(
                            MessageNotificationDestination.CHANNEL,
                            event.session.channelId
                        )
                    )
                }
            }

            is ClientIncomingMessageEvent.Direct -> {
                if (message.sender.equals(event.session.myUsername, ignoreCase = true)) {
                    null
                } else {
                    MessageNotificationSpec(
                        destination = MessageNotificationDestination.DIRECT,
                        conversationId = event.session.dmId,
                        peerUsername = event.session.peerUsername,
                        title = "Message from @${message.sender}",
                        body = body,
                        notificationId = stableId(
                            MessageNotificationDestination.DIRECT,
                            event.session.dmId
                        )
                    )
                }
            }
        }
    }

    /** Suppress delivery only while this exact conversation is visible in the foreground. */
    fun shouldSuppress(
        spec: MessageNotificationSpec,
        isAppInForeground: Boolean,
        visibleConversationId: String?
    ): Boolean {
        return isAppInForeground &&
            !visibleConversationId.isNullOrBlank() &&
            spec.conversationId.equals(visibleConversationId, ignoreCase = true)
    }

    fun stableId(destination: MessageNotificationDestination, conversationId: String): Int {
        val base = when (destination) {
            MessageNotificationDestination.CHANNEL -> CHANNEL_ID_BASE
            MessageNotificationDestination.DIRECT -> DIRECT_ID_BASE
        }
        val nonNegativeHash = conversationId.hashCode().toLong() and 0x7fff_ffffL
        return base + (nonNegativeHash % ID_RANGE).toInt()
    }
}
