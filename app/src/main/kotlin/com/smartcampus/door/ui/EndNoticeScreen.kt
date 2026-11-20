package com.smartcampus.door.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import com.smartcampus.door.core.theme.ResolvedEmergencyTheme

private val NoticeBackground = Color(0xFF0A5A1F)

/**
 * Shown for a couple of minutes after an emergency ends with a message ("you may return to the building").
 * Without it, people would watch the alarm stop and the screen jump back to a room status, with no word
 * about whether it is safe. Same bulletin as the alert, on green.
 */
@Composable
internal fun EndNoticeScreen(doorCode: String?, message: String, look: ResolvedEmergencyTheme = ResolvedEmergencyTheme.NONE, fonts: ThemeFonts = ThemeFonts()) {
    val background = look.background?.let(::Color) ?: NoticeBackground
    val headingFont = (if (look.useFonts) fonts.heading else null) ?: AlertFont
    val bodyFont = (if (look.useFonts) fonts.body else null) ?: AlertFont
    BoxWithConstraints(Modifier) {
        val s = Scale(minOf(maxWidth, maxHeight), LocalDensity.current)
        AlertFrame(background, s, look.radiusUnits) {
            AlertHeader(background, doorCode?.let { "[$it]" }.orEmpty(), s, look.radiusUnits)
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AlertYellow, modifier = Modifier.size(s.dp(9f)))
                FitText("ALERT OVER", AlertYellow, s.sp(9.5f), FontWeight.Bold, Modifier.padding(start = s.dp(2.5f)), singleLine = true, fontFamily = headingFont)
            }
            AlertCallout("ALL CLEAR", background, s, look.radiusUnits, headingFont)
            FitText(message, Color.White, s.sp(5.4f), FontWeight.Bold, Modifier.fillMaxWidth().weight(1.4f), maxLines = 8, fontFamily = bodyFont)
        }
    }
}
