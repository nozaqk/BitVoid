package com.bitvoid.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitvoid.network.channel.BitVoidChannelManager
import com.bitvoid.network.client.BitVoidClientManager
import com.bitvoid.network.client.BitVoidClientRepository
import com.bitvoid.network.client.ClientWsStatus
import com.bitvoid.network.direct.BitVoidDirectChatManager
import com.bitvoid.network.direct.BitVoidUserPrefs
import com.bitvoid.network.media.BitVoidMediaManager
import com.bitvoid.network.room.BitVoidRoomManager
import com.bitvoid.network.server.BitVoidLocalServer
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
class BitVoidLocalServerTest {

    private lateinit var context: Context
    private lateinit var server: BitVoidLocalServer
    private val testPort = 18080

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        BitVoidMediaManager.clearVisibilityCacheForTests()
        BitVoidRoomManager.clearAllForTests()
        BitVoidChannelManager.clearAllForTests()
        BitVoidDirectChatManager.clearAllForTests()
        server = BitVoidLocalServer(context, port = testPort)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    @After
    fun tearDown() {
        server.stop()
        server.sharedDirectory.listFiles()?.forEach { it.delete() }
        BitVoidMediaManager.clearVisibilityCacheForTests()
        BitVoidRoomManager.clearAllForTests()
        BitVoidChannelManager.clearAllForTests()
        BitVoidDirectChatManager.clearAllForTests()
    }

    @Test
    fun `portal response is 4KB block aligned includes privacy headers and channel endpoints`() {
        BitVoidChannelManager.ensureDefaultChannel()

        val conn = (URL("http://127.0.0.1:$testPort/").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "TestTracker/9.9")
            setRequestProperty("Referer", "https://external.example.com")
            setRequestProperty("Cookie", "session=leak")
        }

        assertEquals(200, conn.responseCode)
        assertEquals("no-referrer", conn.getHeaderField("Referrer-Policy"))
        assertEquals("4096", conn.getHeaderField("X-BitVoid-Block-Align"))
        val sessionToken = conn.getHeaderField("X-BitVoid-Session")
        assertNotNull(sessionToken)
        assertTrue(sessionToken!!.startsWith("BV-"))

