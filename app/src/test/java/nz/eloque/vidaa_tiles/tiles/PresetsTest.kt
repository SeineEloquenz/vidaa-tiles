package nz.eloque.vidaa_tiles.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PresetsTest {
    @Test
    fun jellyfinUrl() {
        assertEquals("http://192.168.1.5:8096/web/index.html", Presets.jellyfinUrl("192.168.1.5"))
        assertEquals("https://videos.example.org/web/index.html", Presets.jellyfinUrl("https://videos.example.org/"))
        assertEquals("https://jf.example.org:8096/web/index.html", Presets.jellyfinUrl("jf.example.org"))
        assertEquals("http://10.0.0.2:8920/web", Presets.jellyfinUrl("http://10.0.0.2:8920/web"))
        assertThrows(IllegalArgumentException::class.java) { Presets.jellyfinUrl("localhost") }
    }

    @Test
    fun stremioUrl() {
        val base = "https://noobygains.github.io/stremio-vidaa-tv/?install_source=sidee"
        assertEquals(base, Presets.stremioUrl(""))
        assertEquals("$base&server=https%3A%2F%2Fstream.example.test%3A12470", Presets.stremioUrl("https://stream.example.test:12470"))
        assertThrows(IllegalArgumentException::class.java) { Presets.stremioUrl("http://localhost:11470") }
        assertThrows(IllegalArgumentException::class.java) { Presets.stremioUrl("192.168.1.20:11470") }
    }

    @Test
    fun customTile() {
        val spec = Presets.custom("  My   Dashboard ", "192.168.1.9:8123", "")
        assertEquals("My Dashboard", spec.name)
        assertEquals("my_my_dashboard", spec.appId)
        assertEquals("http://192.168.1.9:8123", spec.url)
        assertThrows(IllegalArgumentException::class.java) { Presets.custom("x", "https://a.example", "http://icon") }
    }

    @Test
    fun privateHosts() {
        assertEquals(true, Presets.isPrivateHost("172.20.0.1"))
        assertEquals(false, Presets.isPrivateHost("172.32.0.1"))
        assertEquals(true, Presets.isPrivateHost("tv.local"))
    }
}
