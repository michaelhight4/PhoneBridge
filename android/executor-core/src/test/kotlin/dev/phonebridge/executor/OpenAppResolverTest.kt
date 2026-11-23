package dev.phonebridge.executor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAppResolverTest {

    private val apps = Fixtures.installedApps()

    @Test
    fun `exact package wins`() {
        assertEquals(OpenAppResolver.Result.Found("com.example.maps"), OpenAppResolver.resolve("com.example.maps", apps))
    }

    @Test
    fun `package match ignores case`() {
        assertEquals(OpenAppResolver.Result.Found("com.example.maps"), OpenAppResolver.resolve("COM.EXAMPLE.MAPS", apps))
    }

    @Test
    fun `exact label wins over prefix`() {
        assertEquals(OpenAppResolver.Result.Found("com.example.maps"), OpenAppResolver.resolve("Maps", apps))
        assertEquals(OpenAppResolver.Result.Found("com.example.maps"), OpenAppResolver.resolve("maps", apps))
    }

    @Test
    fun `unique label prefix resolves`() {
        assertEquals(OpenAppResolver.Result.Found("com.example.chat"), OpenAppResolver.resolve("Cha", apps))
        assertEquals(OpenAppResolver.Result.Found("com.example.settings"), OpenAppResolver.resolve("sett", apps))
    }

    @Test
    fun `unique prefix resolves even with several words`() {
        val result = OpenAppResolver.resolve("Maps P", apps)
        assertEquals(OpenAppResolver.Result.Found("com.example.maps.pro"), result)
    }

    @Test
    fun `ambiguous prefix lists the candidates`() {
        val result = OpenAppResolver.resolve("Ma", apps)
        assertEquals(
            OpenAppResolver.Result.Ambiguous(listOf("com.example.maps", "com.example.maps.pro")),
            result,
        )
    }

    @Test
    fun `substring match resolves when unique`() {
        assertEquals(OpenAppResolver.Result.Found("com.example.chat"), OpenAppResolver.resolve("hat", apps))
    }

    @Test
    fun `unknown label is not found`() {
        assertEquals(OpenAppResolver.Result.NotFound, OpenAppResolver.resolve("Spotify", apps))
        assertEquals(OpenAppResolver.Result.NotFound, OpenAppResolver.resolve("   ", apps))
    }

    @Test
    fun `shared labels with one package resolve`() {
        val duplicates = listOf(InstalledApp("com.a", "Camera"), InstalledApp("com.b", "Camera"))
        val result = OpenAppResolver.resolve("Camera", duplicates)
        assertTrue(result is OpenAppResolver.Result.Ambiguous)
        assertEquals(listOf("com.a", "com.b"), result.candidates)
    }
}