        val bodyBytes = conn.inputStream.readBytes()
        assertTrue(bodyBytes.isNotEmpty())
        assertEquals(0, bodyBytes.size % 4096)
        val html = String(bodyBytes)
        assertTrue(html.contains("/api/channels"))
        assertTrue(html.contains("/api/channels/join"))
        assertTrue(html.contains("/api/public/media"))
        assertTrue(html.contains("/ws/chat/"))
        assertTrue(html.contains("channels-sidebar"))
        assertTrue(html.contains("menu-toggle-btn"))
        assertTrue(html.contains("wa-composer"))
        assertTrue(html.contains("toggle-media-btn"))
        assertTrue(html.contains("General Chat"))
        assertTrue(html.contains("PUBLIC"))
        assertTrue(html.contains("PRIVATE"))
        assertTrue(html.contains("id=\"dm-my-username\" type=\"text\" placeholder=\"Your Username ID\" readonly"))
        conn.disconnect()
    }

    @Test
    fun `private videos default to hidden and return 403 until toggled public`() {
        val sampleFile = File(server.sharedDirectory, "secret_clip.mp4")
        val payload = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toByteArray()
        sampleFile.writeBytes(payload)

        val items = BitVoidMediaManager.refreshFromDisk(context)
        assertEquals(1, items.size)
        val item = items.first()
        assertFalse(item.isPublic)

        val apiPrivateConn = URL("http://127.0.0.1:$testPort/api/public/media").openConnection() as HttpURLConnection
        assertEquals(200, apiPrivateConn.responseCode)
        val privateJson = String(apiPrivateConn.inputStream.readBytes())
        assertFalse(privateJson.contains("secret_clip.mp4"))
        apiPrivateConn.disconnect()

        val forbiddenById = URL("http://127.0.0.1:$testPort/stream/${item.id}").openConnection() as HttpURLConnection
        assertEquals(403, forbiddenById.responseCode)
        forbiddenById.disconnect()

        val forbiddenByName = URL("http://127.0.0.1:$testPort/stream/secret_clip.mp4").openConnection() as HttpURLConnection
        assertEquals(403, forbiddenByName.responseCode)
        forbiddenByName.disconnect()

        BitVoidMediaManager.setVideoPublic(item.id, true)

        val apiPublicConn = URL("http://127.0.0.1:$testPort/api/public/media").openConnection() as HttpURLConnection
        assertEquals(200, apiPublicConn.responseCode)
        val publicJson = String(apiPublicConn.inputStream.readBytes())
        assertTrue(publicJson.contains("secret_clip.mp4"))
        assertTrue(publicJson.contains(item.id))
        apiPublicConn.disconnect()

        val streamConn = (URL("http://127.0.0.1:$testPort/stream/${item.id}").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=4-15")
        }
        assertEquals(206, streamConn.responseCode)
        assertEquals("bytes", streamConn.getHeaderField("Accept-Ranges"))
        assertEquals("bytes 4-15/${payload.size}", streamConn.getHeaderField("Content-Range"))
        val chunk = streamConn.inputStream.readBytes()
        assertEquals("456789ABCDEF", String(chunk))
        streamConn.disconnect()
    }

    @Test
    fun `channel system protects private channel ids and media while allowing public channels and encrypted token ws chat`() = runBlocking {
        val publicFile = File(server.sharedDirectory, "public_briefing.mp4").apply {
            writeBytes("PUBLIC-CHANNEL-STREAM-DATA".toByteArray())
        }
        val secretFile = File(server.sharedDirectory, "classified_feed.mp4").apply {
            writeBytes("PRIVATE-CHANNEL-STREAM-987654321".toByteArray())
        }
        val media = BitVoidMediaManager.refreshFromDisk(context)
        val pubItem = media.first { it.title == publicFile.name }
        val prvItem = media.first { it.title == secretFile.name }

        // Create Public Channel and Private Channel
        val pubChannelRes = BitVoidChannelManager.createChannel(
            rawName = "Field Updates",
            isPublic = true,
            rawChannelId = "field-updates",
            mediaIds = setOf(pubItem.id)
        )
        assertTrue(pubChannelRes.isSuccess)

        val prvChannelRes = BitVoidChannelManager.createChannel(
            rawName = "Black Ops Briefing",
            isPublic = false,
            rawChannelId = null, // auto-generated secure Private ID
            rawPrivateKey = "vault-key-77",
            mediaIds = setOf(prvItem.id)
        )
        assertTrue(prvChannelRes.isSuccess)
        val prvChannel = prvChannelRes.getOrThrow()
        assertTrue(prvChannel.channelId.startsWith("CH-"))
        assertEquals(1, prvChannel.mediaList.size)

        // 1. GET /api/channels exposes public channel ID + private channel title WITHOUT leaking private ID or files
        val channelsConn = URL("http://127.0.0.1:$testPort/api/channels").openConnection() as HttpURLConnection
        assertEquals(200, channelsConn.responseCode)
        val channelsBody = String(channelsConn.inputStream.readBytes()).trim()
        channelsConn.disconnect()

        val channelsJson = JSONObject(channelsBody)
        assertEquals(1, channelsJson.getInt("publicCount"))
        assertEquals(1, channelsJson.getInt("privateCount"))
        assertTrue(channelsBody.contains("field-updates"))
        assertTrue(channelsBody.contains("Black Ops Briefing"))
        assertFalse("Private channelId must NOT be exposed in /api/channels", channelsBody.contains(prvChannel.channelId))
        assertFalse("Private media filename must NOT leak in /api/channels", channelsBody.contains("classified_feed.mp4"))
        assertFalse("Private key must NOT leak in /api/channels", channelsBody.contains("vault-key-77"))

        // 2. Public channel media endpoint succeeds immediately; private channel media returns 401 without token
        val pubMediaConn = URL("http://127.0.0.1:$testPort/api/channels/field-updates/media").openConnection() as HttpURLConnection
        assertEquals(200, pubMediaConn.responseCode)
        assertTrue(String(pubMediaConn.inputStream.readBytes()).contains("public_briefing.mp4"))
        pubMediaConn.disconnect()

        listOf(
            "/api/channels/${prvChannel.channelId}/media",
            "/api/channels/${prvChannel.channelId}/chat",
            "/stream/${prvItem.id}"
        ).forEach { path ->
            val unauth = URL("http://127.0.0.1:$testPort$path").openConnection() as HttpURLConnection
            assertEquals("Expected 401 for $path", 401, unauth.responseCode)
            unauth.disconnect()
        }

        // 3. Unauthorized WebSocket upgrade without token returns 401
        Socket("127.0.0.1", testPort).use { socket ->
            val wsHandshake = buildString {
                append("GET /ws/chat/${prvChannel.channelId} HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$testPort\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                append("Sec-WebSocket-Version: 13\r\n\r\n")
            }
            socket.getOutputStream().write(wsHandshake.toByteArray(StandardCharsets.UTF_8))
            socket.getOutputStream().flush()
            val statusLine = socket.getInputStream().bufferedReader().readLine()
            assertNotNull(statusLine)
            assertTrue("Expected 401 on unauthorized WS, got $statusLine", statusLine.contains("401"))
        }

        // 4. Wrong privateKey on /api/channels/join returns 401
        val badJoin = (URL("http://127.0.0.1:$testPort/api/channels/join").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            outputStream.write("""{"channelId":"${prvChannel.channelId}","privateKey":"bad"}""".toByteArray())
        }
        assertEquals(401, badJoin.responseCode)
        badJoin.disconnect()

        // 5. Native BitVoidClientRepository discovers channels, unlocks private channel, streams 206, and chats via WS
        val repo = BitVoidClientRepository()
        try {
            repo.fetchChannels("http://127.0.0.1:$testPort")
            assertEquals(2, repo.channels.value.size)
            val discoveredPrivate = repo.channels.value.first { !it.isPublic }
            assertEquals("Black Ops Briefing", discoveredPrivate.name)
            assertNull(discoveredPrivate.channelId)

            repo.joinChannel(prvChannel.channelId, "vault-key-77", "http://127.0.0.1:$testPort")
            val session = repo.activeChannelSession.value
            assertNotNull(session)
            assertTrue(session!!.token.startsWith("BVC."))
            assertEquals(prvChannel.channelId, session.channelId)
            assertEquals(1, session.mediaList.size)

            val streamUrl = repo.buildAbsoluteStreamUrl(session.mediaList.first(), session.token)
            val rangeStream = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-14")
            }
            assertEquals(206, rangeStream.responseCode)
            assertEquals("PRIVATE-CHANNEL", String(rangeStream.inputStream.readBytes()))
            rangeStream.disconnect()

            var attempts = 0
            while (repo.wsStatus.value != ClientWsStatus.LIVE && attempts < 25) {
                delay(50)
                attempts++
            }
            assertEquals(ClientWsStatus.LIVE, repo.wsStatus.value)

            repo.sendChannelChatMessage("Channel WS telemetry check")
            attempts = 0
            while (repo.channelMessages.value.none { it.text == "Channel WS telemetry check" } && attempts < 25) {
                delay(50)
                attempts++
            }
            assertTrue(repo.channelMessages.value.any { it.text == "Channel WS telemetry check" })

            // Verify HTTP channel chat sees the same message
            val chatHttp = (URL("http://127.0.0.1:$testPort/api/channels/${prvChannel.channelId}/chat").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("X-Channel-Token", session.token)
            }
            assertEquals(200, chatHttp.responseCode)
            assertTrue(String(chatHttp.inputStream.readBytes()).contains("Channel WS telemetry check"))
            chatHttp.disconnect()

            // Toggling channel from Private -> Public revokes prior private tokens and broadcasts notice
            val toggleRes = BitVoidChannelManager.setChannelVisibility(prvChannel.channelId, isPublic = true)
            assertTrue(toggleRes.isSuccess)
            assertTrue(toggleRes.getOrThrow().isPublic)
        } finally {
            repo.release()
        }
    }

    @Test
    fun `captive portal probes redirect to local broadcast page`() {
        listOf("/generate_204", "/hotspot-detect.html", "/ncsi.txt").forEach { path ->
            val conn = (URL("http://127.0.0.1:$testPort$path").openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
            assertEquals(302, conn.responseCode)
            val location = conn.getHeaderField("Location")
            assertNotNull(location)
            assertTrue(location!!.endsWith("/"))
            conn.disconnect()
        }
    }

    @Test
    fun `os connectivity check domains receive 302 redirect to gateway portal`() {
        val probeCases = listOf(
            "connectivitycheck.gstatic.com" to "/generate_204",
            "www.msftncsi.com" to "/ncsi.txt",
            "captiveportal.recaptcha.net" to "/",
            "captive.apple.com" to "/hotspot-detect.html"
        )
        probeCases.forEach { (hostDomain, path) ->
            Socket("127.0.0.1", testPort).use { socket ->
                val rawReq = "GET $path HTTP/1.1\r\nHost: $hostDomain\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(rawReq.toByteArray(StandardCharsets.UTF_8))
                socket.getOutputStream().flush()
                val headersText = socket.getInputStream().bufferedReader().readLines()
                    .takeWhile { it.isNotEmpty() }
                    .joinToString("\n")
                assertTrue("Expected 302 for $hostDomain$path, got:\n$headersText", headersText.contains("302"))
                assertTrue(
                    "Expected redirect to http://192.168.43.1:8080/ for $hostDomain, got:\n$headersText",
                    headersText.contains("http://192.168.43.1:8080/")
                )
            }
        }
    }

    @Test
    fun `open wifi qr payload and bitmap are generated for passwordless BitVoid_Net`() {
        val payload = com.bitvoid.network.ui.buildWifiQrPayload(
            ssid = "BitVoid_Net",
            passphrase = "",
            isOpen = true
        )
        assertEquals("WIFI:T:nopass;S:BitVoid_Net;H:false;;", payload)
        val bitmap = com.bitvoid.network.ui.generateQrImageBitmap(payload, 256)
        assertNotNull(bitmap)
        assertEquals(256, bitmap!!.width)
        assertEquals(256, bitmap.height)
    }

    @Test
    fun `network config enforces always visible SSID and dual security mode state`() {
        val openCfg = com.bitvoid.network.model.BitVoidNetworkConfig(
            securityMode = com.bitvoid.network.model.HotspotSecurityMode.OPEN_VISIBLE
        )
        assertFalse(openCfg.isHiddenSsid)

        val wpa2Cfg = openCfg.withSecurityMode(com.bitvoid.network.model.HotspotSecurityMode.WPA2_VISIBLE)
        assertEquals(com.bitvoid.network.model.HotspotSecurityMode.WPA2_VISIBLE, wpa2Cfg.securityMode)
        assertFalse(wpa2Cfg.isHiddenSsid)

        var threw = false
        try {
            com.bitvoid.network.model.BitVoidNetworkConfig(
                securityMode = com.bitvoid.network.model.HotspotSecurityMode.OPEN_VISIBLE,
                isHiddenSsid = true
            )
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("Hidden SSID configuration must be rejected", threw)

        val idleHost = com.bitvoid.network.model.HostState()
        assertFalse(idleHost.isEngineLocked)
        val runningHost = idleHost.copy(status = com.bitvoid.network.model.HotspotStatus.ACTIVE)
        assertTrue(runningHost.isEngineLocked)
    }

    @Test
    fun `sandbox firewall allows RFC1918 and blocks public hosts`() {
        assertTrue(BitVoidClientManager.isPrivateLocalHost("192.168.43.1"))
        assertTrue(BitVoidClientManager.isPrivateLocalHost("127.0.0.1"))
        assertTrue(BitVoidClientManager.isPrivateLocalHost("10.10.0.1"))
        assertTrue(BitVoidClientManager.isPrivateLocalHost("172.16.4.2"))
        assertFalse(BitVoidClientManager.isPrivateLocalHost("8.8.8.8"))
        assertFalse(BitVoidClientManager.isPrivateLocalHost("172.32.0.1"))
    }

    @Test
    fun `bottom tabs match Home Hub Upload Settings and autonomous loopback URL is allowed`() {
        val labels = com.bitvoid.network.model.AppBottomTab.entries.map { it.label }
        assertEquals(listOf("Home", "Hub", "Upload", "Settings"), labels)

        val normalizedLoopback = BitVoidClientManager.normalizeLocalPortalUrl("http://127.0.0.1:$testPort")
        assertEquals("http://127.0.0.1:$testPort", normalizedLoopback)
    }

    @Test
    fun `custom wifi broadcast ssid and password persist across reloads`() {
        com.bitvoid.network.model.BitVoidNetworkPrefs.saveBroadcastCredentials(
            context = context,
            ssid = "MeshOps_Relay",
            passphrase = "UltraSecretPass2026"
        )
        com.bitvoid.network.model.BitVoidNetworkPrefs.saveSecurityMode(
            context = context,
            mode = com.bitvoid.network.model.HotspotSecurityMode.WPA2_VISIBLE
        )

        val loaded = com.bitvoid.network.model.BitVoidNetworkPrefs.load(context)
        assertEquals("MeshOps_Relay", loaded.ssid)
        assertEquals("UltraSecretPass2026", loaded.passphrase)
        assertEquals(com.bitvoid.network.model.HotspotSecurityMode.WPA2_VISIBLE, loaded.securityMode)
        assertFalse(loaded.isHiddenSsid)
    }

    @Test
    fun `hidden channels are excluded from directory listings and portal html but unlock by private id`() = runBlocking {
        BitVoidChannelManager.ensureDefaultChannel()
        val hiddenRes = BitVoidChannelManager.createChannel(
            rawName = "Ghost Room",
            isPublic = true, // even if true passed, isHidden forces private
            rawChannelId = "CH-GHOST99",
            rawPrivateKey = null,
            mediaIds = emptySet(),
            isHidden = true
        )
        assertTrue(hiddenRes.isSuccess)
        val hidden = hiddenRes.getOrThrow()
        assertFalse(hidden.isPublic)
        assertTrue(hidden.isHidden)

        // Verify /api/channels omits hidden channel name and ID completely
        val listConn = URL("http://127.0.0.1:$testPort/api/channels").openConnection() as HttpURLConnection
        assertEquals(200, listConn.responseCode)
        val listBody = String(listConn.inputStream.readBytes())
        listConn.disconnect()
        assertFalse("Hidden channel name must not appear in /api/channels", listBody.contains("Ghost Room"))
        assertFalse("Hidden channel ID must not appear in /api/channels", listBody.contains("CH-GHOST99"))

        // Verify HTML portal sidebar omits hidden channel
        val portalConn = URL("http://127.0.0.1:$testPort/").openConnection() as HttpURLConnection
        assertEquals(200, portalConn.responseCode)
        val portalHtml = String(portalConn.inputStream.readBytes())
        portalConn.disconnect()
        assertFalse("Hidden channel must not appear in portal HTML", portalHtml.contains("Ghost Room"))
        assertFalse("Hidden channel ID must not appear in portal HTML", portalHtml.contains("CH-GHOST99"))

        // Verify Hub/client can still enter the hidden channel using its Private Channel ID
        val repo = BitVoidClientRepository()
        try {
            repo.joinChannel("CH-GHOST99", null, "http://127.0.0.1:$testPort", username = "alpha-01")
            val session = repo.activeChannelSession.value
            assertNotNull(session)
            assertEquals("CH-GHOST99", session!!.channelId)
            assertEquals("Ghost Room", session.name)
            assertEquals("alpha-01", session.callsign)
        } finally {
            repo.release()
        }
    }

    @Test
    fun `username id persists and enables direct user to user chat without joining a channel`() = runBlocking {
        val initial = BitVoidUserPrefs.loadOrCreate(context)
        assertTrue(initial.isNotBlank())
        val saved = BitVoidUserPrefs.saveUsername(context, "alice-99")
        assertTrue(saved.isSuccess)
        assertEquals("alice-99", BitVoidUserPrefs.loadOrCreate(context))

        val aliceRepo = BitVoidClientRepository()
        val bobRepo = BitVoidClientRepository()
        try {
            val baseUrl = "http://127.0.0.1:$testPort"
            aliceRepo.registerUsername("alice-99", baseUrl)
            bobRepo.registerUsername("bob-42", baseUrl)
            aliceRepo.fetchKnownUsers(baseUrl)
            assertTrue(aliceRepo.knownUsers.value.contains("alice-99"))
            assertTrue(aliceRepo.knownUsers.value.contains("bob-42"))

            aliceRepo.openDirectChat("alice-99", "bob-42", baseUrl)
            bobRepo.openDirectChat("bob-42", "alice-99", baseUrl)

            val aliceSession = aliceRepo.activeDirectSession.value
            val bobSession = bobRepo.activeDirectSession.value
            assertNotNull(aliceSession)
            assertNotNull(bobSession)
            assertEquals(aliceSession!!.dmId, bobSession!!.dmId)
            assertTrue(aliceSession.token.startsWith("BVD."))

            var attempts = 0
            while ((aliceRepo.directWsStatus.value != ClientWsStatus.LIVE ||
                        bobRepo.directWsStatus.value != ClientWsStatus.LIVE) && attempts < 30
            ) {
                delay(50)
                attempts++
            }
            assertEquals(ClientWsStatus.LIVE, aliceRepo.directWsStatus.value)
            assertEquals(ClientWsStatus.LIVE, bobRepo.directWsStatus.value)

            aliceRepo.sendDirectMessage("Direct ping without channel")
            attempts = 0
            while (bobRepo.directMessages.value.none { it.text == "Direct ping without channel" } && attempts < 30) {
                delay(50)
                attempts++
            }
            assertTrue(bobRepo.directMessages.value.any { it.text == "Direct ping without channel" && it.sender == "alice-99" })

            // Unauthorized direct message read returns 401
            val unauthConn = URL("http://127.0.0.1:$testPort/api/dm/${aliceSession.dmId}/messages")
                .openConnection() as HttpURLConnection
            assertEquals(401, unauthConn.responseCode)
            unauthConn.disconnect()
        } finally {
            aliceRepo.release()
            bobRepo.release()
        }
    }

    @Test
    fun `formatWifiDirectNetworkName preserves configured ssid and enforces 32 byte limit`() {
        val formatted = com.bitvoid.network.model.BitVoidNetworkConfig.formatWifiDirectNetworkName("MeshOps_Relay")
        assertEquals("DIRECT-BV-MeshOps_Relay", formatted)

        val alreadyPrefixed = com.bitvoid.network.model.BitVoidNetworkConfig.formatWifiDirectNetworkName("DIRECT-AB-Custom")
        assertEquals("DIRECT-AB-Custom", alreadyPrefixed)

        val oversized = com.bitvoid.network.model.BitVoidNetworkConfig.formatWifiDirectNetworkName("A_Very_Long_Network_Name_Exceeding_32_Bytes")
        assertTrue(oversized.startsWith("DIRECT-BV-"))
        assertTrue(oversized.toByteArray(StandardCharsets.UTF_8).size <= 32)
    }

    @Test
    fun `resolvePortalUrlAfterConnect overrides default placeholder with detected gateway IP`() {
        val resolvedFromDefault = BitVoidClientManager.resolvePortalUrlAfterConnect(
            normalizedRequestedUrl = "http://192.168.43.1:8080",
            wasDefaultPlaceholder = true,
            linkGatewayIp = "192.168.49.1"
        )
        assertEquals("http://192.168.49.1:8080", resolvedFromDefault)

        val preservedCustomPort = BitVoidClientManager.resolvePortalUrlAfterConnect(
            normalizedRequestedUrl = "http://192.168.12.1:9090",
            wasDefaultPlaceholder = false,
            linkGatewayIp = "192.168.49.1"
        )
        assertEquals("http://192.168.12.1:9090", preservedCustomPort)
    }

    @Test
    fun `captive portal redirect never redirects external probes to 127_0_0_1`() {
        com.bitvoid.network.service.BitVoidNodeStateHolder.updateHostState { state ->
            state.copy(
                gatewayIp = "127.0.0.1",
                boundEndpoints = listOf("127.0.0.1:$testPort"),
                port = 8080
            )
        }
        try {
            Socket("127.0.0.1", testPort).use { socket ->
                val rawReq = "GET /generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(rawReq.toByteArray(StandardCharsets.UTF_8))
                socket.getOutputStream().flush()
                val headersText = socket.getInputStream().bufferedReader().readLines()
                    .takeWhile { it.isNotEmpty() }
                    .joinToString("\n")
                assertTrue(headersText.contains("302"))
                assertFalse("Captive portal must never redirect to 127.0.0.1", headersText.contains("127.0.0.1"))
                assertTrue(headersText.contains("http://192.168.43.1:8080/"))
            }
        } finally {
            com.bitvoid.network.service.BitVoidNodeStateHolder.updateHostState {
                com.bitvoid.network.model.HostState()
            }
        }
    }
}
