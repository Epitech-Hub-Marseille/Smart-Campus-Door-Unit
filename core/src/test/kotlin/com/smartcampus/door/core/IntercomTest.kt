package com.smartcampus.door.core

import com.smartcampus.door.core.audio.AlarmPlayer
import com.smartcampus.door.core.audio.AssetSource
import com.smartcampus.door.core.audio.AudioPlayer
import com.smartcampus.door.core.audio.LivePlayer
import com.smartcampus.door.core.audio.Microphone
import com.smartcampus.door.core.net.LinkEvent
import com.smartcampus.door.core.net.MessageSink
import com.smartcampus.door.core.protocol.CallPeer
import com.smartcampus.door.core.protocol.CardMenu
import com.smartcampus.door.core.protocol.ServerMessage
import com.smartcampus.door.core.protocol.parseServerMessage
import com.smartcampus.door.core.storage.FileStorage
import com.smartcampus.door.core.util.B64
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object IntercomNoDownloads : AssetSource {
    override suspend fun fetch(assetId: String, sha256: String, size: Long): File = error("no download expected")
}

private object IntercomNoSound : AudioPlayer {
    override suspend fun play(file: File, volumePercent: Int) = Unit
}

private class IntercomSink : MessageSink {
    val sent = mutableListOf<Triple<String, JsonElement, String>>()
    override suspend fun send(type: String, payload: JsonElement, id: String) {
        sent += Triple(type, payload, id)
    }

    fun ofType(type: String) = sent.filter { it.first == type }
}

private class IntercomSpeaker : LivePlayer {
    val events = mutableListOf<String>()
    val written = mutableListOf<ByteArray>()
    override fun start() { events += "start" }
    override fun write(pcm: ByteArray) { written += pcm }
    override fun stop() { events += "stop" }
}

private class IntercomMic(private val works: Boolean = true) : Microphone {
    var feed: ((ByteArray) -> Unit)? = null
    var stopped = false
    override fun start(onChunk: (ByteArray) -> Unit): Boolean {
        feed = onChunk
        return works
    }
    override fun stop() { stopped = true }
}

private class IntercomAlarm : AlarmPlayer {
    val ducks = mutableListOf<Boolean>()
    override fun start(kind: String, level: Float) = Unit
    override fun stop() = Unit
    override fun duck(on: Boolean) { ducks += on }
}

@OptIn(ExperimentalCoroutinesApi::class)
class IntercomTest {
    private val now = java.time.LocalDate.of(2027, 1, 15).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val speaker = IntercomSpeaker()
    private val mic = IntercomMic()
    private val alarm = IntercomAlarm()
    private val callSpeaker = IntercomSpeaker()

    private fun controller(scope: TestScope, microphone: Microphone = mic) =
        DoorController(FileStorage(Files.createTempDirectory("intercom").toFile()), IntercomNoDownloads, IntercomNoSound, scope, alarm, ZoneOffset.UTC, live = speaker, callPlayer = callSpeaker, microphone = microphone) { now }

    private suspend fun DoorController.connect() = IntercomSink().also { onEvent(LinkEvent.Connected(it)) }
    private suspend fun DoorController.receive(message: ServerMessage) = onEvent(LinkEvent.Message(message))

    /** A card the server accepted for a person who may announce. */
    private suspend fun DoorController.tap(sink: IntercomSink, canAnnounce: Boolean = true, outcome: String = "maintenance") {
        onCardRead("04A22B1C")
        val ref = sink.ofType("event.badge").last().third
        receive(ServerMessage.CardResult("C$ref".take(26), ref, outcome, outcome == "maintenance", "Olga", CardMenu(canAnnounce = canAnnounce)))
    }

    private suspend fun DoorController.answerStart(sink: IntercomSink, ok: Boolean = true, error: String? = null) {
        val ref = sink.ofType("live.start").last().third
        receive(ServerMessage.IntercomResult("R1", ref, "announce", ok, error, if (ok) 3 else null))
    }

