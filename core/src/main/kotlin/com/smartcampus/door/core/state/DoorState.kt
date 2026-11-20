package com.smartcampus.door.core.state

import com.smartcampus.door.core.protocol.Announcement
import com.smartcampus.door.core.protocol.AudioSettings
import com.smartcampus.door.core.protocol.DisplayConfig
import com.smartcampus.door.core.protocol.Emergency
import com.smartcampus.door.core.protocol.RegistrationWindow
import com.smartcampus.door.core.protocol.Reservation
import com.smartcampus.door.core.protocol.ReservationList
import com.smartcampus.door.core.protocol.RoomInfo
import com.smartcampus.door.core.protocol.RoomStatus
import com.smartcampus.door.core.protocol.ServerMessage
import com.smartcampus.door.core.protocol.Theme
import com.smartcampus.door.core.protocol.protocolJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/** How long the end-of-emergency notice stays when the server does not say. */
const val END_NOTICE_MS = 120_000L

/** The server's bounds (10 s to 15 min). A value outside them is clamped rather than trusted: a bad one must not pin the screen. */
private const val MIN_NOTICE_MS = 10_000L
private const val MAX_NOTICE_MS = 900_000L

/**
 * The message that follows an emergency, shown for [durationMs] from [shownAt] (server time). The duration is
 * stored with it, so a restart in the middle keeps the length the person chose.
 */
@Serializable
data class EndNotice(val emergencyId: String, val message: String, val shownAt: Long, val durationMs: Long = END_NOTICE_MS)

/**
 * Everything the door screen needs, persisted after every change so that a restart or a network
 * outage keeps showing the last known state (offline mode).
 */
