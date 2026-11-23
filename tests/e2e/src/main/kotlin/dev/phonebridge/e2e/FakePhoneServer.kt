package dev.phonebridge.e2e

import dev.phonebridge.core.model.PermissionLevel
import dev.phonebridge.core.pairing.PairingLinks
import dev.phonebridge.ui.ApprovalOutcome
import java.nio.file.Files

/**
 * A manual integration target: a real relay plus a fake phone, both on this machine.
 *
 *     gradlew.bat :e2e:runFakePhone
 *
 * It prints the relay URL, the certificate pin and a pairing code / link / `.pbpair` file, so any
 * external bot (the MCP server/CLI once it lands, a script, a bot framework) can pair and drive it.
 * Every pairing request is approved automatically at Control level, which is what makes it usable
 * without a tap; it is a development harness, not the app.
 *
 * Press Enter to stop.
 */
fun main() {
    val phoneDir = E2e.tempDir("pb-fake-phone")
    val relay = E2e.startRelay(tls = true, moveSecret = "fake-phone-move-secret")
    val phone = FakePhone(
        relayUrl = relay.url,
        tlsPin = relay.fingerprint,
        dataDir = phoneDir,
    ).start()

    phone.relayMover = { url -> relay.moveTo(url); url }

    val offer = phone.newOffer()
    phone.publishOffer(offer)
    val link = PairingLinks.link(offer)
    val pairingFile = phoneDir.resolve("pairing${PairingLinks.FILE_EXTENSION}")
    Files.writeString(pairingFile, PairingLinks.fileContents(offer))

    // Development harness: approve whatever asks, at Control, so an external bot needs no human tap.
    val approver = Thread({
        while (true) {
            for (view in phone.pendingApprovals()) {
                val outcome = phone.approve(view.botId, PermissionLevel.CONTROL)
                if (outcome is ApprovalOutcome.Approved) {
                    println("[fake phone] approved ${view.botName} (${view.fingerprint}) at Control")
                }
            }
            Thread.sleep(200)
        }
    }, "pb-fake-approver")
    approver.isDaemon = true
    approver.start()

    println()
    println("PhoneBridge fake phone (development harness)")
    println("  device          ${phone.identity.name} (${phone.identity.peerId})")
    println("  relay           ${relay.url}")
    println("  relay id        ${relay.relayId}")
    println("  cert pin        ${relay.fingerprint}")
    println("  short code      ${offer.code}")
    println("  pairing link    $link")
    println("  pairing file    $pairingFile")
    println("  bots file       ${phone.registry.path}")
    println()
    println("Pair with that code, link or file. Pairing requests are auto-approved at Control.")
    println("Press Enter to stop.")

    readLine()

    phone.close()
    relay.stop()
    println("[fake phone] stopped")
}