    @Test
    fun `a card that may announce opens the microphone panel and not the maintenance menu`() = runTest {
        val c = controller(this)
        val sink = c.connect()
        c.beginAnnouncement()
        assertTrue(c.intercom.value.awaitingCard)
        c.tap(sink)
        assertEquals("Olga", c.intercom.value.announcer)
        assertFalse(c.intercom.value.awaitingCard)
        assertNull(c.maintenance.value)
    }

    @Test
    fun `a card that may not announce is refused and the terminal keeps waiting`() = runTest {
        val c = controller(this)
        val sink = c.connect()
        c.beginAnnouncement()
        c.tap(sink, outcome = "recognised")
        assertNull(c.intercom.value.announcer)
        assertTrue(c.intercom.value.awaitingCard)
        assertEquals("This card cannot make announcements", c.intercom.value.error)
        c.tap(sink, canAnnounce = false)
        assertNull(c.intercom.value.announcer)
    }

    @Test
    fun `going on air waits for the server then sends the voice in order and ends it`() = runTest {
        val c = controller(this)
        val sink = c.connect()
        c.beginAnnouncement()
        c.tap(sink)
        c.goOnAir()
        assertTrue(c.intercom.value.starting)
        assertTrue(mic.feed == null, "the microphone stays closed until the server agrees")

        c.answerStart(sink)
        assertTrue(c.intercom.value.onAir)
        assertEquals(3, c.intercom.value.screens)
        mic.feed!!(ByteArray(100) { 1 })
        mic.feed!!(ByteArray(100) { 2 })
        advanceUntilIdle()
        val chunks = sink.ofType("live.chunk").map { B64.decode(it.second.jsonObject["data"]!!.jsonPrimitive.content) }
        assertEquals(2, chunks.size)
        assertContentEquals(ByteArray(100) { 1 }, chunks[0])
        assertContentEquals(ByteArray(100) { 2 }, chunks[1])

        c.goOffAir()
        advanceUntilIdle()
        assertTrue(mic.stopped)
        assertFalse(c.intercom.value.onAir)
        assertEquals(1, sink.ofType("live.end").size)
        assertTrue(sink.sent.indexOfLast { it.first == "live.chunk" } < sink.sent.indexOfFirst { it.first == "live.end" })
    }

    @Test
    fun `a refusal from the server leaves the microphone shut and says why`() = runTest {
        val c = controller(this)
        val sink = c.connect()
        c.beginAnnouncement()
        c.tap(sink)
        c.goOnAir()
        c.answerStart(sink, ok = false, error = "Another announcement is on the air")
        assertTrue(mic.feed == null)
        assertFalse(c.intercom.value.onAir)
        assertEquals("Another announcement is on the air", c.intercom.value.error)
    }

    @Test
    fun `a microphone that cannot be opened tells the server the announcement is off`() = runTest {
        val c = controller(this, IntercomMic(works = false))
        val sink = c.connect()
        c.beginAnnouncement()
        c.tap(sink)
        c.goOnAir()
        c.answerStart(sink)
        advanceUntilIdle()
        assertFalse(c.intercom.value.onAir)
        assertEquals("The microphone cannot be opened", c.intercom.value.error)
        assertEquals(1, sink.ofType("live.end").size)
    }

    @Test
    fun `losing the link or starting an emergency closes the microphone`() = runTest {
        val c = controller(this)
        val sink = c.connect()
        c.beginAnnouncement()
        c.tap(sink)
        c.goOnAir()
        c.answerStart(sink)
        c.onEvent(LinkEvent.Disconnected(1006, "gone", revoked = false))
        assertTrue(mic.stopped)
        assertEquals(IntercomState(), c.intercom.value)
    }

