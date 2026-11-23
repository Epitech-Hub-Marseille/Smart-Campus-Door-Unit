package com.smartcampus.door.core

import com.smartcampus.door.core.audio.AssetSource
import com.smartcampus.door.core.audio.AlarmPlayer
import com.smartcampus.door.core.audio.AudioPlayer
import com.smartcampus.door.core.audio.CardTone
import com.smartcampus.door.core.audio.CardTones
import com.smartcampus.door.core.audio.NoTones
import com.smartcampus.door.core.audio.NoAlarm
import com.smartcampus.door.core.audio.LivePlayer
import com.smartcampus.door.core.audio.Microphone
import com.smartcampus.door.core.audio.NoLivePlayer
import com.smartcampus.door.core.audio.NoMicrophone
import com.smartcampus.door.core.protocol.LIVE_MAX_CHUNK_BYTES
import com.smartcampus.door.core.protocol.CallPeer
import com.smartcampus.door.core.protocol.CallStates
import com.smartcampus.door.core.protocol.callAnswerPayload
import com.smartcampus.door.core.protocol.callAudioPayload
import com.smartcampus.door.core.protocol.callHangupPayload
import com.smartcampus.door.core.protocol.callRequestPayload
import com.smartcampus.door.core.protocol.emptyPayload
import kotlinx.coroutines.delay
import com.smartcampus.door.core.protocol.liveChunkPayload
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import com.smartcampus.door.core.net.LinkEvent
import com.smartcampus.door.core.net.MessageSink
import com.smartcampus.door.core.protocol.CardOutcome
import com.smartcampus.door.core.protocol.CardMenu
import com.smartcampus.door.core.protocol.Emergency
import com.smartcampus.door.core.protocol.Theme
import com.smartcampus.door.core.protocol.ThemeFont
import com.smartcampus.door.core.protocol.ServerMessage
import com.smartcampus.door.core.protocol.ackPayload
import com.smartcampus.door.core.protocol.badgePayload
import com.smartcampus.door.core.protocol.maintenanceActionPayload
import com.smartcampus.door.core.protocol.nackPayload
import com.smartcampus.door.core.protocol.protocolJson
import com.smartcampus.door.core.protocol.statePayload
import com.smartcampus.door.core.state.DoorState
import com.smartcampus.door.core.state.contains
import com.smartcampus.door.core.state.isAsleep
import com.smartcampus.door.core.state.localTime
import com.smartcampus.door.core.storage.FileStorage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

/** The alarm loudness of an emergency, 0..1. A power cut is only a heads-up, so it is much softer than a danger alarm. */
fun alarmLevel(emergency: Emergency, inQuietHours: Boolean): Float = when {
    emergency.testMode -> if (inQuietHours) 0f else emergency.testVolume.coerceIn(0, 100) / 100f
    emergency.kind == "power" -> POWER_CUT_LEVEL
    else -> 1f
}

private const val POWER_CUT_LEVEL = 0.35f

/** Slices of voice waiting to be sent (a few seconds): beyond that the link is too slow and the oldest speech is not worth keeping. */
private const val OUTGOING_BACKLOG = 64

/** An incoming call rings this often. */
private const val RING_INTERVAL_MS = 1_800L

/** Recent command ids kept so a replayed command is acknowledged again but never run twice. */
private const val REMEMBERED_IDS = 200

/** The same card read twice within this time (a card resting on the antenna) is one tap. */
private const val TAP_DEBOUNCE_MS = 2_000L

/** An answer to a tap that comes later than this is stale: the person has walked away. */
private const val CARD_RESULT_MAX_AGE_MS = 10_000L

/** Taps still waiting for an answer. Bounded so a server that never answers cannot grow the map. */
private const val MAX_PENDING_TAPS = 16

/** Someone whose card the server accepted for the maintenance menu, and what the server lets them do. */
data class MaintenanceSession(val name: String, val openedAt: Long, val menu: CardMenu = CardMenu())

/**
 * The last card presented and what the server made of it, for the screen that confirms the read.
 * [outcome] is the server's, or one of the two the unit makes up itself.
 */
data class CardEvent(val outcome: String, val name: String?, val at: Long) {
    /** False while the server has not answered yet, or when it could not be asked. */
    val granted: Boolean get() = isAccessGranted(outcome)
}

