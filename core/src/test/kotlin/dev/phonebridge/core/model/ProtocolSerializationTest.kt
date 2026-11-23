package dev.phonebridge.core.model

import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.core.util.PbJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolSerializationTest {
    private val device = Identity.generate(PeerRole.DEVICE, "Pixel 9")
    private val bot = Identity.generate(PeerRole.BOT, "helper")
    private val deviceId = device.peerId
    private val botId = bot.peerId

    private fun <T> roundTrip(serializer: KSerializer<T>, value: T): T =
        PbJson.decodeFromString(serializer, PbJson.encodeToString(serializer, value))

    // ----- relay envelopes -------------------------------------------------------------------------

    @Test
    fun everyEnvelopeTypeRoundTrips() {
        val envelopes = listOf(
            RelayEnvelope.hello(deviceId, device.publicKeyBase64, PeerRole.DEVICE, proofB64 = "cHJvb2Y", nonceB64 = "bm9uY2U"),
            RelayEnvelope.helloChallenge("bm9uY2U", B64Url.encode(ByteArray(32) { 1 })),
            RelayEnvelope.route(deviceId, botId, byteArrayOf(1, 2, 3)),
            RelayEnvelope.ping(deviceId),
            RelayEnvelope.pong(deviceId, "id-1"),
            RelayEnvelope.error("peer_offline", "bot is not connected", pos = 2),
            RelayEnvelope.relayMoved("wss://new.example:8443", "token-1", 1_700_000_000L),
            RelayEnvelope.pairLookup(botId, "7K4-Q9B"),
            RelayEnvelope.pairOffer("""{"v":1}""".toByteArray()),
        )
        for (envelope in envelopes) {
            val decoded = roundTrip(RelayEnvelope.serializer(), envelope)
            assertEquals(envelope, decoded)
            assertEquals(envelope.type, decoded.type)
        }
    }

    @Test
    fun routeEnvelopeMatchesTheDocumentedShape() {
        val envelope = RelayEnvelope.route(deviceId, botId, byteArrayOf(9, 8, 7), id = "uuid-1")
        val json = EnvelopeCodec.encode(envelope)
        assertEquals(
            """{"v":1,"type":"route","from":"$deviceId","to":"$botId","id":"uuid-1","payload":"CQgH"}""",
            json,
        )
        assertEquals(listOf<Byte>(9, 8, 7), EnvelopeCodec.decode(json).payloadBytes().toList())
    }

    @Test
    fun helloEnvelopeUsesTheDocumentedRoleAndKeyFields() {
        val json = EnvelopeCodec.encode(RelayEnvelope.hello(deviceId, device.publicKeyBase64, PeerRole.DEVICE))
        assertTrue(json.contains("\"type\":\"hello\""))
        assertTrue(json.contains("\"role\":\"device\""))
        assertTrue(json.contains("\"pub\":\"${device.publicKeyBase64}\""))
        assertTrue(json.contains("\"v\":1"))
    }

    @Test
    fun envelopesDecodeFromSpecShapedJsonAndIgnoreNewFields() {
        val json = """{"v":1,"type":"ping","from":"$deviceId","id":"x","future_field":true}"""
        val envelope = EnvelopeCodec.decode(json)
        assertEquals(RelayType.PING, envelope.type)
        assertEquals(deviceId, envelope.from)
        assertEquals("x", envelope.id)

        val hello = EnvelopeCodec.decode(
            """{"v":1,"type":"hello","from":"$botId","pub":"abc","sig":"def","role":"bot"}""",
        )
        assertEquals(PeerRole.BOT, hello.role)
        assertEquals("abc", hello.pub)
        assertEquals("def", hello.sig)
    }

    @Test
    fun unknownEnvelopeTypesDecodeButAreMarkedUnknown() {
        val envelope = RelayEnvelope(type = "some_future_type", from = deviceId)
        assertFalse(envelope.isKnownType)
        assertFailsWith<ProtocolException> { envelope.requireKnownType() }
        assertTrue(RelayEnvelope(type = RelayType.ROUTE).isKnownType)
        assertFailsWith<ProtocolException> { RelayEnvelope(type = RelayType.ROUTE).payloadBytes() }
        assertFailsWith<ProtocolException> { EnvelopeCodec.decode("not json") }
    }

    @Test
    fun oversizedEnvelopesAreRejected() {
        val huge = "a".repeat(EnvelopeCodec.MAX_BYTES + 1)
        assertFailsWith<ProtocolException> { EnvelopeCodec.decode(huge) }
    }

    // ----- pairing messages -----------------------------------------------------------------------

    @Test
    fun pairingOffersRoundTripThroughJson() {
        val offer = PairingOffer(
            relay = "wss://relay.example:8443",
            deviceId = deviceId,
            devicePub = device.publicKeyBase64,
            secret = B64Url.encode(ByteArray(32) { it.toByte() }),
            code = "7K4-Q9B",
            exp = 1_700_000_600L,
            deviceName = "Pixel 9",
        )
        offer.validate()
        assertEquals(offer, roundTrip(PairingOffer.serializer(), offer))

        val json = PbJson.encodeToString(PairingOffer.serializer(), offer)
        for (key in listOf("\"device_id\"", "\"device_pub\"", "\"secret\"", "\"code\"", "\"exp\"", "\"relay\"")) {
            assertTrue(json.contains(key), "offers must carry $key")
        }
    }

    @Test
    fun pairRequestAndResultRoundTrip() {
        val request = PairRequest(
            botName = "helper",
            botPub = bot.publicKeyBase64,
            requestedLevel = PermissionLevel.FULL,
            botId = botId,
        )
        request.validate()
        val requestJson = PbJson.encodeToString(PairRequest.serializer(), request)
        assertTrue(requestJson.contains("\"requested_level\":\"full\""))
        assertEquals(request, roundTrip(PairRequest.serializer(), request))

        val granted = PairResult.granted("Pixel 9", deviceId, PermissionLevel.CONTROL)
        assertEquals(granted, roundTrip(PairResult.serializer(), granted))
        val grantedJson = PbJson.encodeToString(PairResult.serializer(), granted)
        assertTrue(grantedJson.contains("\"granted_level\":\"control\""))
        assertTrue(grantedJson.contains("\"ok\":true"))

        val denied = PairResult.denied(PairingReasons.DENIED, "Pixel 9", deviceId)
        assertEquals(denied, roundTrip(PairResult.serializer(), denied))
        assertTrue(PbJson.encodeToString(PairResult.serializer(), denied).contains("\"ok\":false"))
    }

    @Test
    fun mismatchedPairingKeysAreRejected() {
        val request = PairRequest(botName = "helper", botPub = bot.publicKeyBase64, botId = deviceId)
        assertFailsWith<ProtocolException> { request.validate() }
        assertFailsWith<ProtocolException> {
            PairRequest(botName = " ", botPub = bot.publicKeyBase64).validate()
        }
    }

    // ----- application requests -------------------------------------------------------------------

    private fun sampleArgs(): Map<CommandType, CommandArgs> = mapOf(
        CommandType.STATUS to StatusArgs,
        CommandType.SCREENSHOT to ScreenshotArgs(format = "jpeg", quality = 80, maxWidth = 720),
        CommandType.UI_TREE to UiTreeArgs(maxDepth = 6, visibleOnly = false),
        CommandType.NOTIFICATIONS to NotificationsArgs(since = 1_700_000_000_000L),
        CommandType.TAP to TapArgs(x = 540, y = 1200),
        CommandType.LONG_PRESS to LongPressArgs(x = 10, y = 20, ms = 750),
        CommandType.SWIPE to SwipeArgs(x1 = 1, y1 = 2, x2 = 300, y2 = 400, ms = 250),
        CommandType.TYPE to TypeArgs(text = "hello", nodeId = "node-1"),
        CommandType.KEY to KeyArgs(key = KeyName.QUICK_SETTINGS),
        CommandType.OPEN_APP to OpenAppArgs(packageName = "com.example.app"),
        CommandType.REQUEST_CONTROL to RequestControlArgs,
        CommandType.RELEASE_CONTROL to ReleaseControlArgs,
        CommandType.INSTALL_APK to InstallApkArgs(url = "https://example.com/app.apk"),
        CommandType.SHELL to ShellArgs(command = "id", timeoutMs = 5_000),
        CommandType.FILES to FilesArgs(op = FileOp.WRITE, path = "/sdcard/x.txt", dataB64 = "aGk"),
        CommandType.SETTINGS to SettingsArgs(
            namespace = SettingsNamespace.SECURE,
            key = "enabled_accessibility_services",
            value = "com.example/.Svc",
        ),
        CommandType.RELAY_MOVE to RelayMoveArgs(to = "this"),
    )

    @Test
    fun everyCommandHasTypedArgsThatValidateAndRoundTrip() {
        val samples = sampleArgs()
        assertEquals(CommandType.entries.toSet(), samples.keys, "every command needs a typed-args sample")

        for ((type, args) in samples) {
            args.validate()
            val request = AppMessages.request("id-${type.wire}", type, args)
            assertEquals(type.wire, request.cmd)
            assertEquals(type, request.type)

            val decoded = AppMessages.parse(AppMessages.encodeRequest(request))
            val parsed = (decoded as AppMessage.Request).request
            assertEquals("id-${type.wire}", parsed.id)
            assertEquals(type, parsed.type)
            assertEquals(args, Commands.decodeArgs(type, parsed.args))
        }
    }

    @Test
    fun tapRequestMatchesTheDocumentedJson() {
        val request = AppMessages.request("abc", CommandType.TAP, TapArgs(x = 540, y = 1200))
        assertEquals(
            """{"id":"abc","cmd":"tap","args":{"x":540,"y":1200}}""",
            AppMessages.encodeRequest(request),
        )
    }

    @Test
    fun argsDecodeFromHandwrittenJson() {
        val element = PbJson.parseToJsonElement("""{"max_depth":3,"visible_only":false}""")
        assertEquals(UiTreeArgs(maxDepth = 3, visibleOnly = false), Commands.decodeArgs(CommandType.UI_TREE, element))

        val empty = Commands.decodeArgs(CommandType.STATUS, JsonObject(emptyMap()))
        assertEquals(StatusArgs, empty)
    }

    // ----- application responses and results ------------------------------------------------------

    private fun sampleResults(): Map<CommandType, CommandResult> = mapOf(
        CommandType.STATUS to StatusResult(
            device = "Pixel 9",
            battery = 88,
            screenOn = true,
            controller = botId,
            queue = listOf("other"),
            shizuku = true,
        ),
        CommandType.SCREENSHOT to ScreenshotResult(
            mime = "image/jpeg",
            width = 720,
            height = 1600,
            chunked = true,
            chunkId = "abc",
            total = 4,
            size = 900_000,
            sha256 = "00ff",
        ),
        CommandType.UI_TREE to UiTreeResult(
            root = UiNode(
                id = "root",
                cls = "android.widget.FrameLayout",
                text = null,
                desc = "screen",
                resId = "android:id/content",
                bounds = listOf(0, 0, 1080, 2400),
                clickable = false,
                focusable = true,
                editable = false,
                children = listOf(
                    UiNode(id = "child", cls = "Button", text = "OK", bounds = listOf(10, 10, 200, 100), visible = true, clickable = true),
                ),
            ),
        ),
        CommandType.NOTIFICATIONS to NotificationsResult(
            items = listOf(NotificationItem(pkg = "com.example", title = "Hi", text = "there", time = 42)),
        ),
        CommandType.TAP to EmptyResult,
        CommandType.LONG_PRESS to EmptyResult,
        CommandType.SWIPE to EmptyResult,
        CommandType.TYPE to EmptyResult,
        CommandType.KEY to EmptyResult,
        CommandType.OPEN_APP to EmptyResult,
        CommandType.REQUEST_CONTROL to RequestControlResult(granted = false, position = 2),
        CommandType.RELEASE_CONTROL to EmptyResult,
        CommandType.INSTALL_APK to InstallApkResult(packageName = "com.example.app"),
        CommandType.SHELL to ShellResult(exit = 0, stdout = "ok", stderr = ""),
        CommandType.FILES to FilesResult(
            op = "list",
            entries = listOf(FileEntry(name = "a.txt", path = "/sdcard/a.txt", isDir = false, size = 12, mtime = 99)),
        ),
        CommandType.SETTINGS to SettingsResult(value = "1"),
        CommandType.RELAY_MOVE to RelayMoveResult(newUrl = "wss://new.example"),
    )

    @Test
    fun everyCommandHasATypedResultThatRoundTrips() {
        val results = sampleResults()
        assertEquals(CommandType.entries.toSet(), results.keys, "every command needs a typed-result sample")

        for ((type, result) in results) {
            val response = AppMessages.response("id-${type.wire}", type, result)
            val parsed = AppMessages.parse(AppMessages.encodeResponse(response)) as AppMessage.Response
            assertEquals("id-${type.wire}", parsed.response.id)
            assertTrue(parsed.response.ok)
            assertEquals(result, Commands.decodeResult(type, parsed.response.result))
        }
    }

    @Test
    fun errorResponsesCarryTypedCodes() {
        val response = AppMessages.errorResponse("abc", ErrorCode.PERMISSION_DENIED, "needs Control")
        val parsed = (AppMessages.parse(AppMessages.encodeResponse(response)) as AppMessage.Response).response
        assertFalse(parsed.ok)
        val error = assertNotNull(parsed.error)
        assertEquals("permission_denied", error.code)
        assertEquals(ErrorCode.PERMISSION_DENIED, error.errorCode)
        assertEquals("needs Control", error.message)
    }

    @Test
    fun unknownErrorCodesDecodeButDoNotMatchTheEnum() {
        val response = AppMessages.parse("""{"id":"x","ok":false,"error":{"code":"brand_new"}}""")
        val error = (response as AppMessage.Response).response.error!!
        assertEquals("brand_new", error.code)
        assertNull(error.errorCode)
    }

    // ----- events --------------------------------------------------------------------------------

    @Test
    fun everyEventRoundTrips() {
        val events = listOf(
            AppMessages.queuePosition(2),
            AppMessages.controlChanged(botId, "helper"),
            AppMessages.controlChanged(null),
            AppMessages.kicked("kick_all"),
            AppMessages.relayMoved("wss://new.example", "token", 1_700_000_000L),
            AppMessages.notification(NotificationItem("com.example", "Hi", "there", 5)),
        )
        for (event in events) {
            assertTrue(EventNames.ALL.contains(event.event), "undocumented event ${event.event}")
            val parsed = AppMessages.parse(AppMessages.encodeEvent(event)) as AppMessage.Event
            assertEquals(event, parsed.event)
        }

        val queue = AppMessages.parse(AppMessages.encodeEvent(AppMessages.queuePosition(3))) as AppMessage.Event
        assertEquals(3, PbJson.decodeFromJsonElement(QueuePositionData.serializer(), queue.event.data).position)
    }

    @Test
    fun applicationMessageParsingRejectsGarbage() {
        assertFailsWith<ProtocolException> { AppMessages.parse("[]") }
        assertFailsWith<ProtocolException> { AppMessages.parse("""{"id":"x"}""") }
        assertFailsWith<ProtocolException> { AppMessages.parse("") }
        assertFailsWith<ProtocolException> { AppMessages.parse("""{"cmd":"tap","args":{}}""") }
    }

    @Test
    fun chunkEventsUseTheDocumentedDataField() {
        val payload = dev.phonebridge.core.chunk.ChunkPayload("c1", 0, 1, "aGk", "ab")
        val event = AppEvent(
            EventNames.CHUNK,
            PbJson.encodeToJsonElement(dev.phonebridge.core.chunk.ChunkPayload.serializer(), payload).jsonObject,
        )
        val parsed = AppMessages.parse(AppMessages.encodeEvent(event)) as AppMessage.Event
        val decoded = PbJson.decodeFromJsonElement(dev.phonebridge.core.chunk.ChunkPayload.serializer(), parsed.event.data)
        assertEquals(payload, decoded)
    }
}
