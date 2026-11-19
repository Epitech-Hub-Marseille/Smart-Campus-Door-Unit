package com.smartcampus.door.core

import com.smartcampus.door.core.audio.AssetSource
import com.smartcampus.door.core.audio.AudioPlayer
import com.smartcampus.door.core.net.LinkEvent
import com.smartcampus.door.core.net.MessageSink
import com.smartcampus.door.core.protocol.AudioPlay
import com.smartcampus.door.core.protocol.AudioSettings
import com.smartcampus.door.core.protocol.DisplayConfig
import com.smartcampus.door.core.protocol.Emergency
import com.smartcampus.door.core.protocol.Reservation
import com.smartcampus.door.core.protocol.ReservationList
import com.smartcampus.door.core.protocol.RoomInfo
import com.smartcampus.door.core.protocol.ServerMessage
import com.smartcampus.door.core.protocol.TimeWindow
import com.smartcampus.door.core.protocol.buildEnvelope
import com.smartcampus.door.core.protocol.parseServerMessage
import com.smartcampus.door.core.protocol.protocolJson
import com.smartcampus.door.core.state.DoorState
import com.smartcampus.door.core.state.END_NOTICE_MS
import com.smartcampus.door.core.storage.FileStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Recorder : MessageSink {
    val sent = mutableListOf<Pair<String, JsonElement>>()
    override suspend fun send(type: String, payload: JsonElement, id: String) {
        sent += type to payload
    }

    fun refs(type: String) = sent.filter { it.first == type }.map { it.second.jsonObject["ref"]!!.jsonPrimitive.content }
}

private object FileAssets : AssetSource {
    override suspend fun fetch(assetId: String, sha256: String, size: Long): File = File.createTempFile("asset", ".bin")
}

private class Volumes : AudioPlayer {
    val played = mutableListOf<Int>()
    override suspend fun play(file: File, volumePercent: Int) {
        played += volumePercent
    }
}

private fun frame(type: String, payload: String): ServerMessage =
    parseServerMessage(buildEnvelope("01ARZ3NDEKTSV4RRFFQ69G5FAV", type, 1, protocolJson.parseToJsonElement(payload))).first

private const val HOUR = 3_600_000L

