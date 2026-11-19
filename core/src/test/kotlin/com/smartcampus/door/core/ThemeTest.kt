package com.smartcampus.door.core

import com.smartcampus.door.core.protocol.ServerMessage
import com.smartcampus.door.core.protocol.Theme
import com.smartcampus.door.core.protocol.ThemeColors
import com.smartcampus.door.core.protocol.ThemeFont
import com.smartcampus.door.core.protocol.ThemeShape
import com.smartcampus.door.core.protocol.ThemeTokens
import com.smartcampus.door.core.protocol.ThemeTypography
import com.smartcampus.door.core.protocol.parseServerMessage
import com.smartcampus.door.core.protocol.protocolJson
import com.smartcampus.door.core.state.DoorState
import com.smartcampus.door.core.theme.ResolvedTheme
import com.smartcampus.door.core.theme.parseHexColor
import com.smartcampus.door.core.theme.resolve
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ThemeTest {
    private fun frame(payload: String) = """{"v":1,"id":"01HZ","type":"theme.set","ts":1,"seq":1,"payload":$payload}""".toByteArray()

    private val night = Theme(
        id = "t1",
        name = "Night",
        version = 3,
        tokens = ThemeTokens(
            mode = "dark",
            colors = ThemeColors(background = "#101828", primary = "#4F8CFF", text = "#E8F1EC"),
            typography = ThemeTypography(fontFamily = "serif", scale = 1.1),
            shape = ThemeShape(radius = 12),
        ),
    )

    @Test
    fun `reads a theme from the wire, ignoring the CSS and fields it does not know`() {
        val (message, _) = parseServerMessage(
            frame("""{"theme":{"id":"t1","name":"Night","version":3,"css":".card{gap:1rem}","tokens":{"mode":"dark","colors":{"primary":"#4f8cff"},"typography":{"scale":1.1},"shape":{"radius":12},"future":1}}}"""),
        )
        val set = assertIs<ServerMessage.ThemeSet>(message)
        assertEquals("t1", set.theme?.id)
        assertEquals(3, set.theme?.version)
        assertEquals("#4f8cff", set.theme?.tokens?.colors?.primary)
        assertNull(set.theme?.tokens?.colors?.background)
    }

    @Test
    fun `reads the font files of a theme, and a theme without any has none`() {
        val sha = "a".repeat(64)
        val (withFonts, _) = parseServerMessage(
            frame("""{"theme":{"id":"t1","name":"Brand","version":1,"tokens":{},"fonts":{"heading":{"assetId":"F1","sha256":"$sha","size":1200,"mime":"font/woff2"}}}}"""),
        )
        assertEquals(ThemeFont("F1", sha, 1200, "font/woff2"), assertIs<ServerMessage.ThemeSet>(withFonts).theme?.fonts?.get("heading"))
        val (plain, _) = parseServerMessage(frame("""{"theme":{"id":"t1","name":"Night","version":1,"tokens":{}}}"""))
        assertEquals(emptyMap(), assertIs<ServerMessage.ThemeSet>(plain).theme?.fonts)
    }

    @Test
    fun `fonts survive being saved and read back`() {
        val branded = night.copy(fonts = mapOf("body" to ThemeFont("F2", "b".repeat(64), 900, "font/woff2")))
        val state = DoorState(theme = branded)
        assertEquals(branded, protocolJson.decodeFromString<DoorState>(protocolJson.encodeToString(state)).theme)
    }

    @Test
    fun `null takes the unit back to its own look`() {
        val (message, _) = parseServerMessage(frame("""{"theme":null}"""))
        assertNull(assertIs<ServerMessage.ThemeSet>(message).theme)
    }

    @Test
    fun `a command sets the theme and null clears it`() {
        val state = DoorState().apply(ServerMessage.ThemeSet("a", night))
        assertEquals("t1", state.theme?.id)
        assertNull(state.apply(ServerMessage.ThemeSet("b", null)).theme)
    }

    @Test
    fun `the desired state is complete, so no theme in it means the theme was taken away`() {
        val withTheme = DoorState(theme = night)
        val desiredWithout = buildJsonObject { put("audioSettings", buildJsonObject { put("volume", 40) }) }
        assertNull(withTheme.withDesired(buildJsonObject { }, 0, 0).theme)
        assertNull(withTheme.withDesired(desiredWithout, 0, 0).theme)
        val desired = buildJsonObject { putJsonObject("theme") { put("id", "t2"); put("name", "Day"); put("version", 1) } }
        assertEquals("t2", withTheme.withDesired(desired, 0, 0).theme?.id)
        // A desired theme that cannot be read is no theme, not a crash.
        val broken = buildJsonObject { putJsonObject("theme") { put("id", "t2") } }
        assertNull(withTheme.withDesired(broken, 0, 0).theme)
    }

    @Test
    fun `survives being saved and read back`() {
        val state = DoorState(theme = night)
        assertEquals(night, protocolJson.decodeFromString<DoorState>(protocolJson.encodeToString(state)).theme)
    }

    @Test
    fun `resolves colours, mode, font, scale and radius, leaving out what it does not say`() {
        val r = night.resolve()
        assertEquals(0xFF101828L, r.background)
        assertEquals(0xFF4F8CFFL, r.primary)
        assertNull(r.surface)
        assertEquals("dark", r.mode)
        assertEquals("serif", r.fontFamily)
        assertEquals(1.1f, r.textScale, 0.0001f)
        assertEquals(12f / 10.8f, r.radiusUnits!!, 0.0001f)
    }

    @Test
    fun `no theme is the app's own look`() {
        assertEquals(ResolvedTheme.NONE, null.resolve())
        assertEquals(ResolvedTheme.NONE, Theme("x", "Empty", 1).resolve())
    }

    @Test
    fun `reads bad values defensively instead of drawing something unreadable`() {
        val bad = Theme(
            "x", "Bad", 1,
            ThemeTokens(
                mode = "sepia",
                colors = ThemeColors(background = "red", text = "#fff", primary = "#12345G"),
                typography = ThemeTypography(fontFamily = "Comic Sans", scale = 9.0),
                shape = ThemeShape(radius = 500),
            ),
        )
        val r = bad.resolve()
        assertNull(r.background)
        assertNull(r.text)
        assertNull(r.primary)
        assertNull(r.mode)
        assertNull(r.fontFamily)
        assertEquals(1.4f, r.textScale, 0.0001f)
        assertNull(r.radiusUnits)
        assertEquals(0.8f, Theme("x", "Small", 1, ThemeTokens(typography = ThemeTypography(scale = 0.1))).resolve().textScale, 0.0001f)
    }

    @Test
    fun `parses only six-digit hex colours`() {
        assertEquals(0xFF00A86BL, parseHexColor("#00a86b"))
        assertEquals(0xFFFFFFFFL, parseHexColor("#FFFFFF"))
        for (bad in listOf(null, "", "#fff", "00a86b", "#00a86bff", "rgb(0,0,0)")) assertNull(parseHexColor(bad), bad)
    }
}