    @Test
    fun `the server cutting the stream ends it here without answering`() = runTest {
        val c = controller(this)
        val sink = c.connect()
        c.beginAnnouncement()
        c.tap(sink)
        c.goOnAir()
        c.answerStart(sink)
        val id = sink.ofType("live.start").last().third
        c.receive(ServerMessage.LiveEnd("E1", id))
        advanceUntilIdle()
        assertTrue(mic.stopped)
        assertFalse(c.intercom.value.onAir)
        assertEquals("Olga", c.intercom.value.announcer)
        assertEquals(0, sink.ofType("live.end").size)
    }

    @Test
    fun `the voice of someone else is played, the alarm steps back, and it is never acknowledged`() = runTest {
        val c = controller(this)
        val sink = c.connect()
        c.receive(ServerMessage.LiveStart("L1", "S1", "Olga"))
        c.receive(ServerMessage.LiveChunk("L2", "S1", byteArrayOf(1, 2, 3, 4)))
        c.receive(ServerMessage.LiveEnd("L3", "S1"))
        assertEquals(listOf("start", "stop"), speaker.events)
        assertEquals(1, speaker.written.size)
        assertEquals(listOf(true, false), alarm.ducks)
        assertEquals(0, sink.ofType("ack").size)
    }

    @Test
    fun `a stray chunk with no announcement is dropped`() = runTest {
        val c = controller(this)
        c.connect()
        c.receive(ServerMessage.LiveChunk("L2", "S1", byteArrayOf(1, 2)))
        assertTrue(speaker.written.isEmpty())
    }

    private val bob = CallPeer("D2", "Room B12", true)

    @Test
    fun `the list of terminals is asked for and shown only when it answers the request`() = runTest {
        val c = controller(this)
        val sink = c.connect()
        c.openCallList()
        assertTrue(c.intercom.value.choosingPeer)
        assertNull(c.intercom.value.peers)
        val ref = sink.ofType("call.list").single().third
        c.receive(ServerMessage.CallDirectory("X1", "OTHER", listOf(bob)))
        assertNull(c.intercom.value.peers)
        c.receive(ServerMessage.CallDirectory("X2", ref, listOf(bob)))
        assertEquals(listOf(bob), c.intercom.value.peers)
        c.closeCallList()
        assertFalse(c.intercom.value.choosingPeer)
    }

