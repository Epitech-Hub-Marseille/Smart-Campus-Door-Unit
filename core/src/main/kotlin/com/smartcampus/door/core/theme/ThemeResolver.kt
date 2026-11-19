package com.smartcampus.door.core.theme

import com.smartcampus.door.core.protocol.Theme

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
