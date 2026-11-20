package com.smartcampus.door.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/*
 * The bulletin look shared by the emergency screen and the end-of-alert screen, the same as on the Faculty screens: bold
 * monospace, a white double frame, a white header line and yellow on the colour of the alert. Everything is sized in units of
 * the screen (see Scale), so it keeps its proportions from a handheld to a wall tablet.
 */

internal val AlertYellow = Color(0xFFFFFF55)
private val White = Color.White

/** The text on an alert is bold monospace, or the theme's fonts when it says so (see [ResolvedEmergencyTheme]). */
internal val AlertFont: FontFamily = FontFamily.Monospace

/** Dark background of each kind of alert: white and yellow text read on all of them. An unknown kind gets the fire colour. */
internal fun alertBackground(kind: String): Color = when (kind) {
    "biohazard" -> Color(0xFF1F5F1F)
    "nuclear" -> Color(0xFF5A4A00)
    "bomb" -> Color(0xFF8A0030)
    "seismic" -> Color(0xFF8A3A00)
    "power" -> Color(0xFF0A3D8A)
    else -> Color(0xFFA80000)
}

/** The white line around the content, and the content inside it. */
@Composable
internal fun AlertFrame(background: Color, s: Scale, cornerUnits: Float?, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(s.dp(cornerUnits ?: 0f))
    Box(Modifier.fillMaxSize().background(background).padding(s.dp(2.5f))) {
        Column(
            Modifier.fillMaxSize()
                .border(s.dp(0.45f), White, shape)
                .padding(horizontal = s.dp(2.4f), vertical = s.dp(2f)),
            verticalArrangement = Arrangement.spacedBy(s.dp(2.2f)),
            content = content,
        )
    }
}

/** The white line at the top: the system's name on the left and what the screen is on the right. */
@Composable
internal fun AlertHeader(background: Color, right: String, s: Scale, cornerUnits: Float?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(s.dp(cornerUnits ?: 0f))).background(White).padding(horizontal = s.dp(2.2f), vertical = s.dp(1.2f)),
        horizontalArrangement = Arrangement.spacedBy(s.dp(2f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FitText(">> SMART CAMPUS SECURITY SYSTEM [S.C.S.S]", background, s.sp(3.4f), FontWeight.Bold, Modifier.weight(1f), singleLine = true, align = TextAlign.Start, fontFamily = AlertFont)
        if (right.isNotBlank()) FitText(right, background, s.sp(3.4f), FontWeight.Bold, singleLine = true, align = TextAlign.End, fontFamily = AlertFont)
    }
}

/** The order in a white box: ">> EVACUATE NOW <<", the two chevrons blinking together ([chevronsOn] hides them with no change of layout). */
@Composable
internal fun AlertCallout(text: String, background: Color, s: Scale, cornerUnits: Float?, fontFamily: FontFamily?, chevronsOn: Boolean = true) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(s.dp(cornerUnits ?: 0f))).background(White).padding(horizontal = s.dp(4f), vertical = s.dp(1.4f)),
            horizontalArrangement = Arrangement.spacedBy(s.dp(2.2f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FitText(">>", background, s.sp(7f), FontWeight.Bold, Modifier.alpha(if (chevronsOn) 1f else 0f), singleLine = true, fontFamily = fontFamily)
            FitText(text, background, s.sp(7f), FontWeight.Bold, singleLine = true, fontFamily = fontFamily)
            FitText("<<", background, s.sp(7f), FontWeight.Bold, Modifier.alpha(if (chevronsOn) 1f else 0f), singleLine = true, fontFamily = fontFamily)
        }
    }
}

/** A dashed white line between the order and the steps. */
@Composable
internal fun AlertRule(s: Scale) {
    Canvas(Modifier.fillMaxWidth().height(s.dp(0.6f))) {
        drawLine(White, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = size.height / 3, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
    }
}

/**
 * Yellow and black hazard stripes, or with [plain] a solid yellow band (a theme's frame). [slide] is read while drawing, 0..1, and
 * moves the stripes by that share of one pattern period, so they slide without recomposing anything.
 */
@Composable
internal fun HazardStripes(s: Scale, plain: Boolean, slide: () -> Float = { 0f }) {
    Canvas(Modifier.fillMaxWidth().height(s.dp(if (plain) 2f else 4.5f))) {
        drawRect(AlertYellow)
        if (plain) return@Canvas
        val stripe = size.height
        val period = stripe * 2
        var x = -size.height - period + slide() * period
        while (x < size.width) {
            val path = Path().apply {
                moveTo(x, size.height)
                lineTo(x + size.height, 0f)
                lineTo(x + size.height + stripe, 0f)
                lineTo(x + stripe, size.height)
                close()
            }
            drawPath(path, Color.Black)
            x += period
        }
    }
}

/** The warning triangle: yellow with a black outline and a black exclamation mark. */
@Composable
internal fun AlertTriangle(s: Scale, modifier: Modifier = Modifier) {
    Canvas(modifier.size(s.dp(9.5f))) {
        val w = size.width
        val h = size.height
        val outline = Path().apply {
            moveTo(w * 0.5f, h * 0.09f)
            lineTo(w * 0.935f, h * 0.87f)
            lineTo(w * 0.065f, h * 0.87f)
            close()
        }
        drawPath(outline, AlertYellow)
        drawPath(outline, Color.Black, style = Stroke(width = w * 0.065f, join = StrokeJoin.Round))
        drawRect(Color.Black, Offset(w * 0.46f, h * 0.33f), Size(w * 0.08f, h * 0.30f))
        drawCircle(Color.Black, w * 0.055f, Offset(w * 0.5f, h * 0.74f))
    }
}

/** The yellow bar at the bottom: who to call. */
@Composable
internal fun AlertBar(number: String, s: Scale, cornerUnits: Float?, fontFamily: FontFamily) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(s.dp(cornerUnits ?: 0f))).background(AlertYellow).padding(horizontal = s.dp(2.4f), vertical = s.dp(1.2f)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FitText(">> EMERGENCY SERVICES", Color.Black, s.sp(3.6f), FontWeight.Bold, singleLine = true, align = TextAlign.Start, fontFamily = fontFamily)
        FitText(number, Color.Black, s.sp(5.2f), FontWeight.Bold, singleLine = true, align = TextAlign.End, fontFamily = fontFamily)
    }
}
