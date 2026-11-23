package dev.phonebridge.e2e

import dev.phonebridge.core.identity.Identity
import dev.phonebridge.core.model.CommandResult
import dev.phonebridge.core.model.CommandType
import dev.phonebridge.core.model.Commands
import dev.phonebridge.core.model.KeyArgs
import dev.phonebridge.core.model.KeyName
import dev.phonebridge.core.model.NotificationsArgs
import dev.phonebridge.core.model.NotificationsResult
import dev.phonebridge.core.model.OpenAppArgs
import dev.phonebridge.core.model.PairingOffer
import dev.phonebridge.core.model.PeerRole
import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.model.RelayMoveArgs
import dev.phonebridge.core.model.RelayMoveResult
import dev.phonebridge.core.model.RequestControlArgs
import dev.phonebridge.core.model.ReleaseControlArgs
import dev.phonebridge.core.model.ScreenshotArgs
import dev.phonebridge.core.model.ScreenshotResult
import dev.phonebridge.core.model.StatusArgs
import dev.phonebridge.core.model.StatusResult
import dev.phonebridge.core.model.SwipeArgs
import dev.phonebridge.core.model.TapArgs
import dev.phonebridge.core.model.TypeArgs
import dev.phonebridge.core.model.UiTreeArgs
import dev.phonebridge.core.model.UiTreeResult
import dev.phonebridge.core.pairing.PairingLinks
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.relay.RelayHandle
import dev.phonebridge.ui.ApprovalOutcome
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The v1 acceptance run: a real relay, the real `:core` protocol/crypto, the real plain-Kotlin
 * executor and the real device-side session/approval controllers, with only the Android device
 * itself faked.
 *
 * `gradlew.bat :e2e:test` runs all of it.
 */
class EndToEndTest {

