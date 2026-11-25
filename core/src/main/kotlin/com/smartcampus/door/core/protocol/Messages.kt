package com.smartcampus.door.core.protocol

import com.smartcampus.door.core.util.B64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

const val PROTOCOL_VERSION = 1

/** Largest frame the server accepts, before and after encryption. */
const val MAX_FRAME_BYTES = 64 * 1024

val protocolJson = Json {
    ignoreUnknownKeys = true
    // Defaults are part of the contract: a partial display config from the server is valid.
    coerceInputValues = true
    encodeDefaults = true
}

@Serializable
data class TimeWindow(val enabled: Boolean = false, val from: String = "22:00", val to: String = "06:00")

@Serializable
data class DisplayShow(
    val roomName: Boolean = true,
    val building: Boolean = true,
    val floor: Boolean = true,
    val capacity: Boolean = true,
    val clock: Boolean = true,
    val announcements: Boolean = true,
    val nextReservation: Boolean = true,
)

@Serializable
data class DisplayConfig(
    val layout: String = "standard",
    val theme: String = "dark",
    val accentColor: String = "#00a86b",
    val show: DisplayShow = DisplayShow(),
    val sleep: TimeWindow = TimeWindow(),
)

/** The colours a theme may set, as "#rrggbb". Whatever it leaves out keeps the app's own. */
@Serializable
data class ThemeColors(
    val background: String? = null,
    val surface: String? = null,
    val text: String? = null,
    val muted: String? = null,
    val primary: String? = null,
    val primaryText: String? = null,
    val border: String? = null,
    val success: String? = null,
    val warning: String? = null,
    val danger: String? = null,
)

@Serializable
data class ThemeTypography(val fontFamily: String? = null, val scale: Double? = null)

@Serializable
data class ThemeShape(val radius: Int? = null)

@Serializable
data class ThemeTokens(
    val mode: String? = null,
    val colors: ThemeColors = ThemeColors(),
    val typography: ThemeTypography = ThemeTypography(),
    val shape: ThemeShape = ThemeShape(),
)

/**
 * A visual theme (see `packages/protocol/src/theme.ts` on the server). The CSS the server may send to Faculty screens
 * is not read here: a door unit is not a web page.
 */
@Serializable
data class Theme(
    val id: String,
    val name: String,
    val version: Int,
    val tokens: ThemeTokens = ThemeTokens(),
    /** Font files by slot ("body", "heading", "mono"). Without one for a slot, the app keeps the font family of the tokens. */
    val fonts: Map<String, ThemeFont> = emptyMap(),
    /**
     * How an alert is dressed. Kept as raw JSON on purpose: it is read, and checked again, by `resolveEmergency`, so a part
     * this unit cannot read (a newer server, a wrong value) never costs the rest of the theme; the alert is then the default one.
     */
    val emergency: JsonObject? = null,
)

/** A font file the unit downloads like any asset: id, SHA-256 and size, all checked before the file is used. */
@Serializable
data class ThemeFont(val assetId: String, val sha256: String, val size: Long, val mime: String = "")

@Serializable
data class AudioSettings(val volume: Int = 80, val quietHours: TimeWindow = TimeWindow())

@Serializable
data class RoomStatus(
    val roomId: String,
    val status: String,
    val label: String,
    val color: String,
    val icon: String,
    val since: Long? = null,
)

@Serializable
data class RoomInfo(
    val roomId: String,
    val code: String,
    val name: String = "",
    val building: String = "",
    val floor: Int = 0,
    val capacity: Int? = null,
)

@Serializable
data class Announcement(
    val announcementId: String,
    val title: String? = null,
    val text: String,
    val priority: String = "info",
    val expiresAt: Long? = null,
)

@Serializable
data class AudioPlay(
    val announcementId: String,
    val title: String? = null,
    val assetId: String,
    val sha256: String,
    val size: Long,
    /** Emergencies only: plays at full volume and ignores quiet hours. */
    val urgent: Boolean = false,
    /** Set on the read-out of an emergency, a test included (a test is not `urgent`): the alarm steps back while it plays. */
    val emergencyId: String? = null,
)

/** One booking of the room, times in ms since epoch (server time, like every other timestamp). */
@Serializable
data class Reservation(
    val id: String,
    val title: String,
    val start: Long,
    val end: Long,
    val organizer: String? = null,
)

/** The upcoming bookings of [roomId] (at most five). The screen shows the current or next one that has not ended. */
@Serializable
data class ReservationList(val roomId: String, val items: List<Reservation> = emptyList())