@Serializable
data class DoorState(
    val room: RoomInfo? = null,
    val status: RoomStatus? = null,
    val announcements: Map<String, Announcement> = emptyMap(),
    val config: DisplayConfig = DisplayConfig(),
    val audio: AudioSettings = AudioSettings(),
    /** The visual theme the server chose for this unit; null is the app's own look. An emergency only takes the few points of `resolveEmergency` from it. */
    val theme: Theme? = null,
    /** Shown over everything else, with an alarm, until the server clears it. Persisted so a restart keeps it. */
    val emergency: Emergency? = null,
    /** Set while the server collects cards presented to this reader. Persisted so a restart keeps the mode until it expires. */
    val registration: RegistrationWindow? = null,
    /** Bookings of this room, kept in the digital twin so the next one shows through an outage. */
    val reservations: ReservationList? = null,
    /** Set when an emergency ended with a message. Persisted so a restart inside the window still shows it. */
    val endNotice: EndNotice? = null,
    /** serverTime - deviceTime at the last session.ready. Expiry uses it so a wrong device clock cannot show stale announcements. */
    val clockOffsetMs: Long = 0,
    val lastSyncAt: Long? = null,
) {
    /** Announcements still valid at [deviceNowMs], most urgent first. */
    fun visibleAnnouncements(deviceNowMs: Long): List<Announcement> {
        val serverNow = deviceNowMs + clockOffsetMs
        return announcements.values
            .filter { it.expiresAt == null || it.expiresAt > serverNow }
            .sortedWith(compareByDescending<Announcement> { priorityRank(it.priority) }.thenBy { it.announcementId })
    }

    /**
     * The booking that is on now, else the next one, ignoring those that ended. Only the list for the room this
     * unit is linked to counts: after the unit is moved, the old room's bookings must not show.
     */
    fun nextReservation(deviceNowMs: Long): Reservation? {
        val list = reservations?.takeIf { room == null || it.roomId == room.roomId } ?: return null
        val serverNow = deviceNowMs + clockOffsetMs
        return list.items.filter { it.end > serverNow }.minByOrNull { it.start }
    }

    /** The end-of-emergency notice while it is still due. */
    fun visibleEndNotice(deviceNowMs: Long): EndNotice? = endNotice?.takeIf { it.shownAt + it.durationMs.coerceIn(MIN_NOTICE_MS, MAX_NOTICE_MS) > deviceNowMs + clockOffsetMs }

    /** True while cards presented to this unit are being collected for enrolment. */
    fun isRegistering(deviceNowMs: Long): Boolean =
        registration?.let { it.expiresAt == null || it.expiresAt > deviceNowMs + clockOffsetMs } ?: false

    /** Applies the full desired state sent at the start of a session. It replaces what the server manages. */
    fun withDesired(desired: JsonObject, serverTime: Long, deviceNowMs: Long): DoorState {
        fun <T> field(name: String, decode: (JsonObject) -> T): T? = desired[name]?.jsonObject?.let(decode)
        return copy(
            room = field("room") { protocolJson.decodeFromJsonElement<RoomInfo>(it) } ?: room,
            status = field("roomStatus") { protocolJson.decodeFromJsonElement<RoomStatus>(it) } ?: status,
            config = field("displayConfig") { protocolJson.decodeFromJsonElement<DisplayConfig>(it) } ?: config,
            audio = field("audioSettings") { protocolJson.decodeFromJsonElement<AudioSettings>(it) } ?: audio,
            // Complete state, like the emergency: no theme in it means the theme was taken away, even if the command was missed.
            theme = desired["theme"]?.takeIf { it is JsonObject }?.let { runCatching { protocolJson.decodeFromJsonElement<Theme>(it) }.getOrNull() },
            // The desired state is complete: no `emergency` (or null) means the alert is over, even if we missed the clear.
            emergency = desired["emergency"]?.takeIf { it is JsonObject }?.let { protocolJson.decodeFromJsonElement<Emergency>(it) },
            reservations = field("reservations") { protocolJson.decodeFromJsonElement<ReservationList>(it) } ?: reservations,
            // A new alert replaces any end notice; without one, a notice from before the outage stays until it is due.
            endNotice = if (desired["emergency"] is JsonObject) null else endNotice,
            // Same rule: a desired state without a registration window means it ended.
            registration = desired["registration"]?.takeIf { it is JsonObject }?.let { protocolJson.decodeFromJsonElement<RegistrationWindow>(it) },
            announcements = field("announcements") { map ->
                map.values.associate { value ->
                    val announcement = protocolJson.decodeFromJsonElement<Announcement>(value)
                    announcement.announcementId to announcement
                }
            } ?: announcements,
            clockOffsetMs = serverTime - deviceNowMs,
            lastSyncAt = deviceNowMs,
        )
    }

    /** Applies one command. Commands that do not change what is shown (audio, ignored) return the state as is. */
    fun apply(message: ServerMessage, deviceNowMs: Long = System.currentTimeMillis()): DoorState =
        when (message) {
            is ServerMessage.RoomStatusSet -> copy(status = message.value)
            is ServerMessage.RoomInfoSet -> copy(room = message.value)
            is ServerMessage.DisplayConfigSet -> copy(config = message.config)
            is ServerMessage.AudioSettingsSet -> copy(audio = message.value)
            is ServerMessage.ThemeSet -> copy(theme = message.theme)
            is ServerMessage.AnnounceShow -> copy(announcements = announcements + (message.value.announcementId to message.value))
            is ServerMessage.AnnounceClear -> copy(announcements = announcements - message.announcementId)
            is ServerMessage.EmergencySet -> copy(emergency = message.value, endNotice = null)
            is ServerMessage.ReservationsSet -> copy(reservations = message.value)
            is ServerMessage.RegistrationModeSet ->
                // A stop for a session we already left (a newer one started) must not end the newer one.
                if (message.value.active) copy(registration = message.value)
                else if (registration?.sessionId == message.value.sessionId) copy(registration = null)
                else this
            // A clear for an alert we no longer show (a newer one took over) must not end the newer one.
            is ServerMessage.EmergencyClear ->
                if (emergency?.emergencyId == message.emergencyId) {
                    copy(emergency = null, endNotice = message.message?.let { EndNotice(message.emergencyId, it, deviceNowMs + clockOffsetMs, message.noticeSeconds?.let { s -> s * 1_000L } ?: END_NOTICE_MS) })
                } else this
            else -> this
        }

    /** Drops expired announcements so the persisted state does not grow forever. */
    fun withoutExpired(deviceNowMs: Long): DoorState {
        val visible = visibleAnnouncements(deviceNowMs).map { it.announcementId }.toSet()
        val pruned = if (visible.size == announcements.size) this else copy(announcements = announcements.filterKeys { it in visible })
        return if (pruned.endNotice != null && pruned.visibleEndNotice(deviceNowMs) == null) pruned.copy(endNotice = null) else pruned
    }

    private fun priorityRank(priority: String) = when (priority) {
        "urgent" -> 2
        "important" -> 1
        else -> 0
    }
}

/** Wall clock time of day as the room sees it: the device clock corrected by the server offset. */
fun DoorState.localTime(deviceNowMs: Long, zone: java.time.ZoneId): java.time.LocalTime =
    java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(deviceNowMs + clockOffsetMs), zone)

/** True while the night sleep window is on: the screen goes black. */
fun DoorState.isAsleep(deviceNowMs: Long, zone: java.time.ZoneId): Boolean = config.sleep.contains(localTime(deviceNowMs, zone))
