package com.bitvoid.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitvoid.network.call.BitVoidSignalingServer
import com.bitvoid.network.call.DirectCallPhase
import com.bitvoid.network.call.SignalingSessionMode
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
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
        BitVoidSignalingServer.clearAllForTests()
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
        BitVoidSignalingServer.clearAllForTests()
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
        assertFalse(html.contains("id=\"toggle-media-btn\""))
        assertTrue(html.contains("id=\"voice-call-btn\""))
        assertFalse(html.contains("id=\"video-call-btn\""))
        assertFalse(html.contains("Start video call"))
        assertTrue(html.contains("/call/invite"))
        assertTrue(html.contains("access requires trusted HTTPS"))
        assertTrue(html.contains("id=\"pchat-users-list\""))
        assertTrue(html.contains("id=\"pchat-add-user-btn\""))
        assertTrue(html.contains("id=\"pchat-discoverable-toggle\""))
        assertTrue(html.contains("id=\"pchat-add-dialog\""))
        assertTrue(html.contains("/api/users/status?username="))
        assertTrue(html.contains("id=\"live-class-btn\" class=\"btn-ghost hidden\""))
        assertFalse(html.contains("msg-att-preview"))
        assertFalse(html.contains("msg-att-img"))
        assertTrue(html.contains("msg-att-actions"))
        assertTrue(html.contains("upload-spinner"))
        assertTrue(html.contains("General Chat"))
        assertTrue(html.contains("PUBLIC"))
        assertTrue(html.contains("PRIVATE"))
        assertFalse(html.contains("DIRECT CHAT (USERNAME ID)"))
        assertFalse(html.contains("id=\"dm-my-username\""))
        assertFalse(html.contains("id=\"dm-target-username\""))
        conn.disconnect()
    }

    @Test
    fun `node identity endpoint supports lightweight same wifi discovery`() {
        val conn = (URL("http://127.0.0.1:$testPort/api/node").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("X-BitVoid-Discovery", "1")
        }

        assertEquals(200, conn.responseCode)
        assertEquals("1", conn.getHeaderField("X-BitVoid-Node"))
        assertEquals("1", conn.getHeaderField("X-BitVoid-Protocol"))
        val body = JSONObject(conn.inputStream.bufferedReader().readText())
        assertEquals("bitvoid", body.getString("service"))
        assertEquals("gateway", body.getString("nodeType"))
        assertEquals(1, body.getInt("protocolVersion"))
    }

    @Test
    fun `secure browser setup exports a valid local root certificate`() {
        val certConn = URL("http://127.0.0.1:$testPort/bitvoid-local-ca.crt")
            .openConnection() as HttpURLConnection
        assertEquals(200, certConn.responseCode)
        assertEquals("application/x-x509-ca-cert", certConn.contentType)
        assertTrue(certConn.getHeaderField("Content-Disposition").contains("bitvoid-local-ca.crt"))
        assertTrue(certConn.getHeaderField("X-BitVoid-CA-SHA256").matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
        val pemBytes = certConn.inputStream.readBytes()
        assertTrue(String(pemBytes, StandardCharsets.US_ASCII).startsWith("-----BEGIN CERTIFICATE-----"))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(pemBytes.inputStream()) as X509Certificate
        assertTrue(certificate.basicConstraints >= 0)
        assertTrue(certificate.subjectX500Principal.name.contains("BitVoid Local Root CA"))
        certConn.disconnect()

        val derConn = URL("http://127.0.0.1:$testPort/bitvoid-local-ca.cer")
            .openConnection() as HttpURLConnection
        assertEquals(200, derConn.responseCode)
        assertEquals("application/pkix-cert", derConn.contentType)
        assertTrue(derConn.getHeaderField("Content-Disposition").contains("bitvoid-local-ca.cer"))
        val derCertificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(derConn.inputStream) as X509Certificate
        assertTrue(certificate.encoded.contentEquals(derCertificate.encoded))
        derConn.disconnect()

        val setupConn = URL("http://127.0.0.1:$testPort/secure-setup")
            .openConnection() as HttpURLConnection
        assertEquals(200, setupConn.responseCode)
        val setupHtml = setupConn.inputStream.bufferedReader().readText()
        assertTrue(setupHtml.contains("Save / install CA (.crt)"))
        assertTrue(setupHtml.contains("DER fallback (.cer)"))
        assertTrue(setupHtml.contains("Certificate Trust Settings"))
        assertTrue(setupHtml.contains("Open secure BitVoid"))
        setupConn.disconnect()
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
    fun `wifi qr payload always includes WPA password and bitmap`() {
        val payload = com.bitvoid.network.ui.buildWifiQrPayload(
            ssid = "BitVoid_Net",
            passphrase = "ProtectedPass2026"
        )
        assertEquals(
            "WIFI:T:WPA;S:BitVoid_Net;P:ProtectedPass2026;H:false;;",
            payload
        )
        assertFalse(payload.contains("nopass"))
        val defaultProtectedPayload = com.bitvoid.network.ui.buildWifiQrPayload(
            ssid = "BitVoid_Net",
            passphrase = ""
        )
        assertEquals(
            "WIFI:T:WPA;S:BitVoid_Net;P:BitVoid2026;H:false;;",
            defaultProtectedPayload
        )
        assertFalse(defaultProtectedPayload.contains("nopass"))
        val bitmap = com.bitvoid.network.ui.generateQrImageBitmap(payload, 256)
        assertNotNull(bitmap)
        assertEquals(256, bitmap!!.width)
        assertEquals(256, bitmap.height)
    }

    @Test
    fun `network config enforces visible password protected SSID`() {
        val config = com.bitvoid.network.model.BitVoidNetworkConfig()
        assertFalse(config.isHiddenSsid)
        assertTrue(config.passphrase.length >= 8)
        assertEquals(
            "SECURITY: WPA2-PSK (VISIBLE SSID)",
            com.bitvoid.network.model.BitVoidNetworkConfig.WPA2_SECURITY_LABEL
        )

        var hiddenThrew = false
        try {
            com.bitvoid.network.model.BitVoidNetworkConfig(isHiddenSsid = true)
        } catch (_: IllegalArgumentException) {
            hiddenThrew = true
        }
        assertTrue("Hidden SSID configuration must be rejected", hiddenThrew)

        var shortPasswordThrew = false
        try {
            com.bitvoid.network.model.BitVoidNetworkConfig(passphrase = "short")
        } catch (_: IllegalArgumentException) {
            shortPasswordThrew = true
        }
        assertTrue("Password-free and short-password configurations must be rejected", shortPasswordThrew)

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
    fun `silent node discovery tries wifi gateways before every peer on the local subnet`() {
        val hosts = BitVoidClientManager.buildSubnetCandidateHosts(
            localIpv4 = "192.168.50.42",
            gatewayHosts = listOf("192.168.50.1", "192.168.50.254", "8.8.8.8")
        )

        assertEquals("192.168.50.1", hosts.first())
        assertEquals("192.168.50.254", hosts[1])
        assertFalse(hosts.contains("192.168.50.42"))
        assertFalse(hosts.contains("8.8.8.8"))
        assertEquals(253, hosts.size)
        assertTrue(BitVoidClientManager.buildSubnetCandidateHosts("not-an-ip", emptyList()).isEmpty())
    }

    @Test
    fun `all discovered nodes choose the same lowest ipv4 shared gateway`() {
        val ordered = BitVoidClientManager.orderNodeUrlsForSharedGateway(
            listOf(
                "http://192.168.49.200:8080/",
                "http://192.168.49.11:8080",
                "http://192.168.49.2:8080",
                "http://192.168.49.11:8080"
            )
        )

        assertEquals(
            listOf(
                "http://192.168.49.2:8080",
                "http://192.168.49.11:8080",
                "http://192.168.49.200:8080"
            ),
            ordered
        )
    }

    @Test
    fun `bottom tabs match Home Hub P Chat Settings and autonomous loopback URL is allowed`() {
        val labels = com.bitvoid.network.model.AppBottomTab.entries.map { it.label }
        assertEquals(listOf("Home", "Hub", "P.Chat", "Settings"), labels)

        val normalizedLoopback = BitVoidClientManager.normalizeLocalPortalUrl("http://127.0.0.1:$testPort")
        assertEquals("http://127.0.0.1:$testPort", normalizedLoopback)
    }

    @Test
    fun `custom protected wifi credentials persist and legacy open mode is removed`() {
        context.getSharedPreferences("bitvoid_network_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("security_mode", "OPEN_VISIBLE")
            .apply()
        com.bitvoid.network.model.BitVoidNetworkPrefs.saveBroadcastCredentials(
            context = context,
            ssid = "MeshOps_Relay",
            passphrase = "UltraSecretPass2026"
        )

        val loaded = com.bitvoid.network.model.BitVoidNetworkPrefs.load(context)
        assertEquals("MeshOps_Relay", loaded.ssid)
        assertEquals("UltraSecretPass2026", loaded.passphrase)
        assertFalse(loaded.isHiddenSsid)
        assertFalse(
            context.getSharedPreferences("bitvoid_network_prefs", Context.MODE_PRIVATE)
                .contains("security_mode")
        )
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
    fun `hidden private channel stays visible and keyless to its creator only`() = runBlocking {
        val hidden = BitVoidChannelManager.createChannel(
            rawName = "Owner Ghost Room",
            isPublic = false,
            rawChannelId = "CH-OWNER-77",
            rawPrivateKey = "owner-secret-key",
            isHidden = true,
            creatorUsername = "owner-77"
        ).getOrThrow()

        val anonymousDirectory = URL("http://127.0.0.1:$testPort/api/channels")
            .readText()
        assertFalse(anonymousDirectory.contains(hidden.channelId))
        assertFalse(anonymousDirectory.contains(hidden.name))

        val otherDirectory = URL("http://127.0.0.1:$testPort/api/channels?username=other-22")
            .readText()
        assertFalse(otherDirectory.contains(hidden.channelId))
        assertFalse(otherDirectory.contains(hidden.name))

        val creatorDirectory = JSONObject(
            URL("http://127.0.0.1:$testPort/api/channels?username=owner-77").readText()
        )
        val listed = creatorDirectory.getJSONArray("channels")
        val creatorSummary = (0 until listed.length())
            .map { listed.getJSONObject(it) }
            .first { it.optString("channelId") == hidden.channelId }
        assertTrue(creatorSummary.getBoolean("isHidden"))
        assertTrue(creatorSummary.getBoolean("isOwner"))
        assertFalse(creatorSummary.getBoolean("requiresKey"))

        val creatorRepo = BitVoidClientRepository()
        val otherRepo = BitVoidClientRepository()
        try {
            creatorRepo.joinChannel(
                hidden.channelId,
                rawPrivateKey = null,
                urlOverride = "http://127.0.0.1:$testPort",
                username = "owner-77"
            )
            assertEquals(hidden.channelId, creatorRepo.activeChannelSession.value?.channelId)

            otherRepo.joinChannel(
                hidden.channelId,
                rawPrivateKey = null,
                urlOverride = "http://127.0.0.1:$testPort",
                username = "other-22"
            )
            assertNull(otherRepo.activeChannelSession.value)
        } finally {
            creatorRepo.release()
            otherRepo.release()
        }
    }

    @Test
    fun `channel edit and delete are restricted to the creator`() {
        val created = BitVoidChannelManager.createChannel(
            rawName = "Managed Room",
            isPublic = true,
            creatorUsername = "creator-11"
        ).getOrThrow()

        val deniedEdit = BitVoidChannelManager.updateChannel(
            channelId = created.channelId,
            rawName = "Hijacked Room",
            isPublic = false,
            rawPrivateKey = null,
            mediaIds = emptySet(),
            isHidden = true,
            requesterUsername = "other-11"
        )
        assertTrue(deniedEdit.isFailure)
        assertEquals("Managed Room", BitVoidChannelManager.getChannel(created.channelId)?.name)

        val edited = BitVoidChannelManager.updateChannel(
            channelId = created.channelId,
            rawName = "Creator Room",
            isPublic = false,
            rawPrivateKey = "new-key",
            mediaIds = emptySet(),
            isHidden = true,
            requesterUsername = "creator-11"
        )
        assertTrue(edited.isSuccess)
        assertTrue(edited.getOrThrow().isHidden)

        assertTrue(
            BitVoidChannelManager.deleteChannelByCreator(created.channelId, "other-11").isFailure
        )
        assertNotNull(BitVoidChannelManager.getChannel(created.channelId))
        assertTrue(
            BitVoidChannelManager.deleteChannelByCreator(created.channelId, "creator-11").isSuccess
        )
        assertNull(BitVoidChannelManager.getChannel(created.channelId))
    }

    @Test
    fun `hidden user id is omitted from discovery but exact id chat still opens`() {
        val baseUrl = "http://127.0.0.1:$testPort"

        fun postJson(path: String, body: String): Pair<Int, String> {
            val conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseBody = stream?.readBytes()?.let { String(it, StandardCharsets.UTF_8) }.orEmpty()
            conn.disconnect()
            return code to responseBody
        }

        assertEquals(
            200,
            postJson(
                "/api/users/register",
                """{"username":"visible-01","discoverable":true}"""
            ).first
        )
        assertEquals(
            200,
            postJson(
                "/api/users/register",
                """{"username":"hidden-01","discoverable":false}"""
            ).first
        )

        val users = JSONObject(URL("$baseUrl/api/users").readText()).getJSONArray("users")
        val discovered = (0 until users.length()).map { users.getString(it) }
        assertTrue("visible-01" in discovered)
        assertFalse("hidden-01" in discovered)

        val exactHiddenStatus = JSONObject(
            URL("$baseUrl/api/users/status?username=hidden-01").readText()
        )
        assertEquals("hidden-01", exactHiddenStatus.getString("username"))
        assertTrue(exactHiddenStatus.getBoolean("available"))
        assertFalse(
            JSONObject(URL("$baseUrl/api/users/status?username=missing-01").readText())
                .getBoolean("available")
        )

        val exactOpen = postJson(
            "/api/dm/open",
            """{"myUsername":"visible-01","targetUsername":"hidden-01"}"""
        )
        assertEquals(200, exactOpen.first)
        assertEquals("hidden-01", JSONObject(exactOpen.second).getString("peerUsername"))
    }

    @Test
    fun `direct chat rejects usernames that are not present on the same node`() {
        val baseUrl = "http://127.0.0.1:$testPort"

        fun postJson(path: String, body: String): Pair<Int, String> {
            val conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseBody = stream?.readBytes()?.let { String(it, StandardCharsets.UTF_8) }.orEmpty()
            conn.disconnect()
            return code to responseBody
        }

        assertEquals(
            200,
            postJson("/api/users/register", """{"username":"alice-online"}""").first
        )

        val unavailable = postJson(
            "/api/dm/open",
            """{"myUsername":"alice-online","targetUsername":"bob-offline"}"""
        )
        assertEquals(404, unavailable.first)
        assertEquals("user_not_on_node", JSONObject(unavailable.second).getString("error"))
        assertTrue(JSONObject(unavailable.second).getString("detail").contains("not available"))

        assertEquals(
            200,
            postJson("/api/users/register", """{"username":"bob-offline"}""").first
        )
        val available = postJson(
            "/api/dm/open",
            """{"myUsername":"alice-online","targetUsername":"bob-offline"}"""
        )
        assertEquals(200, available.first)
        assertEquals("bob-offline", JSONObject(available.second).getString("peerUsername"))
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

    @Test
    fun `portal html uses dynamic viewport height left upload button and responsive desktop split layout`() {
        BitVoidChannelManager.ensureDefaultChannel()
        val conn = URL("http://127.0.0.1:$testPort/").openConnection() as HttpURLConnection
        assertEquals(200, conn.responseCode)
        val html = String(conn.inputStream.readBytes())
        conn.disconnect()

        assertTrue("Portal should use 100dvh so mobile browser address bar does not hide composer", html.contains("height: 100dvh;"))
        assertFalse("Portal must not override 100dvh with 100vh", html.contains("height: 100vh;"))
        assertTrue("Portal should define PC desktop split-pane media query", html.contains("@media (min-width: 900px)"))
        assertTrue("Portal should wrap workspace in wa-main-pane", html.contains("wa-main-pane"))
        assertTrue("Portal should include left + upload button", html.contains("id=\"chat-attach-btn\""))
        assertTrue("Portal should include arbitrary file input", html.contains("id=\"chat-file-input\""))
    }

    @Test
    fun `channel multipart upload supports arbitrary files with private token isolation`() = runBlocking {
        val prv = BitVoidChannelManager.createChannel(
            rawName = "Docs Vault",
            isPublic = false,
            rawChannelId = "CH-DOCS77",
            rawPrivateKey = null
        ).getOrThrow()

        val repo = BitVoidClientRepository()
        try {
            val baseUrl = "http://127.0.0.1:$testPort"
            repo.joinChannel(prv.channelId, null, baseUrl, username = "uploader-1")
            val session = repo.activeChannelSession.value
            assertNotNull(session)

            val fileBytes = "%PDF-1.4 BITVOID TEST PAYLOAD".toByteArray(StandardCharsets.UTF_8)
            val boundary = "----BitVoidBoundary${System.currentTimeMillis()}"

            // Unauthenticated upload returns 401
            val unauthRespCode = postMultipartBytes(
                url = "$baseUrl/api/channels/${prv.channelId}/upload",
                tokenHeader = null,
                tokenValue = null,
                boundary = boundary,
                fileName = "briefing.pdf",
                mimeType = "application/pdf",
                fileBytes = fileBytes,
                caption = "Read this"
            ).first
            assertEquals(401, unauthRespCode)

            // Authorized upload returns 200 with attachment metadata and makes file downloadable
            val (code, body) = postMultipartBytes(
                url = "$baseUrl/api/channels/${prv.channelId}/upload",
                tokenHeader = "X-Channel-Token",
                tokenValue = session!!.token,
                boundary = boundary,
                fileName = "briefing.pdf",
                mimeType = "application/pdf",
                fileBytes = fileBytes,
                caption = "Read this PDF"
            )
            assertEquals(200, code)
            val json = JSONObject(body)
            val msgObj = json.getJSONObject("message")
            assertEquals("Read this PDF", msgObj.getString("text"))
            val attObj = msgObj.getJSONObject("attachment")
            assertEquals("briefing.pdf", attObj.getString("name"))
            val downloadUrl = attObj.getString("downloadUrl")

            // Unauthorized download returns 401
            val unauthDl = URL("$baseUrl$downloadUrl").openConnection() as HttpURLConnection
            assertEquals(401, unauthDl.responseCode)
            unauthDl.disconnect()

            // Authorized download serves exact uploaded bytes
            val authDl = URL("$baseUrl$downloadUrl?token=${session.token}").openConnection() as HttpURLConnection
            assertEquals(200, authDl.responseCode)
            assertEquals("%PDF-1.4 BITVOID TEST PAYLOAD", String(authDl.inputStream.readBytes(), StandardCharsets.UTF_8))
            authDl.disconnect()

            // Channel media list now includes the uploaded file
            repo.refreshActiveChannelMedia()
            assertTrue(repo.activeChannelSession.value!!.mediaList.any { it.title == "briefing.pdf" })
        } finally {
            repo.release()
        }
    }

    @Test
    fun `direct chat multipart upload isolates files to dm participants`() = runBlocking {
        val baseUrl = "http://127.0.0.1:$testPort"
        val aliceRepo = BitVoidClientRepository()
        val bobRepo = BitVoidClientRepository()
        try {
            aliceRepo.registerUsername("alice-99", baseUrl)
            bobRepo.registerUsername("bob-42", baseUrl)
            aliceRepo.openDirectChat("alice-99", "bob-42", baseUrl)
            bobRepo.openDirectChat("bob-42", "alice-99", baseUrl)

            val aliceSession = aliceRepo.activeDirectSession.value!!
            val bobSession = bobRepo.activeDirectSession.value!!

            var attempts = 0
            while (bobRepo.directWsStatus.value != ClientWsStatus.LIVE && attempts < 30) {
                delay(50)
                attempts++
            }
            assertEquals(ClientWsStatus.LIVE, bobRepo.directWsStatus.value)

            val fileBytes = "CONFIDENTIAL DM ARCHIVE".toByteArray(StandardCharsets.UTF_8)
            val (code, body) = postMultipartBytes(
                url = "$baseUrl/api/dm/${aliceSession.dmId}/upload",
                tokenHeader = "X-DM-Token",
                tokenValue = aliceSession.token,
                boundary = "----BitVoidDmBoundary",
                fileName = "secret_notes.txt",
                mimeType = "text/plain",
                fileBytes = fileBytes,
                caption = ""
            )
            assertEquals(200, code)
            val attObj = JSONObject(body).getJSONObject("message").getJSONObject("attachment")
            val dmDownloadPath = attObj.getString("downloadUrl")
            assertTrue(dmDownloadPath.startsWith("/download/dm/"))

            // Bob receives the attachment over live WS
            attempts = 0
            while (bobRepo.directMessages.value.none { it.attachment?.name == "secret_notes.txt" } && attempts < 30) {
                delay(50)
                attempts++
            }
            assertTrue(bobRepo.directMessages.value.any { it.attachment?.name == "secret_notes.txt" })

            // Unauthorized access without DM token fails with 401
            val unauthDl = URL("$baseUrl$dmDownloadPath").openConnection() as HttpURLConnection
            assertEquals(401, unauthDl.responseCode)
            unauthDl.disconnect()

            // Bob's valid DM token downloads the exact file
            val bobDl = URL("$baseUrl$dmDownloadPath?token=${bobSession.token}").openConnection() as HttpURLConnection
            assertEquals(200, bobDl.responseCode)
            assertEquals("CONFIDENTIAL DM ARCHIVE", String(bobDl.inputStream.readBytes(), StandardCharsets.UTF_8))
            bobDl.disconnect()
        } finally {
            aliceRepo.release()
            bobRepo.release()
        }
    }

    @Test
    fun `wifi qr payload uses WPA when OS passphrase is present`() {
        val payload = com.bitvoid.network.ui.buildWifiQrPayload(
            ssid = "DIRECT-BV-BitVoid_Net",
            passphrase = "OSPASS1234"
        )
        assertEquals("WIFI:T:WPA;S:DIRECT-BV-BitVoid_Net;P:OSPASS1234;H:false;;", payload)
    }

    @Test
    fun `portal html exposes only audio live class controls and participant stage`() {
        BitVoidChannelManager.ensureDefaultChannel()
        val conn = URL("http://127.0.0.1:$testPort/").openConnection() as HttpURLConnection
        assertEquals(200, conn.responseCode)
        val permissionsPolicy = conn.getHeaderField("Permissions-Policy").orEmpty()
        assertTrue(permissionsPolicy.contains("microphone=(self)"))
        assertFalse(permissionsPolicy.contains("camera="))
        assertFalse(permissionsPolicy.contains("display-capture="))

        val html = String(conn.inputStream.readBytes(), StandardCharsets.UTF_8)
        conn.disconnect()
        assertTrue(html.contains("/ws/signaling"))
        assertTrue(html.contains("AUDIO CLASS &amp; CALL"))
        assertTrue(html.contains("rtc-start-class-host-btn"))
        assertTrue(html.contains("START LIVE AUDIO CLASS (HOST)"))
        assertTrue(html.contains("START AUDIO CALL"))
        assertTrue(html.contains("rtc-leave-btn"))
        assertTrue(html.contains("live-participants-grid"))
        assertTrue(html.contains("live-participant-card"))
        assertTrue(html.contains("direct-incoming-controls"))
        assertTrue(html.contains("meeting-active"))
        assertTrue(html.contains("activeChannel.channelId === 'general-chat'"))
        assertTrue(html.contains("joinRtcSession('AUDIO_CALL', 'SPEAKER', true, null, null)"))
        assertTrue(html.contains("navigator.mediaDevices.getUserMedia({ audio: true })"))
        assertTrue(html.contains("body: '{}'"))
        assertFalse(html.contains("rtc-share-screen-btn"))
        assertFalse(html.contains("rtc-cam-btn"))
        assertFalse(html.contains("rtc-upload-doc-btn"))
        assertFalse(html.contains("live-presentation-stage"))
        assertFalse(html.contains("live-video-grid"))
        assertFalse(html.contains("isVideoOn"))
        assertFalse(html.contains("isScreenSharing"))
        assertFalse(html.contains("withVideo"))
        assertFalse(html.contains("window.confirm"))
    }

    @Test
    fun `audio webrtc signaling enforces auth strips non-audio sdp and syncs microphone state`() = runBlocking {
        val classChannel = BitVoidChannelManager.createChannel(
            rawName = "Mesh Classroom",
            isPublic = false,
            rawChannelId = "CH-CLASS1",
            rawPrivateKey = null
        ).getOrThrow()

        // 1. Unauthorized call status & signaling upgrade rejected with 401
        val unauthStatus = URL("http://127.0.0.1:$testPort/api/channels/${classChannel.channelId}/call/status")
            .openConnection() as HttpURLConnection
        assertEquals(401, unauthStatus.responseCode)
        unauthStatus.disconnect()

        Socket("127.0.0.1", testPort).use { socket ->
            val wsHandshake = buildString {
                append("GET /ws/signaling?roomId=${classChannel.channelId}&peerId=intruder HTTP/1.1\r\n")
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
            assertTrue("Expected 401 on unauthorized signaling WS, got $statusLine", statusLine!!.contains("401"))
        }

        // 2. Unlock channel to obtain valid BVC token
        val repo = BitVoidClientRepository()
        val okClient = OkHttpClient()
        val teacherMessages = CopyOnWriteArrayList<JSONObject>()
        val studentMessages = CopyOnWriteArrayList<JSONObject>()
        var teacherWs: WebSocket? = null
        var studentWs: WebSocket? = null

        try {
            val baseUrl = "http://127.0.0.1:$testPort"
            repo.joinChannel(classChannel.channelId, null, baseUrl, username = "teacher-host")
            val session = repo.activeChannelSession.value
            assertNotNull(session)
            val token = session!!.token

            val teacherUrl = "ws://127.0.0.1:$testPort/ws/signaling?roomId=${classChannel.channelId}&channelId=${classChannel.channelId}&peerId=teacher_1&displayName=Prof&mode=LIVE_CLASSROOM&role=HOST_TEACHER&token=$token"
            val studentUrl = "ws://127.0.0.1:$testPort/ws/signaling?roomId=${classChannel.channelId}&channelId=${classChannel.channelId}&peerId=student_1&displayName=Ada&mode=LIVE_CLASSROOM&role=STUDENT_VIEWER&token=$token"

            teacherWs = okClient.newWebSocket(
                Request.Builder().url(teacherUrl).build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        teacherMessages.add(JSONObject(text))
                    }
                }
            )
            var waitCount = 0
            while (teacherMessages.none { it.optString("type") == "welcome" } && waitCount < 30) {
                delay(40)
                waitCount++
            }
            assertTrue(teacherMessages.any { it.optString("type") == "welcome" })

            studentWs = okClient.newWebSocket(
                Request.Builder().url(studentUrl).build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        studentMessages.add(JSONObject(text))
                    }
                }
            )
            waitCount = 0
            while ((studentMessages.none { it.optString("type") == "welcome" } ||
                        teacherMessages.none { it.optString("type") == "peer_joined" }) && waitCount < 30
            ) {
                delay(40)
                waitCount++
            }
            assertTrue(studentMessages.any { it.optString("type") == "welcome" })
            assertTrue(teacherMessages.any { it.optString("type") == "peer_joined" })

            // 3. Call status API reports 2 active classroom peers
            val statusConn = (URL("$baseUrl/api/channels/${classChannel.channelId}/call/status")
                .openConnection() as HttpURLConnection).apply {
                setRequestProperty("X-Channel-Token", token)
            }
            assertEquals(200, statusConn.responseCode)
            val statusJson = JSONObject(String(statusConn.inputStream.readBytes(), StandardCharsets.UTF_8))
            statusConn.disconnect()
            assertTrue(statusJson.getBoolean("isActive"))
            assertEquals(2, statusJson.getInt("participantCount"))
            assertEquals("LIVE_CLASSROOM", statusJson.getString("mode"))

            // 4. Relay SDP and ICE while retaining only the audio media section.
            val mixedOfferSdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=mid:0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:1\r\n"
            teacherWs.send(
                JSONObject()
                    .put("type", "offer")
                    .put("targetPeerId", "student_1")
                    .put("sdp", mixedOfferSdp)
                    .toString()
            )
            waitCount = 0
            while (studentMessages.none { it.optString("type") == "offer" } && waitCount < 30) {
                delay(40)
                waitCount++
            }
            val offerMsg = studentMessages.first { it.optString("type") == "offer" }
            assertEquals("teacher_1", offerMsg.getString("fromPeerId"))
            assertTrue(offerMsg.getString("sdp").contains("m=audio"))
            assertFalse(offerMsg.getString("sdp").contains("m=video"))

            studentWs.send("""{"type":"answer","targetPeerId":"teacher_1","sdp":"v=0-answer"}""")
            studentWs.send("""{"type":"ice","targetPeerId":"teacher_1","candidate":"cand:1 1 udp 1 192.168.43.2 5000 typ host","sdpMid":"0","sdpMLineIndex":0}""")
            waitCount = 0
            while ((teacherMessages.none { it.optString("type") == "answer" } ||
                        teacherMessages.none { it.optString("type") == "ice" }) && waitCount < 30
            ) {
                delay(40)
                waitCount++
            }
            assertEquals("v=0-answer", teacherMessages.first { it.optString("type") == "answer" }.getString("sdp"))
            assertTrue(teacherMessages.first { it.optString("type") == "ice" }.getString("candidate").contains("192.168.43.2"))

            teacherWs.send("""{"type":"media_state","isMuted":true}""")
            waitCount = 0
            while (studentMessages.none {
                    it.optString("type") == "peers_update" &&
                        it.optJSONObject("peer")?.optBoolean("isMuted") == true
                } && waitCount < 30
            ) {
                delay(40)
                waitCount++
            }
            val updatedPeer = studentMessages.last {
                it.optString("type") == "peers_update" &&
                    it.optJSONObject("peer")?.optBoolean("isMuted") == true
            }.getJSONObject("peer")
            assertTrue(updatedPeer.getBoolean("isMuted"))
            assertFalse(updatedPeer.has("isVideoOn"))
            assertFalse(updatedPeer.has("isScreenSharing"))

            // 5. End session broadcasts session_ended and clears room
            teacherWs.send("""{"type":"end_session"}""")
            waitCount = 0
            while (studentMessages.none { it.optString("type") == "session_ended" } && waitCount < 30) {
                delay(40)
                waitCount++
            }
            assertTrue(studentMessages.any { it.optString("type") == "session_ended" })

            val afterEndConn = (URL("$baseUrl/api/channels/${classChannel.channelId}/call/status")
                .openConnection() as HttpURLConnection).apply {
                setRequestProperty("X-Channel-Token", token)
            }
            assertEquals(200, afterEndConn.responseCode)
            val afterEndJson = JSONObject(String(afterEndConn.inputStream.readBytes(), StandardCharsets.UTF_8))
            afterEndConn.disconnect()
            assertFalse(afterEndJson.getBoolean("isActive"))
            assertEquals(0, afterEndJson.getInt("participantCount"))
        } finally {
            teacherWs?.cancel()
            studentWs?.cancel()
            repo.release()
            okClient.dispatcher.executorService.shutdown()
            okClient.connectionPool.evictAll()
        }
    }

    @Test
    fun `audio live class generates CLS code validates join by id and exposes no presentation state`() = runBlocking {
        val classChannel = BitVoidChannelManager.createChannel(
            rawName = "Physics 101",
            isPublic = false,
            rawChannelId = "CH-PHYS1",
            rawPrivateKey = null
        ).getOrThrow()

        val baseUrl = "http://127.0.0.1:$testPort"

        // 0. Portal HTML exposes the Live Class code pill and join-by-ID inputs
        val portalConn = URL("$baseUrl/").openConnection() as HttpURLConnection
        assertEquals(200, portalConn.responseCode)
        val portalHtml = String(portalConn.inputStream.readBytes(), StandardCharsets.UTF_8)
        portalConn.disconnect()
        assertFalse(portalHtml.contains("rtc-upload-doc-btn"))
        assertFalse(portalHtml.contains("live-presentation-stage"))
        assertFalse(portalHtml.contains("slide_nav"))
        assertTrue(portalHtml.contains("live-class-code-pill"))
        assertTrue(portalHtml.contains("sidebar-class-code-input"))
        assertTrue(portalHtml.contains("live-join-id-row"))

        // 1. Invalid Live Class ID returns 404 before any session starts
        val invalidJoin = (URL("$baseUrl/api/live-class/join").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            outputStream.write("""{"classCode":"CLS-DEAD-99","username":"stu-1"}""".toByteArray(StandardCharsets.UTF_8))
        }
        assertEquals(404, invalidJoin.responseCode)
        invalidJoin.disconnect()

        val hostRepo = BitVoidClientRepository()
        val studentRepo = BitVoidClientRepository()
        val okClient = OkHttpClient()
        val hostMessages = CopyOnWriteArrayList<JSONObject>()
        val studentMessages = CopyOnWriteArrayList<JSONObject>()
        var hostWs: WebSocket? = null
        var studentWs: WebSocket? = null

        try {
            // Host unlocks private channel & starts LIVE_CLASSROOM session
            hostRepo.joinChannel(classChannel.channelId, null, baseUrl, username = "prof-host")
            val hostSession = hostRepo.activeChannelSession.value!!
            val hostUrl = "ws://127.0.0.1:$testPort/ws/signaling?roomId=${classChannel.channelId}&channelId=${classChannel.channelId}&peerId=prof_1&displayName=Prof&mode=LIVE_CLASSROOM&role=HOST_TEACHER&token=${hostSession.token}"
            hostWs = okClient.newWebSocket(
                Request.Builder().url(hostUrl).build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        hostMessages.add(JSONObject(text))
                    }
                }
            )
            var waitCount = 0
            while (hostMessages.none { it.optString("type") == "welcome" } && waitCount < 30) {
                delay(40)
                waitCount++
            }
            val welcome = hostMessages.first { it.optString("type") == "welcome" }
            val generatedClassCode = welcome.optString("classCode")
            assertTrue("Expected CLS-... Live Class ID, got '$generatedClassCode'", generatedClassCode.startsWith("CLS-"))

            // 2. The former Live Class materials endpoint no longer exists.
            val removedMaterialsEndpoint = (URL(
                "$baseUrl/api/live-class/${classChannel.channelId}/materials"
            ).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("X-Channel-Token", hostSession.token)
                outputStream.use { it.write(ByteArray(0)) }
            }
            assertEquals(405, removedMaterialsEndpoint.responseCode)
            removedMaterialsEndpoint.disconnect()

            // 3. Student joins purely via generated Live Class ID without knowing private CH-ID
            val joinRes = studentRepo.joinLiveClassByCode(generatedClassCode, "student-ada", baseUrl)
            assertTrue("Student join-by-code should succeed: ${joinRes.exceptionOrNull()}", joinRes.isSuccess)
            val joinInfo = joinRes.getOrThrow()
            assertEquals(classChannel.channelId, joinInfo.roomId)
            assertEquals(generatedClassCode, joinInfo.classCode)
            assertTrue(joinInfo.token.startsWith("BVC."))
            assertEquals(ClientWsStatus.DISCONNECTED, studentRepo.wsStatus.value)
            assertTrue(studentRepo.channelMessages.value.isEmpty())
            assertTrue(studentRepo.activeChannelSession.value?.mediaList.orEmpty().isEmpty())

            val studentUrl = "ws://127.0.0.1:$testPort/ws/signaling?roomId=${joinInfo.roomId}&channelId=${joinInfo.channelId}&peerId=stu_ada&displayName=${joinInfo.callsign}&mode=LIVE_CLASSROOM&role=STUDENT_VIEWER&token=${joinInfo.token}"
            studentWs = okClient.newWebSocket(
                Request.Builder().url(studentUrl).build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        studentMessages.add(JSONObject(text))
                    }
                }
            )
            waitCount = 0
            while (studentMessages.none { it.optString("type") == "welcome" } && waitCount < 30) {
                delay(40)
                waitCount++
            }
            assertEquals(generatedClassCode, studentMessages.first { it.optString("type") == "welcome" }.optString("classCode"))

            // 4. Welcome and subsequent signaling contain participant/audio state only.
            val studentWelcome = studentMessages.first { it.optString("type") == "welcome" }
            assertFalse(studentWelcome.has("materials"))
            assertFalse(studentWelcome.has("activeMaterialId"))
            assertFalse(studentWelcome.has("activePageIndex"))

            hostWs.send("""{"type":"slide_nav","materialId":"removed","pageIndex":4}""")
            delay(150)
            assertFalse(studentMessages.any {
                it.optString("type") == "material_shared" || it.optString("type") == "material_state"
            })
        } finally {
            hostWs?.cancel()
            studentWs?.cancel()
            hostRepo.release()
            studentRepo.release()
            okClient.dispatcher.executorService.shutdown()
            okClient.connectionPool.evictAll()
        }
    }

    @Test
    fun `direct dm webrtc signaling isolates call room to participants and reports status`() = runBlocking {
        val baseUrl = "http://127.0.0.1:$testPort"
        val aliceRepo = BitVoidClientRepository()
        val bobRepo = BitVoidClientRepository()
        val eveRepo = BitVoidClientRepository()
        val okClient = OkHttpClient()
        val aliceSignals = CopyOnWriteArrayList<JSONObject>()
        val bobSignals = CopyOnWriteArrayList<JSONObject>()
        var aliceWs: WebSocket? = null
        var bobWs: WebSocket? = null

        try {
            aliceRepo.registerUsername("alice-call", baseUrl)
            bobRepo.registerUsername("bob-call", baseUrl)
            eveRepo.registerUsername("eve-intruder", baseUrl)

            aliceRepo.openDirectChat("alice-call", "bob-call", baseUrl)
            bobRepo.openDirectChat("bob-call", "alice-call", baseUrl)
            eveRepo.openDirectChat("eve-intruder", "bob-call", baseUrl)

            val aliceSession = aliceRepo.activeDirectSession.value!!
            val bobSession = bobRepo.activeDirectSession.value!!
            val eveSession = eveRepo.activeDirectSession.value!!
            val dmId = aliceSession.dmId

            // Unauthorized call status without token or with wrong DM token returns 401
            val unauthStatus = URL("$baseUrl/api/dm/$dmId/call/status").openConnection() as HttpURLConnection
            assertEquals(401, unauthStatus.responseCode)
            unauthStatus.disconnect()

            val eveStatus = (URL("$baseUrl/api/dm/$dmId/call/status").openConnection() as HttpURLConnection).apply {
                setRequestProperty("X-DM-Token", eveSession.token)
            }
            assertEquals(401, eveStatus.responseCode)
            eveStatus.disconnect()

            // Caller rings first. The caller may open a local meeting preview immediately,
            // while the recipient remains blocked from signaling until explicit acceptance.
            assertTrue(
                aliceRepo.requestDirectCall(
                    dmId,
                    aliceSession.token,
                    urlOverride = baseUrl
                ).isSuccess
            )
            assertTrue(
                BitVoidSignalingServer.authorizeSignalingJoin(
                    roomId = dmId,
                    channelId = dmId,
                    token = aliceSession.token
                )
            )
            assertFalse(
                BitVoidSignalingServer.authorizeSignalingJoin(
                    roomId = dmId,
                    channelId = dmId,
                    token = bobSession.token
                )
            )
            bobRepo.fetchDirectCallStatus(dmId, bobSession.token, baseUrl)
            val ringing = bobRepo.activeDirectCallStatus.value
            assertNotNull(ringing)
            assertEquals(DirectCallPhase.RINGING, ringing!!.directCallPhase)
            assertTrue(ringing.isIncoming)
            assertEquals("alice-call", ringing.callerUsername)

            assertTrue(bobRepo.acceptDirectCall(dmId, bobSession.token, baseUrl).isSuccess)
            aliceRepo.fetchDirectCallStatus(dmId, aliceSession.token, baseUrl)
            assertEquals(
                DirectCallPhase.ACCEPTED,
                aliceRepo.activeDirectCallStatus.value?.directCallPhase
            )
            assertTrue(
                BitVoidSignalingServer.authorizeSignalingJoin(
                    roomId = dmId,
                    channelId = dmId,
                    token = bobSession.token
                )
            )

            val dmMaterialUpload = (URL(
                "$baseUrl/api/live-class/$dmId/materials"
            ).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("X-DM-Token", bobSession.token)
                outputStream.use { it.write(ByteArray(0)) }
            }
            assertEquals(405, dmMaterialUpload.responseCode)
            dmMaterialUpload.disconnect()

            aliceWs = okClient.newWebSocket(
                Request.Builder()
                    .url("ws://127.0.0.1:$testPort/ws/signaling?roomId=$dmId&channelId=$dmId&peerId=alice_p&displayName=alice-call&mode=AUDIO_CALL&role=SPEAKER&token=${aliceSession.token}")
                    .build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        aliceSignals.add(JSONObject(text))
                    }
                }
            )
            bobWs = okClient.newWebSocket(
                Request.Builder()
                    // A modified Direct Chat client cannot relabel an audio call as Live Class.
                    .url("ws://127.0.0.1:$testPort/ws/signaling?roomId=$dmId&channelId=$dmId&peerId=bob_p&displayName=bob-call&mode=LIVE_CLASSROOM&role=HOST_TEACHER&token=${bobSession.token}")
                    .build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        bobSignals.add(JSONObject(text))
                    }
                }
            )

            var waitCount = 0
            while ((aliceSignals.none { it.optString("type") == "welcome" } ||
                        bobSignals.none { it.optString("type") == "welcome" }) && waitCount < 30
            ) {
                delay(40)
                waitCount++
            }
            assertTrue(aliceSignals.any { it.optString("type") == "welcome" })
            assertTrue(bobSignals.any { it.optString("type") == "welcome" })
            val bobWelcome = bobSignals.first { it.optString("type") == "welcome" }
            assertEquals(SignalingSessionMode.AUDIO_CALL.name, bobWelcome.getString("mode"))
            val welcomedBob = bobWelcome.getJSONArray("peers")
                .let { peers ->
                    (0 until peers.length())
                        .map { peers.getJSONObject(it) }
                        .first { it.getString("peerId") == "bob_p" }
                }
            assertFalse(welcomedBob.has("isVideoOn"))
            assertFalse(welcomedBob.has("isScreenSharing"))

            // Valid participant receives active DM call status
            bobRepo.fetchDirectCallStatus(dmId, bobSession.token, baseUrl)
            val status = bobRepo.activeDirectCallStatus.value
            assertNotNull(status)
            assertTrue(status!!.isActive)
            assertEquals(2, status.participantCount)

            // Relay SDP offer directly from Alice to Bob inside DM call room
            aliceWs.send("""{"type":"offer","targetPeerId":"bob_p","sdp":"v=0-dm-offer"}""")
            waitCount = 0
            while (bobSignals.none { it.optString("type") == "offer" } && waitCount < 30) {
                delay(40)
                waitCount++
            }
            assertEquals("v=0-dm-offer", bobSignals.first { it.optString("type") == "offer" }.getString("sdp"))

            // Signaling synchronizes microphone state only.
            aliceWs.send("""{"type":"media_state","isMuted":true}""")
            waitCount = 0
            while (bobSignals.none { it.optString("type") == "peers_update" } && waitCount < 30) {
                delay(40)
                waitCount++
            }
            val voiceUpdate = bobSignals.last { it.optString("type") == "peers_update" }
            val updatedAlice = voiceUpdate.getJSONArray("peers")
                .let { peers ->
                    (0 until peers.length())
                        .map { peers.getJSONObject(it) }
                        .first { it.getString("peerId") == "alice_p" }
                }
            assertTrue(updatedAlice.getBoolean("isMuted"))
            assertFalse(updatedAlice.has("isVideoOn"))
            assertFalse(updatedAlice.has("isScreenSharing"))
        } finally {
            aliceWs?.cancel()
            bobWs?.cancel()
            aliceRepo.release()
            bobRepo.release()
            eveRepo.release()
            okClient.dispatcher.executorService.shutdown()
            okClient.connectionPool.evictAll()
        }
    }

    @Test
    fun `named live class honors show vs hidden visibility creator always sees own hidden class and strict id access`() = runBlocking {
        val baseUrl = "http://127.0.0.1:$testPort"
        val hostRepo = BitVoidClientRepository()
        val peerRepo = BitVoidClientRepository()
        val okClient = OkHttpClient()
        var creatorWs: WebSocket? = null
        val creatorSignals = CopyOnWriteArrayList<JSONObject>()

        try {
            // 1. Creator starts a Hidden Live Class and a Visible (Show) Live Class
            val hiddenStart = hostRepo.startLiveClassSession(
                name = "Secret Seminar",
                isHidden = true,
                creatorUsername = "prof-creator",
                urlOverride = baseUrl
            )
            assertTrue("Hidden start should succeed: ${hiddenStart.exceptionOrNull()}", hiddenStart.isSuccess)
            val hiddenInfo = hiddenStart.getOrThrow()
            assertTrue(hiddenInfo.roomId.startsWith("LC-"))
            assertTrue(hiddenInfo.classCode.startsWith("CLS-"))
            assertTrue(hiddenInfo.isHidden)
            assertEquals("prof-creator", hiddenInfo.creatorUsername)

            val showStart = hostRepo.startLiveClassSession(
                name = "Open Lecture",
                isHidden = false,
                creatorUsername = "prof-creator",
                urlOverride = baseUrl
            )
            assertTrue("Show start should succeed: ${showStart.exceptionOrNull()}", showStart.isSuccess)
            val showInfo = showStart.getOrThrow()
            assertFalse(showInfo.isHidden)

            // 2. Peer (non-creator) sees ONLY the visible Live Class, never the hidden one, and does not receive creator's classCode
            peerRepo.fetchLiveClasses("other-student", baseUrl)
            val peerList = peerRepo.liveClasses.value
            assertEquals(1, peerList.size)
            assertEquals("Open Lecture", peerList.first().name)
            assertEquals("", peerList.first().classCode)
            assertFalse(peerList.any { it.roomId == hiddenInfo.roomId })

            // 3. Creator ALWAYS sees BOTH visible and hidden Live Classes with their CLS codes
            hostRepo.fetchLiveClasses("prof-creator", baseUrl)
            val creatorList = hostRepo.liveClasses.value
            assertEquals(2, creatorList.size)
            val creatorHiddenEntry = creatorList.firstOrNull { it.roomId == hiddenInfo.roomId }
            assertNotNull(creatorHiddenEntry)
            assertTrue(creatorHiddenEntry!!.isHidden)
            assertEquals(hiddenInfo.classCode, creatorHiddenEntry.classCode)

            // 4. Hidden Live Class cannot be joined by roomId alone as non-creator -> 403
            val hiddenByRoomForbidden = (URL("$baseUrl/api/live-class/join").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.write("""{"roomId":"${hiddenInfo.roomId}","username":"other-student"}""".toByteArray(StandardCharsets.UTF_8))
            }
            assertEquals(403, hiddenByRoomForbidden.responseCode)
            hiddenByRoomForbidden.disconnect()

            // 5. Wrong Class ID returns 404
            val wrongCodeConn = (URL("$baseUrl/api/live-class/join").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.write("""{"classCode":"CLS-NOPE-00","username":"other-student"}""".toByteArray(StandardCharsets.UTF_8))
            }
            assertEquals(404, wrongCodeConn.responseCode)
            wrongCodeConn.disconnect()

            // 6. Visible Live Class can be joined by roomId on same local Wi-Fi node -> 200
            val showJoinRes = peerRepo.joinLiveClassByRoomId(showInfo.roomId, "other-student", baseUrl)
            assertTrue("Visible Live Class join on same node should succeed", showJoinRes.isSuccess)
            assertTrue(showJoinRes.getOrThrow().token.startsWith("BVC."))

            // 7. Hidden Live Class unlocks cleanly when exact CLS code is provided -> 200
            val hiddenJoinRes = peerRepo.joinLiveClassByCode(hiddenInfo.classCode, "other-student", baseUrl)
            assertTrue("Hidden Live Class join with exact code should succeed", hiddenJoinRes.isSuccess)
            val joinedHidden = hiddenJoinRes.getOrThrow()
            assertEquals(hiddenInfo.roomId, joinedHidden.roomId)
            assertEquals(hiddenInfo.classCode, joinedHidden.classCode)
            assertTrue(joinedHidden.token.startsWith("BVC."))

            // 8. /ws/signaling on LC-* without token returns 401
            Socket("127.0.0.1", testPort).use { socket ->
                val wsHandshake = buildString {
                    append("GET /ws/signaling?roomId=${hiddenInfo.roomId}&channelId=${hiddenInfo.channelId}&peerId=anon&mode=LIVE_CLASSROOM&role=STUDENT_VIEWER HTTP/1.1\r\n")
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
                assertTrue("Expected 401 on unauthorized LC-* signaling WS, got $statusLine", statusLine!!.contains("401"))
            }

            // 9. /ws/signaling with valid BVC token upgrades cleanly and includes class metadata in welcome
            creatorWs = okClient.newWebSocket(
                Request.Builder()
                    .url("ws://127.0.0.1:$testPort/ws/signaling?roomId=${hiddenInfo.roomId}&channelId=${hiddenInfo.channelId}&peerId=prof_p&displayName=prof-creator&mode=LIVE_CLASSROOM&role=HOST_TEACHER&token=${hiddenInfo.token}")
                    .build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        creatorSignals.add(JSONObject(text))
                    }
                }
            )
            var waitCount = 0
            while (creatorSignals.none { it.optString("type") == "welcome" } && waitCount < 30) {
                delay(40)
                waitCount++
            }
            val welcome = creatorSignals.firstOrNull { it.optString("type") == "welcome" }
            assertNotNull(welcome)
            assertEquals(hiddenInfo.classCode, welcome!!.optString("classCode"))
            assertEquals("Secret Seminar", welcome.optString("className"))
            assertEquals("prof-creator", welcome.optString("creatorUsername"))
            assertTrue(welcome.optBoolean("isHidden"))

            // 10. endSession removes both signaling room and backing LC-* channel
            BitVoidSignalingServer.endSession(hiddenInfo.roomId)
            hostRepo.fetchLiveClasses("prof-creator", baseUrl)
            assertFalse(hostRepo.liveClasses.value.any { it.roomId == hiddenInfo.roomId })
            assertNull(BitVoidChannelManager.getChannel(hiddenInfo.roomId))

            val afterEndJoin = (URL("$baseUrl/api/live-class/join").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.write("""{"classCode":"${hiddenInfo.classCode}","username":"other-student"}""".toByteArray(StandardCharsets.UTF_8))
            }
            assertEquals(404, afterEndJoin.responseCode)
            afterEndJoin.disconnect()
        } finally {
            creatorWs?.cancel()
            hostRepo.release()
            peerRepo.release()
            okClient.dispatcher.executorService.shutdown()
            okClient.connectionPool.evictAll()
        }
    }

    private fun postMultipartBytes(
        url: String,
        tokenHeader: String?,
        tokenValue: String?,
        boundary: String,
        fileName: String,
        mimeType: String,
        fileBytes: ByteArray,
        caption: String
    ): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (tokenHeader != null && tokenValue != null) {
                setRequestProperty(tokenHeader, tokenValue)
            }
        }
        conn.outputStream.use { out ->
            val header = buildString {
                if (caption.isNotEmpty()) {
                    append("--$boundary\r\n")
                    append("Content-Disposition: form-data; name=\"text\"\r\n\r\n")
                    append("$caption\r\n")
                }
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
                append("Content-Type: $mimeType\r\n\r\n")
            }
            out.write(header.toByteArray(StandardCharsets.UTF_8))
            out.write(fileBytes)
            out.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.readBytes()?.let { String(it, StandardCharsets.UTF_8) }.orEmpty()
        conn.disconnect()
        return code to body
    }
}
