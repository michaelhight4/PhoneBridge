package dev.phonebridge.core.model

import dev.phonebridge.core.BadArgsException
import dev.phonebridge.core.PermissionDeniedException
import dev.phonebridge.core.UnknownCommandException
import dev.phonebridge.core.util.PbJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandTableTest {
    /** The table from docs/PROTOCOL.md, copied here so a drift in the code fails this test. */
    private val specTable = mapOf(
        "status" to PermissionLevel.VIEW,
        "screenshot" to PermissionLevel.VIEW,
        "ui_tree" to PermissionLevel.VIEW,
        "notifications" to PermissionLevel.VIEW,
        "tap" to PermissionLevel.CONTROL,
        "long_press" to PermissionLevel.CONTROL,
        "swipe" to PermissionLevel.CONTROL,
        "type" to PermissionLevel.CONTROL,
        "key" to PermissionLevel.CONTROL,
        "open_app" to PermissionLevel.CONTROL,
        "request_control" to PermissionLevel.CONTROL,
        "release_control" to PermissionLevel.CONTROL,
        "install_apk" to PermissionLevel.FULL,
        "shell" to PermissionLevel.FULL,
        "files" to PermissionLevel.FULL,
        "settings" to PermissionLevel.FULL,
        "relay_move" to PermissionLevel.FULL,
    )

    @Test
    fun everyDocumentedCommandExistsWithTheDocumentedLevel() {
        assertEquals(specTable.keys, CommandType.entries.map { it.wire }.toSet())
        assertEquals(specTable.size, CommandType.entries.size)
        for ((cmd, level) in specTable) {
            assertEquals(level, Commands.requiredLevel(cmd), "wrong level for $cmd")
            assertTrue(Commands.isKnown(cmd))
        }
    }

    @Test
    fun permissionLevelsAreStrictlyOrdered() {
        assertTrue(PermissionLevel.VIEW.allows(PermissionLevel.VIEW))
        assertFalse(PermissionLevel.VIEW.allows(PermissionLevel.CONTROL))
        assertFalse(PermissionLevel.VIEW.allows(PermissionLevel.FULL))
        assertTrue(PermissionLevel.CONTROL.allows(PermissionLevel.VIEW))
        assertTrue(PermissionLevel.CONTROL.allows(PermissionLevel.CONTROL))
        assertFalse(PermissionLevel.CONTROL.allows(PermissionLevel.FULL))
        assertTrue(PermissionLevel.FULL.allows(PermissionLevel.CONTROL))
        assertTrue(PermissionLevel.VIEW.rank < PermissionLevel.CONTROL.rank)
        assertTrue(PermissionLevel.CONTROL.rank < PermissionLevel.FULL.rank)
        assertEquals(PermissionLevel.VIEW, PermissionLevel.min(PermissionLevel.FULL, PermissionLevel.VIEW))
        assertEquals("full", PermissionLevel.parse("FULL")!!.wire)
        assertEquals(PermissionLevel.CONTROL, PermissionLevel.parse("Control"))
        assertNull(PermissionLevel.parse("admin"))
    }

    @Test
    fun onlyControlAndFullCommandsNeedControl() {
        assertFalse(CommandType.STATUS.needsControl)
        assertFalse(CommandType.SCREENSHOT.needsControl)
        assertFalse(CommandType.NOTIFICATIONS.needsControl)
        assertTrue(CommandType.TAP.needsControl)
        assertTrue(CommandType.REQUEST_CONTROL.needsControl)
        assertTrue(CommandType.INSTALL_APK.needsControl)
        assertTrue(CommandType.RELAY_MOVE.needsControl)
    }

    @Test
    fun checkEnforcesTheTable() {
        Commands.check(PermissionLevel.VIEW, "status")
        Commands.check(PermissionLevel.CONTROL, "tap")
        Commands.check(PermissionLevel.FULL, "shell")
        Commands.check(PermissionLevel.FULL, "status")

        val viewTap = assertFailsWith<PermissionDeniedException> {
            Commands.check(PermissionLevel.VIEW, "tap")
        }
        assertEquals(PermissionLevel.CONTROL, viewTap.required)
        assertEquals(PermissionLevel.VIEW, viewTap.granted)

        assertFailsWith<PermissionDeniedException> { Commands.check(PermissionLevel.CONTROL, "shell") }
        assertFailsWith<PermissionDeniedException> { Commands.check(PermissionLevel.VIEW, "files") }
    }

    @Test
    fun unknownCommandsAreRejected() {
        assertNull(Commands.requiredLevel("delete_everything"))
        assertFalse(Commands.isKnown("delete_everything"))
        assertFailsWith<UnknownCommandException> {
            Commands.check(PermissionLevel.FULL, "delete_everything")
        }
    }

    @Test
    fun badArgumentsAreRejectedBeforeExecution() {
        fun args(json: String) = PbJson.parseToJsonElement(json).jsonObject

        // tap needs a point or a node, not nothing.
        assertFailsWith<BadArgsException> { Commands.decodeArgs(CommandType.TAP, args("{}")) }
        // tap must not mix a point and a node.
        assertFailsWith<BadArgsException> {
            Commands.decodeArgs(CommandType.TAP, args("""{"x":1,"y":2,"node_id":"n"}"""))
        }
        // swipe needs all four coordinates.
        assertFailsWith<BadArgsException> {
            Commands.decodeArgs(CommandType.SWIPE, args("""{"x1":1,"y1":2}"""))
        }
        // screenshot only speaks png/jpeg.
        assertFailsWith<BadArgsException> {
            Commands.decodeArgs(CommandType.SCREENSHOT, args("""{"format":"gif"}"""))
        }
        // files write needs data_b64.
        assertFailsWith<BadArgsException> {
            Commands.decodeArgs(CommandType.FILES, args("""{"op":"write","path":"/sdcard/x"}"""))
        }
        // Unknown key names fail instead of silently doing nothing.
        assertFailsWith<BadArgsException> {
            Commands.decodeArgs(CommandType.KEY, args("""{"key":"volume_up"}"""))
        }
        // Plain http is not allowed for APK downloads.
        assertFailsWith<BadArgsException> {
            Commands.decodeArgs(CommandType.INSTALL_APK, args("""{"url":"http://example.com/app.apk"}"""))
        }
        // open_app takes exactly one of package or label.
        assertFailsWith<BadArgsException> { Commands.decodeArgs(CommandType.OPEN_APP, args("{}")) }
        assertFailsWith<BadArgsException> {
            Commands.decodeArgs(CommandType.OPEN_APP, args("""{"package":"a","label":"b"}"""))
        }
    }

    @Test
    fun wellFormedArgumentsDecode() {
        assertEquals(
            TapArgs(x = 10, y = 20),
            Commands.decodeArgs(CommandType.TAP, PbJson.parseToJsonElement("""{"x":10,"y":20}""")),
        )
        assertEquals(
            LongPressArgs(nodeId = "0.1", ms = 500),
            Commands.decodeArgs(CommandType.LONG_PRESS, PbJson.parseToJsonElement("""{"node_id":"0.1","ms":500}""")),
        )
        assertFailsWith<BadArgsException> {
            Commands.decodeArgs(
                CommandType.LONG_PRESS,
                PbJson.parseToJsonElement("""{"x":1,"y":2,"node_id":"0.1"}"""),
            )
        }
        assertFailsWith<BadArgsException> {
            Commands.decodeArgs(CommandType.LONG_PRESS, PbJson.parseToJsonElement("{}"))
        }
        assertEquals(
            KeyArgs(KeyName.BACK),
            Commands.decodeArgs(CommandType.KEY, PbJson.parseToJsonElement("""{"key":"back"}""")),
        )
        assertEquals(
            FilesArgs(FileOp.LIST, "/sdcard"),
            Commands.decodeArgs(CommandType.FILES, PbJson.parseToJsonElement("""{"op":"list","path":"/sdcard"}""")),
        )
    }

    @Test
    fun argumentsOfTheWrongCommandAreRejected() {
        assertFailsWith<BadArgsException> {
            Commands.encodeArgs(CommandType.TAP, ShellArgs(command = "id"))
        }
    }

    @Test
    fun commandsAreAddressableByWireName() {
        assertEquals(CommandType.RELAY_MOVE, Commands.fromWire("relay_move"))
        assertEquals(CommandType.UI_TREE, Commands.fromWire("ui_tree"))
        assertNull(Commands.fromWire("UI_TREE"))
    }
}