/** Shown instead of a server outcome when the unit cannot reach the server at all. */
const val OUTCOME_OFFLINE = "offline"

/** The card was read and the server has not answered yet. */
const val OUTCOME_CHECKING = "checking"

/** Outcomes that mean the card was accepted for something: the menu, a greeting, or registration. */
fun isAccessGranted(outcome: String): Boolean =
    outcome == CardOutcome.MAINTENANCE || outcome == CardOutcome.RECOGNISED || outcome == CardOutcome.CAPTURED

/** What the server said about the last menu action; the menu shows it under the buttons. */
data class ActionOutcome(val action: String, val ok: Boolean, val error: String?)

/** Where a call to another terminal is: this one is ringing it, is being rung, has answered and is connecting, or is talking. */
enum class CallPhase { CALLING, INCOMING, CONNECTING, ACTIVE }

/** A call this terminal is part of. [peer] is the other terminal's name as a person knows it (its room). */
data class CallSession(val callId: String, val peer: String, val phase: CallPhase)

/**
 * The intercom side of the terminal. [awaitingCard]: "Announcement" was chosen and the person has not presented their card yet.
 * [announcer]: the person the server accepted, who may now go on the air. [onAir]: the microphone is open and the server is
 * passing the voice on to [screens] screens (never this one: it has the microphone).
 * [choosingPeer] with [peers]: the list of terminals to call is on screen (null peers while it loads). [call]: the call this terminal
 * is in. [callNotice]: why the last call did not happen, for the screen to say.
 */
data class IntercomState(
    val awaitingCard: Boolean = false,
    val announcer: String? = null,
    val starting: Boolean = false,
    val onAir: Boolean = false,
    val screens: Int = 0,
    val error: String? = null,
    val choosingPeer: Boolean = false,
    val peers: List<CallPeer>? = null,
    val call: CallSession? = null,
    val callNotice: String? = null,
)

/**
 * The brain of the door unit: turns what the server sends into the state the screen shows, keeps
 * that state on disk (offline mode), acknowledges every command once and reports back.
 */
