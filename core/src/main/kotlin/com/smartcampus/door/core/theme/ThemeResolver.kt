package com.smartcampus.door.core.theme

import com.smartcampus.door.core.protocol.Theme
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The fonts a theme may name, as the server defines them. The app maps each to a typeface it has. */
val THEME_FONTS = setOf("system", "sans", "serif", "mono", "condensed")

/** One screen pixel of the 1080p reference is this many units (1% of the short side), so a corner radius in pixels scales with the screen. */
private const val UNITS_PER_REFERENCE_PIXEL = 1f / 10.8f

private const val MIN_SCALE = 0.8f
private const val MAX_SCALE = 1.4f
private const val MAX_RADIUS = 32

/**
 * A theme in the terms the screen draws with: colours as ARGB integers (null keeps the app's own), whether it forces light
 * or dark, a font name, a text scale and a corner radius in the app's own units. The server checked all of it already;
 * this reads it defensively anyway, so a bad value on a wall (a hand-edited state file, a newer server) falls back to the
 * app's own look instead of crashing or drawing something unreadable.
 */
data class ResolvedTheme(
    val background: Long? = null,
    val surface: Long? = null,
    val text: Long? = null,
    val muted: Long? = null,
    val primary: Long? = null,
    val primaryText: Long? = null,
    val border: Long? = null,
    val success: Long? = null,
    val warning: Long? = null,
    val danger: Long? = null,
    /** "light", "dark", or null to follow the unit's display configuration. */
    val mode: String? = null,
    val fontFamily: String? = null,
    val textScale: Float = 1f,
    /** Corner radius in units of the app's scale, or null to keep the app's own corners. */
    val radiusUnits: Float? = null,
) {
    companion object {
        val NONE = ResolvedTheme()
    }
}

/** "#rrggbb" to 0xFFRRGGBB, or null when it is not exactly that. */
fun parseHexColor(hex: String?): Long? {
    if (hex == null || !Regex("^#[0-9a-fA-F]{6}$").matches(hex)) return null
    return 0xFF000000L or hex.substring(1).toLong(16)
}

fun Theme?.resolve(): ResolvedTheme {
    val tokens = this?.tokens ?: return ResolvedTheme.NONE
    val c = tokens.colors
    return ResolvedTheme(
        background = parseHexColor(c.background),
        surface = parseHexColor(c.surface),
        text = parseHexColor(c.text),
        muted = parseHexColor(c.muted),
        primary = parseHexColor(c.primary),
        primaryText = parseHexColor(c.primaryText),
        border = parseHexColor(c.border),
        success = parseHexColor(c.success),
        warning = parseHexColor(c.warning),
        danger = parseHexColor(c.danger),
        mode = tokens.mode?.takeIf { it == "light" || it == "dark" },
        fontFamily = tokens.typography.fontFamily?.takeIf { it in THEME_FONTS },
        textScale = (tokens.typography.scale?.toFloat() ?: 1f).coerceIn(MIN_SCALE, MAX_SCALE),
        radiusUnits = tokens.shape.radius?.takeIf { it in 0..MAX_RADIUS }?.let { it * UNITS_PER_REFERENCE_PIXEL },
    )
}

/** The least contrast white text needs on an alert's background (WCAG, large text). The server refuses a darker rule too. */
const val EMERGENCY_MIN_CONTRAST = 4.5

/** Contrast of white text on an opaque 0xFFRRGGBB colour, by WCAG's relative luminance. */
fun contrastOnWhite(argb: Long): Double {
    fun channel(shift: Int): Double {
        val value = ((argb shr shift) and 0xFF) / 255.0
        return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
    }
    return 1.05 / (0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0) + 0.05)
}

/**
 * What a theme changes on an alert, in the terms the screen draws with. The alert's colour, wording, alarm and white text are
 * never themed. [background] is null unless white text stays readable on it, so a bad value falls back to the default.
 */
data class ResolvedEmergencyTheme(
    val background: Long? = null,
    val useFonts: Boolean = false,
    /** True for a plain solid band in the colour of the alert along the top and bottom; false for the default look. */
    val frameBar: Boolean = false,
    val radiusUnits: Float? = null,
) {
    companion object {
        val NONE = ResolvedEmergencyTheme()
    }
}

/** Reads the alert part of a theme defensively: anything unreadable gives [ResolvedEmergencyTheme.NONE], the default alert. */
fun Theme?.resolveEmergency(): ResolvedEmergencyTheme {
    val raw = this?.emergency ?: return ResolvedEmergencyTheme.NONE
    return runCatching {
        val background = parseHexColor(raw["colors"]?.jsonObject?.get("background")?.jsonPrimitive?.contentOrNull)
        ResolvedEmergencyTheme(
            background = background?.takeIf { contrastOnWhite(it) >= EMERGENCY_MIN_CONTRAST },
            useFonts = raw["fonts"]?.jsonPrimitive?.booleanOrNull == true,
            frameBar = raw["frame"]?.jsonPrimitive?.contentOrNull == "bar",
            radiusUnits = raw["radius"]?.jsonPrimitive?.intOrNull?.takeIf { it in 0..MAX_RADIUS }?.let { it * UNITS_PER_REFERENCE_PIXEL },
        )
    }.getOrDefault(ResolvedEmergencyTheme.NONE)
}
