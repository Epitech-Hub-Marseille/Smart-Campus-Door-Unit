package com.smartcampus.door.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.style.TextAlign

private val NoticeBackground = Color(0xFF07080A)
private val NoticeAccent = Color(0xFF00A86B)
private val NoticeText = Color(0xFFF2F5F3)
private val NoticeMuted = Color(0xFFA3AFA9)

/**
 * Shown for a couple of minutes after an emergency ends with a message ("you may return to the building").
 * Without it, people would watch the alarm stop and the screen jump back to a room status, with no word
 * about whether it is safe.
 */
@Composable
fun EndNoticeScreen(doorCode: String?, message: String) {
    BoxWithConstraints(Modifier.fillMaxSize().background(NoticeBackground)) {
        val s = Scale(minOf(maxWidth, maxHeight), LocalDensity.current)
        Box(Modifier.fillMaxSize().padding(s.dp(8f)), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(s.dp(4f))) {
                doorCode?.let { FitText(it, NoticeMuted, s.sp(6f), FontWeight.Medium, Modifier.fillMaxWidth(), singleLine = true) }
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = NoticeAccent, modifier = Modifier.size(s.dp(20f)))
                FitText("Alert over", NoticeAccent, s.sp(12f), FontWeight.SemiBold, Modifier.fillMaxWidth(), maxLines = 2)
                FitText(message, NoticeText, s.sp(6.5f), FontWeight.Normal, Modifier.fillMaxWidth(), maxLines = 8, align = TextAlign.Center)
            }
        }
    }
}