class DoorController(
    private val storage: FileStorage,
    private val assets: AssetSource,
    private val player: AudioPlayer,
    private val scope: CoroutineScope,
    private val alarm: AlarmPlayer = NoAlarm,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val tones: CardTones = NoTones,
    private val live: LivePlayer = NoLivePlayer,
    private val callPlayer: LivePlayer = NoLivePlayer,
    private val microphone: Microphone = NoMicrophone,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _door = MutableStateFlow(loadState())
    private val _online = MutableStateFlow(false)
    private val seenIds: ArrayDeque<String> = ArrayDeque(loadSeenIds())
    private var session: MessageSink? = null
    private var offlineSince: Long? = clock()
    private val _maintenance = MutableStateFlow<MaintenanceSession?>(null)
    private val _lastCard = MutableStateFlow<CardEvent?>(null)
    private val _actionOutcome = MutableStateFlow<ActionOutcome?>(null)
    private val pendingTaps = LinkedHashMap<String, Long>()
    private val pendingActions = LinkedHashMap<String, String>()
    private var lastTap: Pair<String, Long>? = null
    private val _intercom = MutableStateFlow(IntercomState())
    /** The id of this terminal's own `live.start` while it is waiting for an answer or on the air; also the id of its stream. */
    private var announceId: String? = null
    private var outgoing: Channel<ByteArray>? = null
    private var sender: Job? = null
    private var directoryId: String? = null
    private var ringJob: Job? = null
    private var hearing = false
    private val _fonts = MutableStateFlow<Map<String, File>>(emptyMap())
    private var fontsRequested: Map<String, ThemeFont> = emptyMap()

    init {
        // A restart in the middle of an emergency must not silence it: it ends only when the server says so.
        _door.value.emergency?.let(::soundAlarm)
        // After a restart the files are already on disk, so the theme's fonts come back without a network.
        loadFonts(_door.value.theme)
    }

    val door: StateFlow<DoorState> = _door.asStateFlow()
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /** Verified font files of the current theme, by slot. A slot appears once its file is downloaded and its hash matched. */
    val fonts: StateFlow<Map<String, File>> = _fonts.asStateFlow()

    val intercom: StateFlow<IntercomState> = _intercom.asStateFlow()

    /** Non-null while the maintenance menu is open. */
    val maintenance: StateFlow<MaintenanceSession?> = _maintenance.asStateFlow()
    val lastCard: StateFlow<CardEvent?> = _lastCard.asStateFlow()
    val actionOutcome: StateFlow<ActionOutcome?> = _actionOutcome.asStateFlow()

    suspend fun onEvent(event: LinkEvent) {
        when (event) {
            is LinkEvent.Connected -> {
                session = event.session
                offlineSince = null
                _online.value = true
            }
            is LinkEvent.Disconnected -> {
                session = null
                offlineSince = offlineSince ?: clock()
                _online.value = false
                // The menu was opened on the server's word: without the link it is no longer vouched for.
                _maintenance.value = null
                pendingTaps.clear()
                pendingActions.clear()
                // A voice that was being passed on, or a microphone that was open, ends with the link.
                endIntercom()
                endCall(null)
                _intercom.update { it.copy(choosingPeer = false, peers = null) }
                stopHearing()
            }
            is LinkEvent.Message -> handle(event.message)
        }
    }

    /**
     * A card was read by the NFC antenna. The unit never decides by itself who may open the menu: it
     * forwards the serial number and waits for the server's `card.result`. Offline, the answer is
     * simply "no", and the unit says so.
     */
    suspend fun onCardRead(cardUid: String) {
        val now = clock()
        val previous = lastTap
        lastTap = cardUid to now
        if (previous != null && previous.first == cardUid && now - previous.second < TAP_DEBOUNCE_MS) return

        playTone(CardTone.READ)
        val sink = session
        if (sink == null) {
            _lastCard.value = CardEvent(OUTCOME_OFFLINE, null, now)
            playTone(CardTone.DENIED)
            return
        }
        _lastCard.value = CardEvent(OUTCOME_CHECKING, null, now)
        val id = newUlid(now)
        pendingTaps[id] = now
        while (pendingTaps.size > MAX_PENDING_TAPS) pendingTaps.remove(pendingTaps.keys.first())
        sink.send("event.badge", badgePayload(cardUid, now), id)
    }

    fun closeMaintenance() {
        _maintenance.value = null
        _actionOutcome.value = null
        pendingActions.clear()
    }

    /**
     * Asks the server to run a menu action. The server checks it against the card that opened the menu,
     * so a button the person may not press is refused there even if this screen showed it.
     */
    suspend fun maintenanceAction(action: String, status: String? = null) {
        val sink = session ?: return
        val id = newUlid(clock())
        pendingActions[id] = action
        while (pendingActions.size > MAX_PENDING_TAPS) pendingActions.remove(pendingActions.keys.first())
        _actionOutcome.value = null
        sink.send("maintenance.action", maintenanceActionPayload(action, status), id)
    }

    private fun handleMaintenanceResult(result: ServerMessage.MaintenanceResult) {
        val action = pendingActions.remove(result.ref) ?: return
        _actionOutcome.value = ActionOutcome(action, result.ok, result.error)
        playTone(if (result.ok) CardTone.GRANTED else CardTone.DENIED)
    }

    /** Silent during an emergency (the alarm owns the speaker) and in quiet hours, and follows the volume setting. */
    private fun playTone(tone: CardTone) {
        val state = _door.value
        if (state.emergency != null || state.audio.quietHours.contains(state.localTime(clock(), zone))) return
        val level = state.audio.volume.coerceIn(0, 100) / 100f
        if (level > 0f) tones.play(tone, level)
    }

    private fun handleCardResult(result: ServerMessage.CardResult) {
        val now = clock()
        // Only an answer to a tap we made, and recent: anything else is replayed, forged or late.
        val sentAt = pendingTaps.remove(result.ref) ?: return
        if (now - sentAt > CARD_RESULT_MAX_AGE_MS) return

        val event = CardEvent(result.outcome, result.name, now)
        _lastCard.value = event
        playTone(if (event.granted) CardTone.GRANTED else CardTone.DENIED)
        // "Announcement" was chosen first: the card is for that, not for the maintenance menu.
        if (_intercom.value.awaitingCard && _door.value.emergency == null) {
            if (result.outcome == CardOutcome.MAINTENANCE && result.menu?.canAnnounce == true) {
                _intercom.update { it.copy(awaitingCard = false, announcer = result.name.orEmpty(), error = null) }
            } else {
                _intercom.update { it.copy(error = "This card cannot make announcements") }
            }
            return
        }
        // An emergency owns the screen: nobody should be able to cover it with a menu.
        val allowed = result.maintenance && result.outcome == CardOutcome.MAINTENANCE && _door.value.emergency == null
        if (allowed) _maintenance.value = MaintenanceSession(result.name.orEmpty(), now, result.menu ?: CardMenu())
    }

    private suspend fun handle(message: ServerMessage) {
        if (message is ServerMessage.CardResult) {
            handleCardResult(message)
            return
        }
        if (message is ServerMessage.MaintenanceResult) {
            handleMaintenanceResult(message)
            return
        }
        when (message) {
            is ServerMessage.LiveStart -> return startHearing()
            is ServerMessage.LiveChunk -> return if (hearing) live.write(message.pcm) else Unit
            is ServerMessage.LiveEnd -> return if (message.streamId == announceId) onServerEndedAnnouncement() else stopHearing()
            is ServerMessage.IntercomResult -> return handleIntercomResult(message)
            is ServerMessage.CallDirectory -> return handleDirectory(message)
            is ServerMessage.CallIncoming -> return handleIncoming(message)
            is ServerMessage.CallState -> return handleCallState(message)
            is ServerMessage.CallAudio -> return if (message.callId == _intercom.value.call?.callId) callPlayer.write(message.pcm) else Unit
            else -> Unit
        }
        if (message is ServerMessage.SessionReady) {
            update(_door.value.withDesired(message.desired, message.serverTime, clock()))
            report()
            return
        }
        if (message.id in seenIds) {
            acknowledge(message.id) // the server did not see our first ack: repeat it, do not run the command again
            return
        }
        if (message is ServerMessage.AudioPlayRequest) {
            handleAudio(message)
            return
        }
        update(_door.value.apply(message, clock()))
        remember(message.id)
        acknowledge(message.id)
        report()
    }

    // ---- intercom ----

    /** The person chose "Announcement": the next card, if it may announce, opens the microphone panel instead of the menu. */
    fun beginAnnouncement() {
        if (_intercom.value.call != null) return
        _intercom.update { it.copy(awaitingCard = true, announcer = null, error = null) }
    }

    /** Leaves the announcement flow, closing the microphone if it was open. */
    fun cancelAnnouncement() {
        endAnnouncementStream(tellServer = true)
        endIntercom(reset = true)
    }

    /** Asks the server to put this person on the air. The microphone opens when the server agrees. */
    suspend fun goOnAir() {
        val state = _intercom.value
        val sink = session
        if (state.announcer == null || state.onAir || state.starting) return
        if (state.call != null) {
            _intercom.update { it.copy(error = "Finish the call first") }
            return
        }
        if (sink == null) {
            _intercom.update { it.copy(error = "Not connected to the server") }
            return
        }
        val id = newUlid(clock())
        announceId = id
        _intercom.update { it.copy(starting = true, error = null) }
        sink.send("live.start", emptyPayload(), id)
    }

    /** The person is done speaking. */
    fun goOffAir() = endAnnouncementStream(tellServer = true)

    // ---- calls between terminals ----

    /** Shows the terminals that can be called; asks the server for the list. */
    suspend fun openCallList() {
        val sink = session
        if (sink == null) {
            _intercom.update { it.copy(callNotice = "Not connected to the server") }
            return
        }
        val id = newUlid(clock())
        directoryId = id
        _intercom.update { it.copy(choosingPeer = true, peers = null, callNotice = null) }
        sink.send("call.list", emptyPayload(), id)
    }

    fun closeCallList() {
        directoryId = null
        _intercom.update { it.copy(choosingPeer = false, peers = null) }
    }

    /** Rings [peer]. Its microphone stays closed until it accepts. */
    suspend fun callPeer(peer: CallPeer) {
        val sink = session ?: return
        val state = _intercom.value
        if (state.call != null || state.announcer != null || state.awaitingCard) return
        val id = newUlid(clock())
        _intercom.update { it.copy(choosingPeer = false, peers = null, call = CallSession(id, peer.name, CallPhase.CALLING), callNotice = null) }
        sink.send("call.request", callRequestPayload(peer.id), id)
    }

    /** The person at this terminal answers or declines the incoming call. */
    suspend fun answerCall(accept: Boolean) {
        val call = _intercom.value.call ?: return
        if (call.phase != CallPhase.INCOMING) return
        stopRing()
        if (accept) _intercom.update { it.copy(call = call.copy(phase = CallPhase.CONNECTING)) } else _intercom.update { it.copy(call = null) }
        session?.send("call.answer", callAnswerPayload(call.callId, accept))
    }

    /** Ends the call from this side (also cancels one that is still ringing). */
    fun hangUp() {
        val call = _intercom.value.call ?: return
        endCall(null)
        scope.launch { runCatching { session?.send("call.hangup", callHangupPayload(call.callId)) } }
    }

    fun dismissNotice() = _intercom.update { it.copy(callNotice = null) }

    private fun handleDirectory(result: ServerMessage.CallDirectory) {
        if (result.ref != directoryId) return
        _intercom.update { if (it.choosingPeer) it.copy(peers = result.peers) else it }
    }

    private fun handleIncoming(incoming: ServerMessage.CallIncoming) {
        val state = _intercom.value
        val busy = state.call != null || state.announcer != null || state.awaitingCard || state.starting || state.onAir || _door.value.emergency != null
        if (busy) {
            // Not a person's decision: the terminal is in the middle of something else, so the caller is told it declined.
            scope.launch { session?.send("call.answer", callAnswerPayload(incoming.callId, false)) }
            return
        }
        _maintenance.value = null
        directoryId = null
        _intercom.update { it.copy(choosingPeer = false, peers = null, call = CallSession(incoming.callId, incoming.from, CallPhase.INCOMING), callNotice = null) }
        startRing()
    }

    private fun handleCallState(change: ServerMessage.CallState) {
        val call = _intercom.value.call ?: return
        if (change.callId != call.callId) return
        val peer = change.peer ?: call.peer
        when (change.state) {
            CallStates.RINGING -> _intercom.update { it.copy(call = call.copy(peer = peer)) }
            CallStates.ACTIVE -> {
                stopRing()
                val opened = startMicrophone { pcm -> session?.send("call.audio", callAudioPayload(call.callId, pcm)) }
                if (!opened) {
                    hangUp()
                    _intercom.update { it.copy(callNotice = "The microphone cannot be opened") }
                    return
                }
                callPlayer.start()
                _intercom.update { it.copy(call = call.copy(peer = peer, phase = CallPhase.ACTIVE)) }
            }
            "declined" -> endCall("$peer declined the call")
            "busy" -> endCall("$peer is busy")
            "unavailable" -> endCall("$peer cannot be reached")
            "timeout" -> endCall(if (call.phase == CallPhase.INCOMING) null else "$peer did not answer")
            else -> endCall(null)
        }
    }

    /** Leaves the call on this side: ringing stops, the microphone closes and the speaker is released. [notice] says why, if it needs saying. */
    private fun endCall(notice: String?) {
        val call = _intercom.value.call ?: return
        stopRing()
        if (call.phase == CallPhase.ACTIVE) {
            stopMicrophone()
            callPlayer.stop()
        }
        _intercom.update { it.copy(call = null, callNotice = notice) }
    }

    private fun startRing() {
        ringJob?.cancel()
        ringJob = scope.launch {
            while (true) {
                playTone(CardTone.GRANTED)
                delay(RING_INTERVAL_MS)
            }
        }
    }

    private fun stopRing() {
        ringJob?.cancel()
        ringJob = null
    }

    /**
     * Opens the microphone and sends what it hears through [send], in order. Returns false when the microphone cannot be opened.
     * The microphone's own thread never waits: a link too slow for the voice drops it rather than growing without end.
     */
    private fun startMicrophone(send: suspend (ByteArray) -> Unit): Boolean {
        val channel = Channel<ByteArray>(OUTGOING_BACKLOG)
        val opened = microphone.start { chunk ->
            for (at in chunk.indices step LIVE_MAX_CHUNK_BYTES) channel.trySend(chunk.copyOfRange(at, minOf(at + LIVE_MAX_CHUNK_BYTES, chunk.size)))
        }
        if (!opened) {
            channel.close()
            return false
        }
        outgoing = channel
        sender = scope.launch {
            // One sender, so the slices leave in the order they were spoken (each message's counter follows the send order).
            try {
                for (pcm in channel) send(pcm)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The link broke: the disconnect handler ends the announcement or the call.
            }
        }
        return true
    }

    /** Closes the microphone. The returned job completes once everything already spoken has been sent. */
    private fun stopMicrophone(): Job? {
        microphone.stop()
        outgoing?.close()
        outgoing = null
        return sender.also { sender = null }
    }

    private fun handleIntercomResult(result: ServerMessage.IntercomResult) {
        if (result.ref != announceId) return
        if (!result.ok) {
            announceId = null
            _intercom.update { it.copy(starting = false, error = result.error ?: "The announcement was refused") }
            playTone(CardTone.DENIED)
            return
        }
        if (!startMicrophone { pcm -> session?.send("live.chunk", liveChunkPayload(pcm)) }) {
            announceId = null
            _intercom.update { it.copy(starting = false, error = "The microphone cannot be opened") }
            // The server was told it was on the air: tell it it is not.
            scope.launch { session?.send("live.end", emptyPayload()) }
            return
        }
        _intercom.update { it.copy(starting = false, onAir = true, screens = result.deviceCount ?: 0) }
        playTone(CardTone.GRANTED)
    }

    /** The server ended our stream (time limit, or the link was too quiet for too long). */
    private fun onServerEndedAnnouncement() = endAnnouncementStream(tellServer = false)

    private fun endAnnouncementStream(tellServer: Boolean) {
        // Only the announcement's own microphone: the same one serves a call, which this must not cut.
        if (announceId == null) {
            _intercom.update { it.copy(starting = false, onAir = false, screens = 0) }
            return
        }
        val wasOpen = outgoing != null
        val job = if (wasOpen) stopMicrophone() else null
        announceId = null
        _intercom.update { it.copy(starting = false, onAir = false, screens = 0) }
        // The slices still queued go out first, then the end: closing the channel lets the sender drain it.
        if (tellServer && wasOpen) scope.launch {
            job?.join()
            runCatching { session?.send("live.end", emptyPayload()) }
        }
    }

    /** Ends the announcement and, with [reset], the whole flow (card, panel, error). Safe to call when nothing is going on. */
    private fun endIntercom(reset: Boolean = false) {
        endAnnouncementStream(tellServer = false)
        if (reset || _intercom.value.awaitingCard || _intercom.value.announcer != null) {
            _intercom.update { IntercomState(choosingPeer = it.choosingPeer, peers = it.peers, call = it.call, callNotice = it.callNotice) }
        }
    }

    private fun startHearing() {
        if (announceId != null) return // never play back what this terminal is saying: it would feed on itself
        hearing = true
        live.start()
        alarm.duck(true)
    }

    private fun stopHearing() {
        if (!hearing) return
        hearing = false
        live.stop()
        alarm.duck(false)
    }

    private suspend fun handleAudio(message: ServerMessage.AudioPlayRequest) {
        val request = message.value
        val state = _door.value
        // An urgent broadcast (an emergency read-out) must be heard whatever the settings say.
        if (!request.urgent && state.audio.quietHours.contains(state.localTime(clock(), zone))) {
            // Acknowledged on purpose: the server must not replay a broadcast we deliberately stayed silent for.
            remember(message.id)
            acknowledge(message.id)
            return
        }
        val file = try {
            assets.fetch(request.assetId, request.sha256, request.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            remember(message.id)
            session?.send("nack", nackPayload(message.id, "download failed: ${e.message}"))
            return
        }
        remember(message.id)
        acknowledge(message.id)
        val volume = if (request.urgent) 100 else state.audio.volume
        scope.launch {
            // The read-out of an emergency is heard over the alarm, which steps back for as long as it speaks.
            val overAlarm = request.urgent || request.emergencyId != null
            if (overAlarm) alarm.duck(true)
            try {
                runCatching { player.play(file, volume) }
            } finally {
                if (overAlarm) alarm.duck(false)
            }
        }
    }

    /**
     * Fetches the font files a theme names. The theme dresses the screen at once and each font joins when its file is
     * verified, so a slow download never delays the colours. A font that cannot be fetched or is too big is left out,
     * and the slot keeps the font family of the tokens: a theme must never stop a door from showing its room.
     */
    private fun loadFonts(theme: Theme?) {
        val wanted = theme?.fonts.orEmpty().filter { (slot, font) -> slot in FONT_SLOTS && font.size in 1L..MAX_FONT_BYTES && canLoad(font) }
        if (wanted == fontsRequested) return
        fontsRequested = wanted
        _fonts.value = emptyMap()
        for ((slot, font) in wanted) {
            scope.launch {
                val file = try {
                    assets.fetch(font.assetId, font.sha256, font.size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@launch
                }
                // The theme may have changed while the file was coming.
                if (fontsRequested[slot] == font) _fonts.update { it + (slot to file) }
            }
        }
    }

    /** Android reads TrueType and OpenType, but not WOFF or WOFF2 (those are for web pages): such a font is not downloaded. */
    private fun canLoad(font: ThemeFont) = !font.mime.startsWith("font/woff")

    /** Sends the state the console draws its live preview from. */
    suspend fun report() {
        val now = clock()
        val state = _door.value
        val payload = buildJsonObject {
            put("roomStatus", state.status?.status ?: "")
            put("announcements", JsonArray(state.visibleAnnouncements(now).map { JsonPrimitive(it.announcementId) }))
            put("configApplied", true)
            state.emergency?.let { put("emergency", it.emergencyId) }
            put("screen", if (state.isAsleep(now, zone)) "sleep" else "on")
            put("volume", state.audio.volume)
            state.theme?.let { put("themeId", it.id); put("themeVersion", it.version) }
            offlineSince?.let { put("offlineSince", it) }
        }
        session?.send("state.report", statePayload(payload))
    }

    private suspend fun acknowledge(ref: String) {
        session?.send("ack", ackPayload(ref))
    }

    private fun update(state: DoorState) {
        val pruned = state.withoutExpired(clock())
        val before = _door.value.emergency
        _door.value = pruned
        loadFonts(pruned.theme)
        if (before != pruned.emergency) {
            val current = pruned.emergency
            if (current != null) {
                _maintenance.value = null
                endIntercom(reset = true)
                endCall(null)
                soundAlarm(current)
            } else {
                alarm.stop()
            }
        }
        storage.write(STATE_KEY, protocolJson.encodeToString(DoorState.serializer(), pruned))
    }

    /**
     * A real emergency overrides the volume setting and quiet hours: people must hear it. A test
     * plays at its own volume and stays silent in quiet hours, so a test never wakes anyone.
     */
    private fun soundAlarm(emergency: Emergency) {
        val state = _door.value
        val quiet = state.audio.quietHours.contains(state.localTime(clock(), zone))
        val level = alarmLevel(emergency, quiet)
        if (level > 0f) alarm.start(emergency.kind, level) else alarm.stop()
    }

    private fun remember(id: String) {
        seenIds.addLast(id)
        while (seenIds.size > REMEMBERED_IDS) seenIds.removeFirst()
        storage.write(SEEN_KEY, protocolJson.encodeToString(ListSerializer(String.serializer()), seenIds.toList()))
    }

    private fun loadState(): DoorState =
        storage.read(STATE_KEY)?.let { runCatching { protocolJson.decodeFromString(DoorState.serializer(), it) }.getOrNull() } ?: DoorState()

    private fun loadSeenIds(): List<String> =
        storage.read(SEEN_KEY)?.let { runCatching { protocolJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty()

    private companion object {
        const val STATE_KEY = "state.json"
        const val SEEN_KEY = "seen-ids.json"
    }
}

private val FONT_SLOTS = setOf("body", "heading", "mono")

/** The server refuses fonts above this size; a larger one announced to a unit is not a font of ours. */
private const val MAX_FONT_BYTES = 2L * 1024 * 1024
