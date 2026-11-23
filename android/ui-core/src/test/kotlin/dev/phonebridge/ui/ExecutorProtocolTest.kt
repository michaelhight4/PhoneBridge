package dev.phonebridge.ui

import dev.phonebridge.core.model.AppMessages
import dev.phonebridge.core.model.TapArgs
import dev.phonebridge.core.model.TypeArgs
import dev.phonebridge.core.util.PbJson
import dev.phonebridge.executor.ErrorCode
import dev.phonebridge.executor.Response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutorProtocolTest {

    @Test
    fun mapsWireArgsToTheExecutor() {
        val wire = AppMessages.request("req-1", TapArgs(x = 540, y = 1200))
        val executor = ExecutorProtocol.toExecutor(wire)

        assertEquals("req-1", executor.id)
        assertEquals("tap", executor.cmd)
        assertEquals(540L, executor.args["x"])
        assertEquals(1200L, executor.args["y"])
    }

    @Test
    fun mapsASuccessfulResponseBackToJson() {
        val response = Response.success("req-1", linkedMapOf("root" to linkedMapOf("text" to "Hi"), "count" to 2))
        val wire = ExecutorProtocol.toAppResponse(response)
        assertTrue(wire.ok)
        val json = PbJson.encodeToString(dev.phonebridge.core.model.AppResponse.serializer(), wire)
        assertTrue(json.contains("\"count\":2"))
        assertTrue(json.contains("\"text\":\"Hi\""))
    }

    @Test
    fun mapsAnErrorResponseWithTheProtocolCode() {
        val wire = ExecutorProtocol.toAppResponse(
            Response.failure("req-2", ErrorCode.PERMISSION_DENIED, "needs Control"),
        )
        assertFalse(wire.ok)
        assertEquals("permission_denied", wire.error!!.code)
        assertEquals("needs Control", wire.error!!.message)
    }

    @Test
    fun theLogDetailNeverCopiesTypedTextOrSecrets() {
        val type = ExecutorProtocol.toExecutor(AppMessages.request("r", TypeArgs(text = "my password")))
        assertEquals("11 character(s)", ExecutorProtocol.detailFor(type))

        val tap = ExecutorProtocol.toExecutor(AppMessages.request("r", TapArgs(x = 5, y = 6)))
        assertEquals("(5,6)", ExecutorProtocol.detailFor(tap))
    }

    @Test
    fun onboardingTracksAllThreeChecks() {
        val empty = OnboardingStatus()
        assertFalse(Onboarding.isComplete(empty))
        assertEquals(OnboardingStep.ACCESSIBILITY, Onboarding.firstMissing(empty)!!.step)

        val ready = OnboardingStatus(
            accessibilityEnabled = true,
            notificationsGranted = true,
            batteryOptimisationExempt = true,
        )
        assertTrue(Onboarding.isComplete(ready))
        assertEquals(3, Onboarding.items(ready).count { it.done })
        assertEquals(null, Onboarding.firstMissing(ready))
    }
}