    private val closers = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        closers.asReversed().forEach { runCatching { it.close() } }
        closers.clear()
    }

    // ---------------------------------------------------------------- scenarios

    @Test
    fun `pairs by short code over a pinned TLS relay and runs every command`() {
        val relay = relay(tls = true)
        val phone = phone(relay.url, tlsPin = relay.fingerprint)
        val offer = phone.newOffer()
        assertEquals(relay.fingerprint, offer.relayPin, "the offer must carry the relay certificate pin")
        phone.publishOffer(offer)

        val alice = bot("Alice")
        alice.connect(relay.url, relay.fingerprint)
        // The relay mailbox must hand back the whole offer, pin included.
        val lookedUp = alice.lookupCode(offer.code)
        assertEquals(offer, lookedUp)

        approveAndOpen(phone, alice, lookedUp, requested = PermissionLevel.CONTROL)
        assertEquals(phone.identity.peerId, alice.deviceId)

        // View level: no control needed.
        val status = result<StatusResult>(alice.request(CommandType.STATUS, StatusArgs), CommandType.STATUS)
        assertEquals(FakePhoneDevice.DEVICE_NAME, status.device)
        assertEquals(88, status.battery)
        assertEquals(true, status.screenOn)
        assertNull(status.controller)
        assertTrue(status.queue.isEmpty())

        val screenshot = result<ScreenshotResult>(
            alice.request(CommandType.SCREENSHOT, ScreenshotArgs(format = "png")),
            CommandType.SCREENSHOT,
        )
        assertEquals("image/png", screenshot.mime)
        assertEquals(1080, screenshot.width)
        assertNotNull(screenshot.dataB64)
        val png = B64Url.decode(screenshot.dataB64!!)
        assertContentEquals(PNG_MAGIC, png.copyOfRange(0, PNG_MAGIC.size), "the reply must carry a real image")

        val tree = result<UiTreeResult>(alice.request(CommandType.UI_TREE, UiTreeArgs()), CommandType.UI_TREE)
        val nodes = flatten(tree.root)
        assertEquals("OK", nodes.first { it.id == "0.1" }.text)
        assertFalse(nodes.any { it.id == "0.2" }, "visible_only must drop the hidden node")

        val notifications = result<NotificationsResult>(
            alice.request(CommandType.NOTIFICATIONS, NotificationsArgs()),
            CommandType.NOTIFICATIONS,
        )
        assertEquals("com.example.chat", notifications.items.single().pkg)

        // Control level: the bot must hold control before it may touch the screen.
        val granted = result<dev.phonebridge.core.model.RequestControlResult>(
            alice.request(CommandType.REQUEST_CONTROL, RequestControlArgs),
            CommandType.REQUEST_CONTROL,
        )
        assertEquals(true, granted.granted)
        assertEquals("Alice is in control", phone.notificationText())

        assertTrue(alice.request(CommandType.TAP, TapArgs(x = 540, y = 1200)).ok)
        assertEquals(540 to 1200, phone.device.taps.last())

        // A tap by node id lands on the centre of the node the bot just saw.
        assertTrue(alice.request(CommandType.TAP, TapArgs(nodeId = "0.1")).ok)
        assertEquals(Screen.okButtonCenter, phone.device.taps.last())

        assertTrue(alice.request(CommandType.SWIPE, SwipeArgs(100, 1000, 900, 200, ms = 250)).ok)
        assertEquals(
            FakePhoneDevice.SwipeCall(100, 1000, 900, 200, 250),
            phone.device.swipes.last(),
        )

        assertTrue(alice.request(CommandType.TYPE, TypeArgs(text = "hello world")).ok)
        assertEquals("hello world" to null, phone.device.texts.last())

        // Non-ASCII text survives the JSON envelope, base64url framing and Noise unchanged.
        val unicode = "héllo ✓ 日本語 🚀"
        assertTrue(alice.request(CommandType.TYPE, TypeArgs(text = unicode, nodeId = "0.0")).ok)
        assertEquals(unicode to "0.0", phone.device.texts.last())

        assertTrue(alice.request(CommandType.KEY, KeyArgs(KeyName.BACK)).ok)
        assertEquals(dev.phonebridge.executor.KeyCommand.BACK, phone.device.keys.last())

        assertTrue(alice.request(CommandType.OPEN_APP, OpenAppArgs(packageName = "com.example.chat")).ok)
        assertEquals("com.example.chat", phone.device.launches.last())

        // A label is resolved against the installed packages, as the app does with PackageManager.
        assertTrue(alice.request(CommandType.OPEN_APP, OpenAppArgs(label = "Maps")).ok)
        assertEquals("com.example.maps", phone.device.launches.last())

        val jpeg = result<ScreenshotResult>(
            alice.request(CommandType.SCREENSHOT, ScreenshotArgs(format = "jpeg", quality = 70, maxWidth = 720)),
            CommandType.SCREENSHOT,
        )
        assertEquals("image/jpeg", jpeg.mime)
        assertEquals(FakePhoneDevice.ScreenshotCall(dev.phonebridge.executor.ScreenshotFormat.JPEG, 70, 720), phone.device.screenshotCalls.last())

        // Bad arguments and unknown commands are refused before the device is touched.
        val badTap = alice.requestRaw("tap")
        assertFalse(badTap.ok)
        assertEquals("bad_args", badTap.error?.code)
        val unknown = alice.requestRaw("launch_missiles")
        assertFalse(unknown.ok)
        assertEquals("unknown_cmd", unknown.error?.code)
        assertEquals(2, phone.device.taps.size, "no refused command may reach the device")

        // The activity log names every command and never copies typed text.
        val entries = phone.activityLog.entriesFor(alice.identity.peerId, limit = 100)
        assertTrue(entries.any { it.cmd == "status" })
        assertTrue(entries.any { it.cmd == "tap" })
        assertTrue(entries.none { entry -> entry.detail?.contains("hello world") == true })
        assertTrue(phone.liveBots().contains(alice.identity.peerId))
    }

    @Test
    fun `pairs by link and by pairing file`() {
        val relay = relay()
        val phone = phone(relay.url)

        val offer = phone.newOffer()
        val byLinkBot = bot("Linky")
        val link = PairingLinks.link(offer)
        assertTrue(link.startsWith("phonebridge://pair?d="))
        val fromLink = byLinkBot.offerFromLink(link)
        assertEquals(offer, fromLink)
        pairAndOpen(phone, byLinkBot, fromLink, requested = PermissionLevel.VIEW)
        assertTrue(byLinkBot.request(CommandType.STATUS, StatusArgs).ok)

        val fileOffer = phone.newOffer()
        val byFileBot = bot("Filey")
        val fileContents = PairingLinks.fileContents(fileOffer)
        assertTrue(fileContents.trimStart().startsWith("{"))
        val fromFile = byFileBot.offerFromFile(fileContents)
        assertEquals(fileOffer, fromFile)
        pairAndOpen(phone, byFileBot, fromFile, requested = PermissionLevel.CONTROL)
        assertTrue(byFileBot.request(CommandType.STATUS, StatusArgs).ok)

        assertEquals(2, phone.registry.list().size)
        assertEquals(setOf(byLinkBot.identity.peerId, byFileBot.identity.peerId), phone.liveBots().toSet())
    }

    @Test
    fun `a view level bot cannot run control commands`() {
        val relay = relay()
        val phone = phone(relay.url)
        val viewer = bot("Viewer")
        pairAndOpen(phone, viewer, phone.newOffer(), requested = PermissionLevel.CONTROL, granted = PermissionLevel.VIEW)

        // Lowering the level at the approval screen really is the granted level.
        assertEquals(PermissionLevel.VIEW, phone.registry.get(viewer.identity.peerId)!!.level)

        assertTrue(viewer.request(CommandType.UI_TREE, UiTreeArgs()).ok)

        val deniedTap = viewer.request(CommandType.TAP, TapArgs(x = 10, y = 20))
        assertFalse(deniedTap.ok)
        assertEquals("permission_denied", deniedTap.error?.code)
        assertEquals("needs Control", deniedTap.error?.message)

        val deniedShell = viewer.requestRaw("shell", kotlinx.serialization.json.buildJsonObject {
            put("command", kotlinx.serialization.json.JsonPrimitive("id"))
        })
        assertFalse(deniedShell.ok)
        assertEquals("permission_denied", deniedShell.error?.code)

        assertTrue(phone.device.taps.isEmpty(), "a refused tap must never reach the device")
        assertTrue(phone.device.keys.isEmpty())
    }

    @Test
    fun `two bots queue for control and the queued one acts after release`() {
        val relay = relay()
        val phone = phone(relay.url)
        val alice = bot("Alice")
        val bob = bot("Bob")
        pairAndOpen(phone, alice, phone.newOffer(), requested = PermissionLevel.CONTROL)
        pairAndOpen(phone, bob, phone.newOffer(), requested = PermissionLevel.CONTROL)

        val aliceGrant = result<dev.phonebridge.core.model.RequestControlResult>(
            alice.request(CommandType.REQUEST_CONTROL, RequestControlArgs),
            CommandType.REQUEST_CONTROL,
        )
        assertEquals(true, aliceGrant.granted)

        val bobGrant = result<dev.phonebridge.core.model.RequestControlResult>(
            bob.request(CommandType.REQUEST_CONTROL, RequestControlArgs),
            CommandType.REQUEST_CONTROL,
        )
        assertEquals(false, bobGrant.granted)
        assertEquals(1, bobGrant.position)

        // A queued bot is told its position and is `busy` when it tries to act (PROTOCOL.md 7).
        val positionEvent = bob.awaitEvent("queue_position")
        assertEquals(1, positionEvent.data["position"]?.jsonPrimitive?.content?.toInt())
        val refused = bob.request(CommandType.TAP, TapArgs(x = 1, y = 2))
        assertFalse(refused.ok)
        assertEquals("busy", refused.error?.code)
        assertTrue(refused.error?.message?.contains("position 1") == true)
        assertTrue(phone.device.taps.isEmpty())

        // A bot that never asked for control is told exactly that.
        val carol = bot("Carol")
        pairAndOpen(phone, carol, phone.newOffer(), requested = PermissionLevel.CONTROL)
        val neverAsked = carol.request(CommandType.TAP, TapArgs(x = 1, y = 2))
        assertFalse(neverAsked.ok)
        assertEquals("not_controller", neverAsked.error?.code)
        assertTrue(phone.device.taps.isEmpty())

        assertTrue(alice.request(CommandType.TAP, TapArgs(x = 5, y = 6)).ok)
        val status = result<StatusResult>(alice.request(CommandType.STATUS, StatusArgs), CommandType.STATUS)
        assertEquals(alice.identity.peerId, status.controller)
        assertEquals(listOf(bob.identity.peerId), status.queue)
        assertTrue(phone.notificationText().startsWith("Alice is in control"))

        assertTrue(alice.request(CommandType.RELEASE_CONTROL, ReleaseControlArgs).ok)
        val promoted = bob.awaitEventWhere("control_changed") { event ->
            event.data["controller"]?.jsonPrimitive?.content == bob.identity.peerId
        }
        assertEquals("Bob", promoted.data["name"]?.jsonPrimitive?.content)

        assertTrue(bob.request(CommandType.TAP, TapArgs(x = 7, y = 8)).ok)
        assertEquals(7 to 8, phone.device.taps.last())
        val bobStatus = result<StatusResult>(bob.request(CommandType.STATUS, StatusArgs), CommandType.STATUS)
        assertEquals(bob.identity.peerId, bobStatus.controller)
        assertTrue(bobStatus.queue.isEmpty())
    }

    @Test
    fun `kick everyone revokes every session and the paired keys survive`() {
        val relay = relay()
        val phone = phone(relay.url)
        val alice = bot("Alice")
        val bob = bot("Bob")
        pairAndOpen(phone, alice, phone.newOffer(), requested = PermissionLevel.CONTROL)
        pairAndOpen(phone, bob, phone.newOffer(), requested = PermissionLevel.CONTROL)
        assertTrue(alice.request(CommandType.REQUEST_CONTROL, RequestControlArgs).ok)

        val kicked = phone.kickEveryone()
        assertEquals(setOf(alice.identity.peerId, bob.identity.peerId), kicked.toSet())

        // Both bots are told, and the phone remembers no live session or controller.
        val aliceKicked = alice.awaitEvent("kicked")
        assertEquals("kicked by the device owner", aliceKicked.data["reason"]?.jsonPrimitive?.content)
        bob.awaitEvent("kicked")
        assertTrue(phone.liveBots().isEmpty())
        assertNull(phone.state.controllerId())
        assertEquals("No bot in control", phone.notificationText())

        // Pairings (keys) survive a kick: a fresh Noise session works without pairing again.
        alice.openSession()
        assertTrue(alice.request(CommandType.STATUS, StatusArgs).ok)
        assertTrue(phone.liveBots().contains(alice.identity.peerId))
        assertEquals(2, phone.registry.list().size)
        assertTrue(phone.activityLog.entriesFor(alice.identity.peerId).any { it.cmd == "kick" })
    }

    @Test
    fun `relay_move hands the heart to a second relay and the session keeps working`() {
        val moveSecret = B64Url.encode(ByteArray(32) { (it * 3 + 1).toByte() })
        val relayA = relay(moveSecret = moveSecret)
        val relayB = relay(moveSecret = moveSecret)
        val phone = phone(relayA.url)
        phone.relayMover = { url ->
            relayA.moveTo(url)
            url
        }

        val admin = bot("Admin")
        pairAndOpen(phone, admin, phone.newOffer(), requested = PermissionLevel.FULL)
        assertEquals(relayA.relayId, admin.relayId)
        assertEquals(relayA.relayId, phone.relayId)

        val moved = result<RelayMoveResult>(
            admin.request(CommandType.RELAY_MOVE, RelayMoveArgs(to = relayB.url)),
            CommandType.RELAY_MOVE,
        )
        assertEquals(relayB.url, moved.newUrl)

        E2e.waitUntil(what = "both peers to follow the heart to relay B") {
            admin.relayId == relayB.relayId && phone.relayId == relayB.relayId
        }
        E2e.waitUntil(what = "relay B to see both peers") {
            relayB.peers().toSet() == setOf(admin.identity.peerId, phone.identity.peerId)
        }
        assertTrue(relayA.peers().isEmpty(), "relay A must be empty after the hand-over")

        // The Noise session is peer-to-peer, so it survives the relay changing underneath it.
        assertTrue(admin.request(CommandType.REQUEST_CONTROL, RequestControlArgs).ok)
        assertTrue(admin.request(CommandType.TAP, TapArgs(x = 42, y = 43)).ok)
        assertEquals(42 to 43, phone.device.taps.last())
        assertEquals(false, result<StatusResult>(admin.request(CommandType.STATUS, StatusArgs), CommandType.STATUS).shizuku)
    }

    @Test
    fun `a full level bot without shizuku is told so`() {
        val relay = relay()
        val phone = phone(relay.url)
        val admin = bot("Admin")
        pairAndOpen(phone, admin, phone.newOffer(), requested = PermissionLevel.FULL)

        val shell = admin.requestRaw(
            "shell",
            kotlinx.serialization.json.buildJsonObject { put("command", kotlinx.serialization.json.JsonPrimitive("id")) },
        )
        assertFalse(shell.ok)
        assertEquals("shizuku_unavailable", shell.error?.code)
    }

    /**
     * Writes the real messages this harness produced into the conformance hook's sample directory
     * (`build/protocol-samples`), so `tests/conformance` validates genuine output against the
     * published JSON Schemas. This is the cross-lane check that catches protocol drift.
     */
    @Test
    fun `emits real protocol samples for the conformance suite`() {
        val relay = relay()
        val phone = phone(relay.url)
        val offer = phone.newOffer()
        val bot = bot("Sampler")

        bot.connect(offer.relay, offer.relayPin)
        bot.requestPairing(offer, PermissionLevel.CONTROL)
        phone.awaitApproval(bot.identity.peerId)
        val approval = phone.approve(bot.identity.peerId, PermissionLevel.CONTROL)
        assertTrue(approval is ApprovalOutcome.Approved, "approval failed: $approval")
        val pairResult = bot.awaitPairResult()
        assertTrue(pairResult.ok)
        bot.openSession()

        assertTrue(bot.request(CommandType.REQUEST_CONTROL, RequestControlArgs).ok)
        assertTrue(bot.request(CommandType.STATUS, StatusArgs).ok)
        bot.awaitEvent("control_changed")

        val directory = java.nio.file.Path.of("").toAbsolutePath()
            .resolve("../..").normalize().resolve("build/protocol-samples")
        java.nio.file.Files.createDirectories(directory)
        fun write(name: String, json: String) {
            java.nio.file.Files.writeString(directory.resolve(name), json)
        }

        write("pairing-offer.json", dev.phonebridge.core.util.PbJson.encodeToString(PairingOffer.serializer(), offer))
        write("pair-request.json", assertNotNull(bot.lastPairRequestJson))
        write("pair-result.json", dev.phonebridge.core.util.PbJson.encodeToString(
            dev.phonebridge.core.model.PairResult.serializer(),
            pairResult,
        ))
        write("request.json", assertNotNull(bot.lastRequestJson))
        write("response.json", assertNotNull(bot.lastResponseJson))
        write("event.json", assertNotNull(bot.lastEventJson))
        write(
            "envelope-route.json",
            dev.phonebridge.core.model.EnvelopeCodec.encode(
                dev.phonebridge.core.model.RelayEnvelope.route(
                    from = bot.identity.peerId,
                    to = phone.identity.peerId,
                    payload = byteArrayOf(0x02, 0x01, 0x02, 0x03),
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun relay(tls: Boolean = false, moveSecret: String? = null): RelayHandle =
        E2e.startRelay(tls = tls, moveSecret = moveSecret).also { closers += it }

    private fun phone(relayUrl: String, tlsPin: String? = null): FakePhone =
        FakePhone(relayUrl = relayUrl, tlsPin = tlsPin).also {
            it.start()
            closers += it
        }

    private fun bot(name: String): BotClient =
        BotClient(Identity.generate(PeerRole.BOT, name)).also { closers += it }

    /** Connects, seals the pair request, waits for the human to tap Allow, then opens the session. */
    private fun pairAndOpen(
        phone: FakePhone,
        bot: BotClient,
        offer: PairingOffer,
        requested: PermissionLevel,
        granted: PermissionLevel? = null,
    ) {
        bot.connect(offer.relay, offer.relayPin)
        approveAndOpen(phone, bot, offer, requested, granted)
    }

    private fun approveAndOpen(
        phone: FakePhone,
        bot: BotClient,
        offer: PairingOffer,
        requested: PermissionLevel,
        granted: PermissionLevel? = null,
    ) {
        bot.requestPairing(offer, requested)
        val view = phone.awaitApproval(bot.identity.peerId)
        assertEquals(bot.identity.fingerprint, view.fingerprint)
        assertEquals(requested, view.requestedLevel)
        val outcome = phone.approve(bot.identity.peerId, granted)
        assertTrue(outcome is ApprovalOutcome.Approved, "approval failed: $outcome")
        val result = bot.awaitPairResult()
        assertTrue(result.ok, "pair_result: $result")
        assertEquals(granted ?: requested, result.grantedLevel)
        assertEquals(phone.identity.peerId, result.deviceId)
        bot.openSession()
    }

    private inline fun <reified T : CommandResult> result(response: dev.phonebridge.core.model.AppResponse, type: CommandType): T {
        assertTrue(response.ok, "${type.wire} failed: ${response.error?.code} ${response.error?.message}")
        return Commands.decodeResult(type, response.result) as T
    }

    private fun flatten(node: dev.phonebridge.core.model.UiNode): List<dev.phonebridge.core.model.UiNode> =
        listOf(node) + node.children.flatMap { flatten(it) }

    private companion object {
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    }
}