/** An emergency takes over the screen and sounds an alarm until it is cleared. */
@Serializable
data class Emergency(
    val emergencyId: String,
    val kind: String,
    val title: String,
    val instructions: String,
    val testMode: Boolean = false,
    val testVolume: Int = 20,
    val startedAt: Long = 0,
)

/**
 * While a window is active, cards presented to this reader are collected by the server for
 * enrolment, not treated as logins. The unit leaves the mode by itself at [expiresAt] (server
 * time), even when it is offline, so a lost "stop" never leaves the maintenance menu locked out.
 */
@Serializable
data class RegistrationWindow(val sessionId: String, val active: Boolean = true, val expiresAt: Long? = null)

/** Outcomes of `card.result`. */
object CardOutcome {
    const val MAINTENANCE = "maintenance"
    const val RECOGNISED = "recognised"
    const val CAPTURED = "captured"
    const val UNKNOWN = "unknown"
    const val DISABLED = "disabled"
    const val DUPLICATE = "duplicate"
    const val ALREADY_REGISTERED = "already_registered"
    const val RATE_LIMITED = "rate_limited"
}

/** A room status the menu can set. Travels with its look, like `room.status.set`, so no catalogue is kept here. */
@Serializable
data class MenuStatus(val key: String, val label: String, val color: String, val icon: String)

/** What the server offers the person whose card opened the menu. */
@Serializable
data class CardMenu(val statuses: List<MenuStatus> = emptyList(), val canRevoke: Boolean = false, val canAnnounce: Boolean = false)

/** Another door terminal this one may call, and whether it is connected right now. */
@Serializable
data class CallPeer(val id: String, val name: String, val online: Boolean)

/** The states of a call between two terminals, as the server names them. */
object CallStates {
    const val RINGING = "ringing"
    const val ACTIVE = "active"
}

/** The actions of the maintenance menu, as the server names them. */
object MaintenanceActions {
    const val SET_STATUS = "status.set"
    const val RESTART = "restart"
    const val REVOKE = "revoke"
}

/** What the server sends after the handshake. Only these types exist for a door unit. */
sealed interface ServerMessage {
    val id: String

    data class SessionReady(override val id: String, val desired: JsonObject, val serverTime: Long, val pendingCommands: Int) : ServerMessage
    data class RoomStatusSet(override val id: String, val value: RoomStatus) : ServerMessage
    data class RoomInfoSet(override val id: String, val value: RoomInfo) : ServerMessage
    data class DisplayConfigSet(override val id: String, val config: DisplayConfig) : ServerMessage
    data class AudioSettingsSet(override val id: String, val value: AudioSettings) : ServerMessage
    /** [theme] is null to go back to the app's own look. */
    data class ThemeSet(override val id: String, val theme: Theme?) : ServerMessage
    data class AnnounceShow(override val id: String, val value: Announcement) : ServerMessage
    data class AnnounceClear(override val id: String, val announcementId: String) : ServerMessage
    data class AudioPlayRequest(override val id: String, val value: AudioPlay) : ServerMessage
    data class EmergencySet(override val id: String, val value: Emergency) : ServerMessage
    /** [message] is the end-of-emergency notice, shown for [noticeSeconds] (the screen's own default when absent). */
    data class EmergencyClear(override val id: String, val emergencyId: String, val message: String? = null, val noticeSeconds: Int? = null) : ServerMessage
    data class ReservationsSet(override val id: String, val value: ReservationList) : ServerMessage
    data class RegistrationModeSet(override val id: String, val value: RegistrationWindow) : ServerMessage

    /**
     * The answer to an `event.badge` ([ref] is its id). Not a command: it is never acknowledged nor
     * remembered, because a late answer must not open a menu for someone who has walked away.
     */
    data class CardResult(
        override val id: String,
        val ref: String,
        val outcome: String,
        val maintenance: Boolean,
        val name: String?,
        val menu: CardMenu? = null,
    ) : ServerMessage

    /** The answer to a `maintenance.action` ([ref] is its id). Not a command either. */
    data class MaintenanceResult(override val id: String, val ref: String, val action: String, val ok: Boolean, val error: String?) : ServerMessage

    /** Someone is speaking live (a microphone announcement). Not a command: never acknowledged, and one that is missed is missed. */
    data class LiveStart(override val id: String, val streamId: String, val from: String) : ServerMessage

    /** A slice of the voice: 16 kHz mono 16-bit little-endian PCM, already decoded from base64. */
    class LiveChunk(override val id: String, val streamId: String, val pcm: ByteArray) : ServerMessage

    data class LiveEnd(override val id: String, val streamId: String) : ServerMessage

    /** The answer to this terminal's `live.start` or `call.request` ([ref] is its id). Not a command either. */
    data class IntercomResult(override val id: String, val ref: String, val kind: String, val ok: Boolean, val error: String?, val deviceCount: Int?) : ServerMessage

