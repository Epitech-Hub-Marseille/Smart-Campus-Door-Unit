package com.smartcampus.door.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartcampus.door.core.CallPhase
import com.smartcampus.door.core.CallSession
import com.smartcampus.door.core.protocol.CallPeer
import com.smartcampus.door.core.IntercomState
import kotlinx.coroutines.delay

/** The extras menu closes by itself, and so does an announcer panel nobody is using, so a terminal is never left to the next person. */
private const val IDLE_CLOSE_MS = 30_000L
private const val ANNOUNCER_IDLE_CLOSE_MS = 60_000L

/** The swipe starts within this distance (dp) of the right edge and goes at least [SWIPE_DP] towards the middle. */
private const val EDGE_DP = 40
private const val SWIPE_DP = 100

/**
 * What the door shows when nothing else is on top of it: nothing but a barely visible tick at the right edge. The extras menu opens
 * by putting a finger on the right edge and sliding it towards the middle, a gesture nobody makes by accident. Transparent
 * otherwise, so the door stays what it is.
 */
@Composable
fun ExtrasEntry(onOpen: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var fromEdge = false
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { start ->
                        fromEdge = start.x > size.width - EDGE_DP.dp.toPx()
                        dragged = 0f
                    },
                    onDragEnd = { if (fromEdge && dragged < -SWIPE_DP.dp.toPx()) onOpen() },
                    onDragCancel = { fromEdge = false },
                ) { _, amount -> dragged += amount }
            },
    ) {
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .size(width = 3.dp, height = 36.dp)
                .clip(RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
        )
    }
}

/**
 * The extra features: an intercom call to another door terminal, and a live announcement. The announcement needs a card first,
 * checked by the server (see `DoorController.intercom`): this screen only says "hold your card" while it waits.
 */
@Composable
fun ExtrasMenu(
    intercom: IntercomState,
    onIntercom: () -> Unit,
    onCallPeer: (CallPeer) -> Unit,
    onCloseList: () -> Unit,
    onAnnounce: () -> Unit,
    onCancelAnnounce: () -> Unit,
    onDismissNotice: () -> Unit,
    onClose: () -> Unit,
) {
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(touches) {
        delay(IDLE_CLOSE_MS)
        onClose()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        touches++
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when {
                intercom.awaitingCard -> {
                    Text("Announcement", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text("Hold your card against the reader to make an announcement.", fontSize = 18.sp)
                    intercom.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = onCancelAnnounce, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
                intercom.choosingPeer -> {
                    Text("Intercom", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    val peers = intercom.peers
                    when {
                        peers == null -> Text("Looking for terminals…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        peers.isEmpty() -> Text("There is no other terminal to call.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            peers.forEach { peer ->
                                OutlinedButton(onClick = { onCallPeer(peer) }, enabled = peer.online, modifier = Modifier.fillMaxWidth()) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                                        Box(Modifier.size(12.dp).clip(CircleShape).background(if (peer.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
                                        Text(if (peer.online) peer.name else "${peer.name} (offline)", fontSize = 18.sp)
                                    }
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = onCloseList, modifier = Modifier.fillMaxWidth()) { Text("Back") }
                }
                else -> {
                    Text("More", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    intercom.callNotice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    ExtraButton("Intercom", Icons.Filled.Call, onClick = { onDismissNotice(); onIntercom() })
                    ExtraButton("Make an announcement", Icons.Filled.Mic, onClick = onAnnounce)
                    OutlinedButton(onClick = { onDismissNotice(); onClose() }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                }
            }
        }
    }
}

/** A call in progress: ringing the other terminal, being rung, connecting, or talking. Takes the whole screen so nobody misses it. */
@Composable
fun CallScreen(call: CallSession, onNeedMicrophone: () -> Unit, onAnswer: () -> Unit, onDecline: () -> Unit, onHangUp: () -> Unit) {
    // Asked while it rings, so the answer is not held up by a dialog the first time.
    LaunchedEffect(call.phase) { if (call.phase == CallPhase.INCOMING) onNeedMicrophone() }
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(call.phase) {
        seconds = 0
        while (call.phase == CallPhase.ACTIVE) {
            delay(1_000)
            seconds++
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(call.peer, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            when (call.phase) {
                CallPhase.CALLING -> {
                    Text("Calling…", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onHangUp, modifier = Modifier.fillMaxWidth()) { Text("Cancel", fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                }
                CallPhase.INCOMING -> {
                    Text("Intercom call", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Your microphone opens only if you answer.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onAnswer, modifier = Modifier.fillMaxWidth()) { Text("Answer", fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                    OutlinedButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) { Text("Decline", fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                }
                CallPhase.CONNECTING -> Text("Connecting…", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CallPhase.ACTIVE -> {
                    Text("Intercom %d:%02d".format(seconds / 60, seconds % 60), fontSize = 22.sp)
                    Button(
                        onClick = onHangUp,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("Hang up", fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ExtraButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Icon(icon, contentDescription = null)
            Text(label, fontSize = 18.sp)
        }
    }
}

/**
 * Shown to a person whose card the server accepted for announcements. "Go on air" asks the server, and the microphone opens only
 * when it agrees. This terminal never plays the voice back (the server leaves it out), so there is no howl.
 */
@Composable
fun AnnouncerScreen(name: String, intercom: IntercomState, onGoOnAir: () -> Unit, onGoOffAir: () -> Unit, onClose: () -> Unit) {
    var touches by remember { mutableIntStateOf(0) }
    // Never closes under someone who is speaking.
    LaunchedEffect(touches, intercom.onAir) {
        if (intercom.onAir) return@LaunchedEffect
        delay(ANNOUNCER_IDLE_CLOSE_MS)
        onClose()
    }
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(intercom.onAir) {
        seconds = 0
        while (intercom.onAir) {
            delay(1_000)
            seconds++
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        touches++
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Announcement", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
            if (intercom.onAir) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                    Text("On air %d:%02d".format(seconds / 60, seconds % 60), fontSize = 22.sp)
                }
                Text("Speaking to ${intercom.screens} screen${if (intercom.screens == 1) "" else "s"}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = onGoOffAir,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Stop", fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            } else {
                Text("The screens play a chime, then your voice. Speak when it says On air.", fontSize = 16.sp)
                intercom.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = onGoOnAir, enabled = !intercom.starting, modifier = Modifier.fillMaxWidth()) {
                    Text(if (intercom.starting) "Connecting…" else "Go on air", fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp))
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}
