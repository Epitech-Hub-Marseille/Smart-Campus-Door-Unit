package com.smartcampus.door.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.smartcampus.door.core.protocol.Emergency
import com.smartcampus.door.core.state.emergencyPresentation
import com.smartcampus.door.core.state.emergencySteps
import com.smartcampus.door.core.theme.ResolvedEmergencyTheme
import kotlinx.coroutines.delay

/** Each of the five lines is the one to do now for this long, in turn: five lines go round in four seconds. */
private const val STEP_HIGHLIGHT_MS = 800L
/** The triangles take turns, and the chevrons blink with the first one: one second a cycle, half of it each. */
private const val BLINK_HALF_MS = 500L
/** The hazard stripes slide by one pattern in this time, so they loop without a jump. */
private const val STRIPES_MS = 2_000

/** A door unit does not know the site's number; 112 works in every EU country. */
private const val EMERGENCY_NUMBER = "112"

/**
 * Full screen takeover, the alert of the Faculty screens at the size of a door: the system's name and this door's code, the alert in
 * large yellow between two warning triangles that blink in turns, what is happening, the order to follow between two blinking
 * chevrons, five lines in which the one to do now is reversed in turn, who to call and sliding hazard stripes. Nothing else, so it
 * reads at a glance. The alarm is separate and never depends on this screen.
 *
 * A theme may dress it through [look]: a dark background, its fonts, a plain band instead of stripes and the corners of the
 * boxes. The colour is the kind of alert's, the text is white and yellow, and anything unreadable is the default alert.
 */
@Composable
internal fun EmergencyScreen(doorCode: String?, emergency: Emergency, look: ResolvedEmergencyTheme = ResolvedEmergencyTheme.NONE, fonts: ThemeFonts = ThemeFonts()) {
    val background = look.background?.let(::Color) ?: alertBackground(emergency.kind)
    val presentation = remember(emergency.kind) { emergencyPresentation(emergency.kind) }
    val headingFont = (if (look.useFonts) fonts.heading else null) ?: AlertFont
    val bodyFont = (if (look.useFonts) fonts.body else null) ?: AlertFont
    val steps = remember(emergency.kind, emergency.testMode) { emergencySteps(emergency.kind, emergency.testMode) }

    // The blinks and the highlight are hard steps, as on the Faculty screens: on or off, never a fade.
    var secondHalf by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(BLINK_HALF_MS)
            secondHalf = !secondHalf
        }
    }
    var active by remember { mutableIntStateOf(0) }
    LaunchedEffect(steps.size) {
        while (true) {
            delay(STEP_HIGHLIGHT_MS)
            active = (active + 1) % steps.size
        }
    }
    val slide = rememberInfiniteTransition(label = "stripes").animateFloat(0f, 1f, infiniteRepeatable(tween(STRIPES_MS, easing = LinearEasing), RepeatMode.Restart), label = "slide")

    BoxWithConstraints(Modifier) {
        val s = Scale(minOf(maxWidth, maxHeight), LocalDensity.current)
        val landscape = maxWidth > maxHeight
        val hero = @Composable {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                AlertTriangle(s, Modifier.alpha(if (secondHalf) 0f else 1f))
                FitText(emergency.title.uppercase(), AlertYellow, s.sp(9.5f), FontWeight.Bold, Modifier.padding(horizontal = s.dp(2.5f)).weight(1f, fill = false), singleLine = true, fontFamily = headingFont)
                AlertTriangle(s, Modifier.alpha(if (secondHalf) 1f else 0f))
            }
        }
        val detail = @Composable {
            FitText(emergency.instructions.uppercase(), Color.White, s.sp(4.6f), FontWeight.Bold, Modifier.fillMaxWidth(), maxLines = 3, fontFamily = bodyFont)
        }
        val callout = @Composable { AlertCallout(presentation.headline, background, s, look.radiusUnits, headingFont, chevronsOn = !secondHalf) }
        val stepList = @Composable { modifier: Modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(s.dp(1.2f))) {
                steps.forEachIndexed { index, step ->
                    StepLine(index + 1, step.uppercase(), first = index == active, background = background, s = s, cornerUnits = look.radiusUnits, fontFamily = bodyFont)
                }
            }
        }
        AlertFrame(background, s, look.radiusUnits) {
            AlertHeader(background, if (emergency.testMode) "TEST - NO REAL EMERGENCY" else doorCode?.let { "[$it]" }.orEmpty(), s, look.radiusUnits)
            if (landscape) {
                // Side by side: stacked, a wide and short screen has no room left for the steps.
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(s.dp(3f))) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(s.dp(2.2f), Alignment.CenterVertically)) {
                        hero()
                        detail()
                        callout()
                    }
                    stepList(Modifier.weight(1.1f))
                }
            } else {
                hero()
                detail()
                callout()
                AlertRule(s)
                stepList(Modifier.weight(1f))
            }
            AlertBar(EMERGENCY_NUMBER, s, look.radiusUnits, bodyFont)
            HazardStripes(s, plain = look.frameBar, slide = { slide.value })
        }
    }
}

/** ">> 1. USE THE STAIRS. ..." The highlighted step is reversed (white): the one to do now, it cycles through the list. */
@Composable
private fun StepLine(number: Int, text: String, first: Boolean, background: Color, s: Scale, cornerUnits: Float?, fontFamily: FontFamily) {
    val shape = RoundedCornerShape(s.dp(cornerUnits ?: 0f))
    val line = Modifier.fillMaxWidth().clip(shape).let { if (first) it.background(Color.White) else it }.padding(horizontal = s.dp(1.6f), vertical = s.dp(0.6f))
    Row(line, horizontalArrangement = Arrangement.spacedBy(s.dp(1.6f))) {
        FitText(">>", if (first) background else AlertYellow, s.sp(4.2f), FontWeight.Bold, singleLine = true, fontFamily = fontFamily)
        FitText("$number. $text", if (first) background else Color.White, s.sp(4.2f), FontWeight.Bold, Modifier.weight(1f), maxLines = 3, align = TextAlign.Start, fontFamily = fontFamily)
    }
}