    /** The answer to `call.list` ([ref] is its id): the terminals that can be called. Not a command. */
    data class CallDirectory(override val id: String, val ref: String, val peers: List<CallPeer>) : ServerMessage

    /** Another terminal is calling this one. Not a command: a call that rings late is one nobody waits for. */
    data class CallIncoming(override val id: String, val callId: String, val from: String) : ServerMessage

    /** The call changed state (see [CallStates], or an end: declined, ended, busy, unavailable, timeout). [peer] names the other side. */
    data class CallState(override val id: String, val callId: String, val state: String, val peer: String?) : ServerMessage

    /** The other side's voice, 16 kHz mono 16-bit PCM. */
    class CallAudio(override val id: String, val callId: String, val pcm: ByteArray) : ServerMessage

    /** A valid command for another kind of device (e.g. access.acl.sync): acknowledged, not acted on. */
    data class Ignored(override val id: String, val type: String) : ServerMessage
}

class ProtocolException(message: String) : Exception(message)

/** Parses a decrypted frame. Throws [ProtocolException] for anything malformed. */
fun parseServerMessage(frame: ByteArray): Pair<ServerMessage, Long> {
    if (frame.size > MAX_FRAME_BYTES) throw ProtocolException("frame too large")
    val root = try {
        protocolJson.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
    } catch (e: Exception) {
        throw ProtocolException("invalid JSON")
    }
    val id = root["id"]?.jsonPrimitive?.contentOrNull ?: throw ProtocolException("missing id")
    val type = root["type"]?.jsonPrimitive?.contentOrNull ?: throw ProtocolException("missing type")
    val seq = root["seq"]?.jsonPrimitive?.long ?: throw ProtocolException("missing seq")
    val payload = root["payload"]?.jsonObject ?: throw ProtocolException("missing payload")

    val message = try {
        decode(id, type, payload)
    } catch (e: kotlinx.serialization.SerializationException) {
        throw ProtocolException("bad $type payload: ${e.message}")
    }
    return message to seq
}

private inline fun <reified T> JsonObject.decode(): T = protocolJson.decodeFromJsonElement<T>(this)