    @Test
    fun `calling rings, and the microphone opens only when the other side has answered`() = runTest {
        val c = controller(this)
        val sink = c.connect()
        c.callPeer(bob)
        val call = sink.ofType("call.request").single()
        assertEquals("D2", call.second.jsonObject["target"]!!.jsonPrimitive.content)
        assertEquals(CallPhase.CALLING, c.intercom.value.call?.phase)
        assertTrue(mic.feed == null)

        c.receive(ServerMessage.CallState("S1", call.third, "active", "Room B12"))
        assertEquals(CallPhase.ACTIVE, c.intercom.value.call?.phase)
        assertEquals(listOf("start"), callSpeaker.events)
        mic.feed!!(ByteArray(64) { 7 })
        advanceUntilIdle()
        val audio = sink.ofType("call.audio").single().second.jsonObject
        assertEquals(call.third, audio["callId"]!!.jsonPrimitive.content)
        assertContentEquals(ByteArray(64) { 7 }, B64.decode(audio["data"]!!.jsonPrimitive.content))

        c.receive(ServerMessage.CallAudio("A1", call.third, byteArrayOf(1, 2)))
        c.receive(ServerMessage.CallAudio("A2", "OTHERCALL", byteArrayOf(3, 4)))
        assertEquals(1, callSpeaker.written.size)

        c.hangUp()
        advanceUntilIdle()
        assertNull(c.intercom.value.call)
        assertTrue(mic.stopped)
        assertEquals(listOf("start", "stop"), callSpeaker.events)
        assertEquals(call.third, sink.ofType("call.hangup").single().second.jsonObject["callId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an incoming call rings, stays silent until accepted, and declining says so`() = runTest {
        val c = controller(this)
        val sink = c.connect()
        c.receive(ServerMessage.CallIncoming("I1", "CALL1", "Room A01"))
        assertEquals(CallSession("CALL1", "Room A01", CallPhase.INCOMING), c.intercom.value.call)
        assertTrue(mic.feed == null)
        c.answerCall(false)
        assertNull(c.intercom.value.call)
        assertEquals(false, sink.ofType("call.answer").single().second.jsonObject["accept"]!!.jsonPrimitive.content.toBoolean())

        c.receive(ServerMessage.CallIncoming("I2", "CALL2", "Room A01"))
        c.answerCall(true)
        assertEquals(CallPhase.CONNECTING, c.intercom.value.call?.phase)
        assertTrue(mic.feed == null)
        c.receive(ServerMessage.CallState("S2", "CALL2", "active", "Room A01"))
        assertEquals(CallPhase.ACTIVE, c.intercom.value.call?.phase)
        assertTrue(mic.feed != null)
        c.receive(ServerMessage.CallState("S3", "CALL2", "ended", "Room A01"))
        assertNull(c.intercom.value.call)
        assertTrue(mic.stopped)
    }

    @Test
    fun `a call that comes while the terminal is busy is declined without ringing`() = runTest {
        val c = controller(this)
        val sink = c.connect()
        c.beginAnnouncement()
        c.receive(ServerMessage.CallIncoming("I1", "CALL1", "Room A01"))
        assertNull(c.intercom.value.call)
        advanceUntilIdle()
        assertEquals(false, sink.ofType("call.answer").single().second.jsonObject["accept"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `a refused or unanswered call says why, and a lost link ends a call`() = runTest {
        val c = controller(this)
        val sink = c.connect()
        c.callPeer(bob)
        c.receive(ServerMessage.CallState("S1", sink.ofType("call.request").last().third, "declined", "Room B12"))
        assertNull(c.intercom.value.call)
        assertEquals("Room B12 declined the call", c.intercom.value.callNotice)
        c.dismissNotice()

        c.callPeer(bob)
        val id = sink.ofType("call.request").last().third
        c.receive(ServerMessage.CallState("S2", id, "active", "Room B12"))
        c.onEvent(LinkEvent.Disconnected(1006, "gone", revoked = false))
        assertNull(c.intercom.value.call)
        assertTrue(mic.stopped)
    }

    @Test
    fun `a call cannot start while announcing, and an announcement cannot start during a call`() = runTest {
        val c = controller(this)
        val sink = c.connect()
        c.beginAnnouncement()
        c.callPeer(bob)
        assertEquals(0, sink.ofType("call.request").size)
        c.cancelAnnouncement()

        c.callPeer(bob)
        c.beginAnnouncement()
        assertFalse(c.intercom.value.awaitingCard)
    }

    @Test
    fun `the new messages parse`() {
        fun frame(type: String, payload: String) = """{"v":1,"id":"01HZZZZZZZZZZZZZZZZZZZZZZZ","type":"$type","ts":1,"seq":1,"payload":$payload}""".toByteArray()
        val start = parseServerMessage(frame("live.start", """{"streamId":"S","sampleRate":16000,"from":"Olga"}""")).first
        assertEquals("Olga", (start as ServerMessage.LiveStart).from)
        val chunk = parseServerMessage(frame("live.chunk", """{"streamId":"S","data":"AQID"}""")).first as ServerMessage.LiveChunk
        assertContentEquals(byteArrayOf(1, 2, 3), chunk.pcm)
        val broken = parseServerMessage(frame("live.chunk", """{"streamId":"S","data":"***"}""")).first as ServerMessage.LiveChunk
        assertEquals(0, broken.pcm.size)
        val result = parseServerMessage(frame("intercom.result", """{"ref":"R","kind":"announce","ok":true,"deviceCount":4}""")).first as ServerMessage.IntercomResult
        assertEquals(4, result.deviceCount)
        val card = parseServerMessage(frame("card.result", """{"ref":"R","outcome":"maintenance","maintenance":true,"menu":{"statuses":[],"canRevoke":false,"canAnnounce":true}}""")).first as ServerMessage.CardResult
        assertTrue(card.menu!!.canAnnounce)
    }
}