@OptIn(ExperimentalCoroutinesApi::class)
class ReservationsAndNoticeTest {
    private val noon = java.time.LocalDate.of(2027, 1, 15).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val night = java.time.LocalDate.of(2027, 1, 15).atTime(23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val dir = Files.createTempDirectory("reservations-test").toFile()

    private fun controller(scope: TestScope, now: Long = noon, player: AudioPlayer = Volumes(), directory: File = dir) =
        DoorController(FileStorage(directory), FileAssets, player, scope, zone = ZoneOffset.UTC) { now }

    private suspend fun DoorController.receive(message: ServerMessage) = onEvent(LinkEvent.Message(message))

    private val room = RoomInfo("room-1", "B2-104", "Lab", "Building 2", 1, 24)
    private fun booking(id: String, startH: Long, endH: Long, title: String = id) = Reservation(id, title, noon + startH * HOUR, noon + endH * HOUR, "Ada")
    private fun emergency(id: String = "E1") = Emergency(id, "fire", "Fire alarm", "Leave the building.", startedAt = 1)

    // ---- wire format ----

    @Test
    fun reads_the_messages_the_server_added() {
        val reservations = frame("reservations.set", """{"roomId":"room-1","items":[{"id":"r1","title":"Algorithms","start":1000,"end":2000,"organizer":"Ada"}]}""")
        assertEquals(ReservationList("room-1", listOf(Reservation("r1", "Algorithms", 1000, 2000, "Ada"))), (reservations as ServerMessage.ReservationsSet).value)

        val clear = frame("emergency.clear", """{"emergencyId":"E1","message":"All clear."}""") as ServerMessage.EmergencyClear
        assertEquals("All clear.", clear.message)
        assertNull((frame("emergency.clear", """{"emergencyId":"E1"}""") as ServerMessage.EmergencyClear).message)

        val urgent = frame("audio.play", """{"announcementId":"n","assetId":"a","sha256":"${"0".repeat(64)}","size":5,"urgent":true}""") as ServerMessage.AudioPlayRequest
        assertTrue(urgent.value.urgent)
        assertFalse((frame("audio.play", """{"announcementId":"n","assetId":"a","sha256":"${"0".repeat(64)}","size":5}""") as ServerMessage.AudioPlayRequest).value.urgent)
    }

    @Test
    fun a_display_config_saved_before_reservations_existed_still_shows_the_next_booking() {
        val config = protocolJson.decodeFromString(DisplayConfig.serializer(), """{"show":{"roomName":false}}""")
        assertTrue(config.show.nextReservation)
        assertFalse(config.show.roomName)
    }

    // ---- next reservation ----

    @Test
    fun shows_the_booking_that_is_on_now_else_the_next_and_drops_ended_ones() {
        val state = DoorState(room = room, reservations = ReservationList("room-1", listOf(booking("past", -3, -2), booking("later", 3, 4), booking("now", -1, 1), booking("soon", 1, 2))))
        assertEquals("now", state.nextReservation(noon)?.id)
        assertEquals("soon", state.nextReservation(noon + 90 * 60_000)?.id)
        assertEquals("later", state.nextReservation(noon + 2 * HOUR + 1)?.id)
        assertNull(state.nextReservation(noon + 5 * HOUR))
    }

    @Test
    fun judges_time_by_the_server_clock_not_a_wrong_device_clock() {
        val state = DoorState(room = room, reservations = ReservationList("room-1", listOf(booking("a", 0, 1))), clockOffsetMs = 2 * HOUR)
        // The device thinks it is noon but the server says 14:00: the 12:00-13:00 booking is over.
        assertNull(state.nextReservation(noon))
    }

    @Test
    fun ignores_the_bookings_of_another_room() {
        val state = DoorState(room = room, reservations = ReservationList("another-room", listOf(booking("a", 0, 1))))
        assertNull(state.nextReservation(noon))
    }

    @Test
    fun a_reservations_command_is_applied_acknowledged_and_never_run_twice() = runTest {
        val controller = controller(this)
        val sink = Recorder().also { controller.onEvent(LinkEvent.Connected(it)) }
        controller.receive(ServerMessage.RoomInfoSet("I1", room))
        controller.receive(ServerMessage.ReservationsSet("R1", ReservationList("room-1", listOf(booking("a", 1, 2)))))
        assertEquals("a", controller.door.value.nextReservation(noon)?.id)
        controller.receive(ServerMessage.ReservationsSet("R1", ReservationList("room-1", emptyList())))
        assertEquals("a", controller.door.value.nextReservation(noon)?.id) // the replay of R1 did nothing
        assertTrue("R1" in sink.refs("ack"))
    }

    @Test
    fun the_next_booking_survives_a_restart_and_comes_back_with_the_desired_state() = runTest {
        val own = Files.createTempDirectory("restart").toFile()
        val first = controller(this, directory = own)
        first.receive(ServerMessage.RoomInfoSet("I1", room))
        first.receive(ServerMessage.ReservationsSet("R1", ReservationList("room-1", listOf(booking("a", 1, 2)))))

        val second = controller(this, directory = own)
        assertEquals("a", second.door.value.nextReservation(noon)?.id)

        val desired = protocolJson.parseToJsonElement("""{"reservations":{"roomId":"room-1","items":[{"id":"b","title":"b","start":${noon + HOUR},"end":${noon + 2 * HOUR}}]}}""").jsonObject
        second.receive(ServerMessage.SessionReady("S", desired, noon, 0))
        assertEquals("b", second.door.value.nextReservation(noon)?.id)
    }

    // ---- end of emergency ----

    @Test
    fun clearing_with_a_message_shows_it_for_two_minutes_then_it_goes_away() = runTest {
        val controller = controller(this)
        controller.receive(ServerMessage.EmergencySet("C1", emergency()))
        controller.receive(ServerMessage.EmergencyClear("C2", "E1", "All clear, you may return."))

        val state = controller.door.value
        assertNull(state.emergency)
        assertEquals("All clear, you may return.", state.visibleEndNotice(noon + 1)?.message)
        assertNotNull(state.visibleEndNotice(noon + END_NOTICE_MS - 1))
        assertNull(state.visibleEndNotice(noon + END_NOTICE_MS + 1))
        assertNull(state.withoutExpired(noon + END_NOTICE_MS + 1).endNotice)
    }

    @Test
    fun the_server_can_choose_how_long_the_notice_stays() = runTest {
        val controller = controller(this)
        controller.receive(ServerMessage.EmergencySet("C1", emergency()))
        controller.receive(ServerMessage.EmergencyClear("C2", "E1", "Over.", noticeSeconds = 30))

        val state = controller.door.value
        assertNotNull(state.visibleEndNotice(noon + 29_000))
        assertNull(state.visibleEndNotice(noon + 31_000))
        assertNull(state.withoutExpired(noon + 31_000).endNotice)
    }

    @Test
    fun a_long_notice_outlasts_the_default_and_a_restart_keeps_its_length() = runTest {
        val own = Files.createTempDirectory("long-notice").toFile()
        val first = controller(this, directory = own)
        first.receive(ServerMessage.EmergencySet("C1", emergency()))
        first.receive(ServerMessage.EmergencyClear("C2", "E1", "Over.", noticeSeconds = 600))

        val second = controller(this, directory = own)
        assertNotNull(second.door.value.visibleEndNotice(noon + END_NOTICE_MS + 60_000))
        assertNull(second.door.value.visibleEndNotice(noon + 601_000))
    }

    @Test
    fun an_absurd_duration_is_clamped_so_it_cannot_pin_the_screen() {
        val long = DoorState(endNotice = com.smartcampus.door.core.state.EndNotice("E1", "x", noon, durationMs = Long.MAX_VALUE / 4))
        assertNull(long.visibleEndNotice(noon + 901_000))
        val tiny = DoorState(endNotice = com.smartcampus.door.core.state.EndNotice("E1", "x", noon, durationMs = 1))
        assertNotNull(tiny.visibleEndNotice(noon + 5_000))
    }

    @Test
    fun reads_the_duration_from_the_wire_and_an_older_saved_notice_keeps_the_default() {
        val clear = frame("emergency.clear", """{"emergencyId":"E1","message":"Over.","noticeSeconds":45}""") as ServerMessage.EmergencyClear
        assertEquals(45, clear.noticeSeconds)
        assertNull((frame("emergency.clear", """{"emergencyId":"E1","message":"Over."}""") as ServerMessage.EmergencyClear).noticeSeconds)
        val saved = protocolJson.decodeFromString(com.smartcampus.door.core.state.EndNotice.serializer(), """{"emergencyId":"E1","message":"Over.","shownAt":5}""")
        assertEquals(END_NOTICE_MS, saved.durationMs)
    }

    @Test
    fun clearing_without_a_message_shows_nothing() = runTest {
        val controller = controller(this)
        controller.receive(ServerMessage.EmergencySet("C1", emergency()))
        controller.receive(ServerMessage.EmergencyClear("C2", "E1"))
        assertNull(controller.door.value.endNotice)
    }

    @Test
    fun a_new_alert_replaces_the_notice_and_the_clear_of_an_old_alert_changes_nothing() = runTest {
        val controller = controller(this)
        controller.receive(ServerMessage.EmergencySet("C1", emergency("E1")))
        controller.receive(ServerMessage.EmergencyClear("C2", "E1", "Over."))
        controller.receive(ServerMessage.EmergencySet("C3", emergency("E2")))
        assertNull(controller.door.value.endNotice)

        controller.receive(ServerMessage.EmergencyClear("C4", "E1", "Stale notice."))
        assertEquals("E2", controller.door.value.emergency?.emergencyId)
        assertNull(controller.door.value.endNotice)
    }

    @Test
    fun the_notice_is_kept_through_a_restart_and_dropped_by_a_desired_state_with_an_alert() = runTest {
        val own = Files.createTempDirectory("notice").toFile()
        val first = controller(this, directory = own)
        first.receive(ServerMessage.EmergencySet("C1", emergency()))
        first.receive(ServerMessage.EmergencyClear("C2", "E1", "Over."))

        val second = controller(this, directory = own)
        assertEquals("Over.", second.door.value.visibleEndNotice(noon + 1000)?.message)

        val desired = protocolJson.parseToJsonElement("""{"emergency":{"emergencyId":"E3","kind":"fire","title":"Fire alarm","instructions":"Leave.","startedAt":1}}""").jsonObject
        second.receive(ServerMessage.SessionReady("S", desired, noon, 0))
        assertNull(second.door.value.endNotice)
        assertEquals("E3", second.door.value.emergency?.emergencyId)
    }

    // ---- urgent audio ----

    private val urgentAudio = ServerMessage.AudioPlayRequest("A1", AudioPlay("n1", "Evacuate", "asset1", "0".repeat(64), 10, urgent = true))
    private val quiet = AudioSettings(volume = 35, quietHours = TimeWindow(true, "22:00", "06:00"))

    @Test
    fun an_urgent_broadcast_plays_at_full_volume_even_in_quiet_hours() = runTest {
        val player = Volumes()
        val controller = controller(this, now = night, player = player)
        val sink = Recorder().also { controller.onEvent(LinkEvent.Connected(it)) }
        controller.receive(ServerMessage.AudioSettingsSet("S1", quiet))
        controller.receive(urgentAudio)
        advanceUntilIdle()

        assertEquals(listOf(100), player.played)
        assertTrue("A1" in sink.refs("ack"))
    }

    @Test
    fun an_ordinary_broadcast_still_obeys_quiet_hours_and_volume() = runTest {
        val player = Volumes()
        val controller = controller(this, now = night, player = player)
        controller.receive(ServerMessage.AudioSettingsSet("S1", quiet))
        controller.receive(ServerMessage.AudioPlayRequest("A2", AudioPlay("n2", "Bell", "asset2", "0".repeat(64), 10)))
        advanceUntilIdle()
        assertTrue(player.played.isEmpty())
    }

}