private fun decode(id: String, type: String, payload: JsonObject): ServerMessage =
    when (type) {
        "session.ready" -> ServerMessage.SessionReady(
            id,
            payload["desired"]?.jsonObject ?: JsonObject(emptyMap()),
            payload["serverTime"]?.jsonPrimitive?.long ?: 0,
            payload["pendingCommands"]?.jsonPrimitive?.long?.toInt() ?: 0,
        )
        "room.status.set" -> ServerMessage.RoomStatusSet(id, payload.decode())
        "room.info.set" -> ServerMessage.RoomInfoSet(id, payload.decode())
        "display.config.set" -> ServerMessage.DisplayConfigSet(id, payload["config"]!!.jsonObject.decode())
        "audio.settings.set" -> ServerMessage.AudioSettingsSet(id, payload.decode())
        "theme.set" -> ServerMessage.ThemeSet(id, payload["theme"]?.takeIf { it is JsonObject }?.jsonObject?.decode())
        "announce.show" -> ServerMessage.AnnounceShow(id, payload.decode())
        "announce.clear" -> ServerMessage.AnnounceClear(id, payload["announcementId"]!!.jsonPrimitive.content)
        "audio.play" -> ServerMessage.AudioPlayRequest(id, payload.decode())
        "emergency.set" -> ServerMessage.EmergencySet(id, payload.decode())
        "emergency.clear" -> ServerMessage.EmergencyClear(
            id,
            payload["emergencyId"]!!.jsonPrimitive.content,
            payload["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            payload["noticeSeconds"]?.jsonPrimitive?.long?.toInt(),
        )
        "reservations.set" -> ServerMessage.ReservationsSet(id, payload.decode())
        "registration.mode.set" -> ServerMessage.RegistrationModeSet(id, payload.decode())
        "card.result" -> ServerMessage.CardResult(
            id,
            ref = payload["ref"]!!.jsonPrimitive.content,
            outcome = payload["outcome"]!!.jsonPrimitive.content,
            maintenance = payload["maintenance"]!!.jsonPrimitive.boolean,
            name = payload["name"]?.jsonPrimitive?.contentOrNull,
            menu = payload["menu"]?.takeIf { it is JsonObject }?.jsonObject?.decode<CardMenu>(),
        )
        "maintenance.result" -> ServerMessage.MaintenanceResult(
            id,
            ref = payload["ref"]!!.jsonPrimitive.content,
            action = payload["action"]!!.jsonPrimitive.content,
            ok = payload["ok"]!!.jsonPrimitive.boolean,
            error = payload["error"]?.jsonPrimitive?.contentOrNull,
        )
        "live.start" -> ServerMessage.LiveStart(id, payload["streamId"]!!.jsonPrimitive.content, payload["from"]?.jsonPrimitive?.contentOrNull.orEmpty())
        "live.chunk" -> ServerMessage.LiveChunk(id, payload["streamId"]!!.jsonPrimitive.content, runCatching { B64.decode(payload["data"]!!.jsonPrimitive.content) }.getOrDefault(ByteArray(0)))
        "live.end" -> ServerMessage.LiveEnd(id, payload["streamId"]!!.jsonPrimitive.content)
        "intercom.result" -> ServerMessage.IntercomResult(
            id,
            ref = payload["ref"]!!.jsonPrimitive.content,
            kind = payload["kind"]!!.jsonPrimitive.content,
            ok = payload["ok"]!!.jsonPrimitive.boolean,
            error = payload["error"]?.jsonPrimitive?.contentOrNull,
            deviceCount = payload["deviceCount"]?.jsonPrimitive?.long?.toInt(),
        )
        "call.directory" -> ServerMessage.CallDirectory(id, payload["ref"]!!.jsonPrimitive.content, payload["peers"]!!.jsonArray.map { it.jsonObject.decode<CallPeer>() })
        "call.incoming" -> ServerMessage.CallIncoming(id, payload["callId"]!!.jsonPrimitive.content, payload["from"]?.jsonPrimitive?.contentOrNull.orEmpty())
        "call.state" -> ServerMessage.CallState(id, payload["callId"]!!.jsonPrimitive.content, payload["state"]!!.jsonPrimitive.content, payload["peer"]?.jsonPrimitive?.contentOrNull)
        "call.audio" -> ServerMessage.CallAudio(id, payload["callId"]!!.jsonPrimitive.content, runCatching { B64.decode(payload["data"]!!.jsonPrimitive.content) }.getOrDefault(ByteArray(0)))
        else -> ServerMessage.Ignored(id, type)
    }

/** Builds the plaintext envelope of a device message. `seq` starts at 1 per connection. */
fun buildEnvelope(id: String, type: String, seq: Long, payload: JsonElement, nowMs: Long = System.currentTimeMillis()): ByteArray =
    buildJsonObject {
        put("v", PROTOCOL_VERSION)
        put("id", id)
        put("type", type)
        put("ts", nowMs)
        put("seq", seq)
        put("payload", payload)
    }.toString().toByteArray(Charsets.UTF_8)

fun ackPayload(ref: String): JsonObject = buildJsonObject { put("ref", ref) }

fun nackPayload(ref: String, reason: String): JsonObject = buildJsonObject {
    put("ref", ref)
    put("reason", reason.take(200))
}

fun statePayload(state: JsonObject): JsonObject = buildJsonObject { put("state", state) }


/** A card was presented to the reader. [badgeUid] is the serial number in upper-case hex; the server normalises it anyway. */
fun badgePayload(badgeUid: String, atMs: Long): JsonObject = buildJsonObject {
    put("badgeUid", badgeUid)
    put("at", atMs)
}

/** An action chosen in the maintenance menu. [status] is only for `status.set`. */
fun maintenanceActionPayload(action: String, status: String? = null): JsonObject = buildJsonObject {
    put("action", action)
    status?.let { put("status", it) }
}

/** Payload of a message that carries nothing (`live.start`, `live.end`, `call.request`). */
fun emptyPayload(): JsonObject = JsonObject(emptyMap())

/** A slice of the microphone: [pcm] is 16 kHz mono 16-bit little-endian, at most [LIVE_MAX_CHUNK_BYTES]. */
fun liveChunkPayload(pcm: ByteArray): JsonObject = buildJsonObject { put("data", B64.encode(pcm)) }

/** Largest slice of live audio the server takes in one message (a quarter of a second). */
const val LIVE_MAX_CHUNK_BYTES = 8_000

/** Live voice is 16 kHz mono 16-bit PCM. */
const val LIVE_SAMPLE_RATE = 16_000

fun callRequestPayload(target: String): JsonObject = buildJsonObject { put("target", target) }

fun callAnswerPayload(callId: String, accept: Boolean): JsonObject = buildJsonObject {
    put("callId", callId)
    put("accept", accept)
}

fun callHangupPayload(callId: String): JsonObject = buildJsonObject { put("callId", callId) }

fun callAudioPayload(callId: String, pcm: ByteArray): JsonObject = buildJsonObject {
    put("callId", callId)
    put("data", B64.encode(pcm))
}
