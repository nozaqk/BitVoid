package com.bitvoid.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitvoid.network.channel.BitVoidChannelManager
import com.bitvoid.network.channel.BitVoidSpaceKind
import com.bitvoid.network.channel.BitVoidSpaceRole
import com.bitvoid.network.channel.BitVoidSpaceStore
import com.bitvoid.network.server.BitVoidLocalServer
import fi.iki.elonen.NanoHTTPD
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BitVoidSpacesTest {
    private lateinit var context: Context
    private lateinit var server: BitVoidLocalServer
    private val port = 18082

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        BitVoidChannelManager.clearAllForTests()
        server = BitVoidLocalServer(context, port)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    @After
    fun tearDown() {
        server.stop()
        BitVoidChannelManager.clearAllForTests()
    }

    @Test
    fun `group roles membership authorization and history are durable`() {
        val group = BitVoidChannelManager.createGroup(
            rawName = "Engineering 2026",
            rawDescription = "Project coordination",
            ownerUsername = "alice",
            initialMembers = listOf("bob", "carol")
        ).getOrThrow()
        assertEquals(BitVoidSpaceKind.GROUP, group.spaceKind)
        assertEquals(BitVoidSpaceRole.OWNER, group.members.first { it.username == "alice" }.role)
        assertEquals(3, group.members.size)

        val owner = BitVoidChannelManager.joinChannel(group.channelId, rawUsername = "alice")
        val bob = BitVoidChannelManager.joinChannel(group.channelId, rawUsername = "bob")
        assertNotNull(owner)
        assertNotNull(bob)
        assertNull(BitVoidChannelManager.joinChannel(group.channelId, rawUsername = "mallory"))

        val bobMessage = BitVoidChannelManager.appendChatMessage(
            channelId = group.channelId,
            callsign = "bob",
            rawText = "First group message",
            stableMessageId = "client_group_0001",
            sessionToken = bob!!.token
        )
        assertNotNull(bobMessage)
        assertEquals(
            "client_group_0001",
            BitVoidChannelManager.appendChatMessage(
                channelId = group.channelId,
                callsign = "bob",
                rawText = "duplicate retry",
                stableMessageId = "client_group_0001",
                sessionToken = bob.token
            )?.id
        )
        assertEquals(1, BitVoidChannelManager.getChatHistory(group.channelId).size)

        BitVoidChannelManager.setSpaceMemberRole(
            group.channelId,
            owner!!.token,
            "bob",
            BitVoidSpaceRole.ADMINISTRATOR
        ).getOrThrow()
        assertNull(BitVoidChannelManager.validateToken(bob.token, group.channelId))
        val bobAdmin = BitVoidChannelManager.joinChannel(group.channelId, rawUsername = "bob")!!
        assertEquals(BitVoidSpaceRole.ADMINISTRATOR, bobAdmin.role)
        BitVoidChannelManager.addGroupMember(group.channelId, bobAdmin.token, "dede").getOrThrow()
        assertTrue(
            BitVoidChannelManager.setSpaceMemberRole(
                group.channelId,
                bobAdmin.token,
                "dede",
                BitVoidSpaceRole.ADMINISTRATOR
            ).isFailure
        )
        assertTrue(BitVoidChannelManager.deleteSpace(group.channelId, bobAdmin.token).isFailure)

        val store = BitVoidSpaceStore(context)
        val stored = try {
            store.loadSpaces().first { it.channelId == group.channelId }
        } finally {
            store.close()
        }
        assertEquals(BitVoidSpaceKind.GROUP, stored.spaceKind)
        assertEquals(4, stored.members.size)
        assertEquals(1, stored.messages.size)
        assertEquals("First group message", stored.messages.single().text)
    }

    @Test
    fun `private invitation joins subscriber while broadcast publishing remains role enforced`() {
        val channel = BitVoidChannelManager.createBroadcastChannel(
            rawName = "Campus Updates",
            rawDescription = "Official notices",
            ownerUsername = "owner",
            isPublic = false
        ).getOrThrow()
        val owner = BitVoidChannelManager.joinChannel(channel.channelId, rawUsername = "owner")!!
        assertTrue(BitVoidChannelManager.discoverPublicBroadcastChannels().isEmpty())
        assertNull(BitVoidChannelManager.joinChannel(channel.channelId, rawUsername = "student"))

        val invitation = BitVoidChannelManager.createInvitation(channel.channelId, owner.token, 2).getOrThrow()
        val subscriber = BitVoidChannelManager.joinChannelByInvitation(invitation.code, "student").getOrThrow()
        assertEquals(BitVoidSpaceRole.SUBSCRIBER, subscriber.role)
        assertFalse(BitVoidChannelManager.canPublish(subscriber))
        assertNull(
            BitVoidChannelManager.appendChatMessage(
                channel.channelId,
                subscriber.callsign,
                "Subscribers cannot publish",
                sessionToken = subscriber.token
            )
        )
        assertNotNull(
            BitVoidChannelManager.appendChatMessage(
                channel.channelId,
                owner.callsign,
                "Authorized announcement",
                sessionToken = owner.token
            )
        )

        BitVoidChannelManager.setSpaceMemberRole(
            channel.channelId,
            owner.token,
            "student",
            BitVoidSpaceRole.ADMINISTRATOR
        ).getOrThrow()
        val administrator = BitVoidChannelManager.joinChannel(channel.channelId, rawUsername = "student")!!
        assertTrue(BitVoidChannelManager.canPublish(administrator))
        assertNotNull(
            BitVoidChannelManager.appendChatMessage(
                channel.channelId,
                administrator.callsign,
                "Administrator announcement",
                sessionToken = administrator.token
            )
        )
    }

    @Test
    fun `space HTTP API enforces private access and subscriber publish denial`() {
        val aliceIdentity = registerIdentity("alice")
        val bobIdentity = registerIdentity("bob")
        val malloryIdentity = registerIdentity("mallory")
        val ownerIdentity = registerIdentity("owner")
        val subscriberIdentity = registerIdentity("subscriber")
        assertEquals(
            403,
            postJson(
                "/api/spaces/groups",
                """{"name":"Spoofed","username":"alice","members":[]}"""
            ).code
        )
        val createdGroup = postJson(
            "/api/spaces/groups",
            """{"name":"Study Group","description":"Finals","username":"alice","members":["bob"]}""",
            identityToken = aliceIdentity
        )
        assertEquals(200, createdGroup.code)
        val groupJson = JSONObject(createdGroup.body)
        val groupId = groupJson.getString("channelId")
        val ownerToken = groupJson.getString("token")
        assertFalse(get("/api/channels?username=bob").body.contains(groupId))
        assertTrue(get("/api/channels?username=bob", identityToken = bobIdentity).body.contains(groupId))

        val outsiderJoin = postJson(
            "/api/channels/join",
            """{"channelId":"$groupId","username":"mallory"}""",
            identityToken = malloryIdentity
        )
        assertEquals(401, outsiderJoin.code)
        val bobJoin = postJson(
            "/api/channels/join",
            """{"channelId":"$groupId","username":"bob"}""",
            identityToken = bobIdentity
        )
        assertEquals(200, bobJoin.code)
        val bobToken = JSONObject(bobJoin.body).getString("token")
        assertEquals(
            200,
            postJson(
                "/api/channels/$groupId/chat",
                """{"text":"hello group","clientMessageId":"client_http_group_1"}""",
                bobToken
            ).code
        )
        assertEquals(403, get("/api/spaces/$groupId").code)
        assertEquals(200, get("/api/spaces/$groupId", ownerToken).code)

        val createdChannel = postJson(
            "/api/spaces/channels",
            """{"name":"Private Bulletin","description":"Invite only","username":"owner","isPublic":false}""",
            identityToken = ownerIdentity
        )
        assertEquals(200, createdChannel.code)
        val channelJson = JSONObject(createdChannel.body)
        val channelId = channelJson.getString("channelId")
        val channelOwnerToken = channelJson.getString("token")
        val inviteResponse = postJson(
            "/api/spaces/$channelId/invitations",
            """{"maxUses":3}""",
            channelOwnerToken
        )
        assertEquals(200, inviteResponse.code)
        val invitation = JSONObject(inviteResponse.body).getString("code")
        val joined = postJson(
            "/api/spaces/invitations/join",
            """{"invitation":"$invitation","username":"subscriber"}""",
            identityToken = subscriberIdentity
        )
        assertEquals(200, joined.code)
        val subscriberToken = JSONObject(joined.body).getString("token")
        assertEquals(
            401,
            postJson(
                "/api/channels/$channelId/chat",
                """{"text":"forbidden post"}""",
                subscriberToken
            ).code
        )
        assertEquals(
            200,
            postJson(
                "/api/channels/$channelId/chat",
                """{"text":"owner post"}""",
                channelOwnerToken
            ).code
        )
    }

    @Test
    fun `public broadcast channels are discoverable and subscription is explicit`() {
        val editorIdentity = registerIdentity("editor")
        val readerIdentity = registerIdentity("reader")
        val created = postJson(
            "/api/spaces/channels",
            """{"name":"Public News","description":"Daily updates","username":"editor","isPublic":true}""",
            identityToken = editorIdentity
        )
        assertEquals(200, created.code)
        val channelId = JSONObject(created.body).getString("channelId")
        val discovery = get("/api/spaces/discover?q=Public")
        assertEquals(200, discovery.code)
        val found = JSONObject(discovery.body).getJSONArray("channels")
        assertEquals(1, found.length())
        assertEquals(channelId, found.getJSONObject(0).getString("channelId"))

        val subscription = postJson(
            "/api/spaces/$channelId/subscribe",
            """{"username":"reader"}""",
            identityToken = readerIdentity
        )
        assertEquals(200, subscription.code)
        val reader = BitVoidChannelManager.getChannel(channelId)!!.members.first { it.username == "reader" }
        assertEquals(BitVoidSpaceRole.SUBSCRIBER, reader.role)
    }

    private data class HttpResult(val code: Int, val body: String)

    private fun registerIdentity(username: String): String {
        val registered = postJson(
            "/api/users/register",
            """{"username":"$username","discoverable":true}"""
        )
        assertEquals(200, registered.code)
        return JSONObject(registered.body).getString("identityToken")
    }

    private fun postJson(
        path: String,
        body: String,
        token: String? = null,
        identityToken: String? = null
    ): HttpResult {
        val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            token?.let { setRequestProperty("X-Channel-Token", it) }
            identityToken?.let { setRequestProperty("X-BitVoid-Identity-Token", it) }
        }
        connection.outputStream.use { it.write(body.toByteArray()) }
        return read(connection)
    }

    private fun get(
        path: String,
        token: String? = null,
        identityToken: String? = null
    ): HttpResult {
        val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            token?.let { setRequestProperty("X-Channel-Token", it) }
            identityToken?.let { setRequestProperty("X-BitVoid-Identity-Token", it) }
        }
        return read(connection)
    }

    private fun read(connection: HttpURLConnection): HttpResult {
        val code = connection.responseCode
        val stream = if (code in 200..399) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
        connection.disconnect()
        return HttpResult(code, body)
    }
}
