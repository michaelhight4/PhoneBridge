package dev.phonebridge.core

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionTest {
    @Test fun protocolVersionIsOne() = assertEquals(1, Version.PROTOCOL)
}
