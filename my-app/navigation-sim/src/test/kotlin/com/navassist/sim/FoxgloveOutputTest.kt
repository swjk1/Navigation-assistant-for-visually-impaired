package com.navassist.sim

import com.navassist.sim.foxglove.FoxglovePublisher
import com.navassist.sim.foxglove.McapSink
import com.navassist.sim.foxglove.McapWriter
import com.navassist.sim.foxglove.MiniJson
import com.navassist.sim.foxglove.json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural checks of what goes to Foxglove. (The recordings were also validated with the official
 * `mcap` Python reader and Foxglove's JSON Schemas when this was written; this keeps the format
 * honest in CI without either.)
 */
class FoxgloveOutputTest {

    private data class Record(val opcode: Int, val content: ByteBuffer)

    /** Reads an unchunked MCAP back into records, checking the framing on the way. */
    private fun records(bytes: ByteArray): List<Record> {
        val magic = byteArrayOf(0x89.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte(), 0x30, '\r'.code.toByte(), '\n'.code.toByte())
        assertTrue(bytes.copyOfRange(0, 8).contentEquals(magic), "leading magic")
        assertTrue(bytes.copyOfRange(bytes.size - 8, bytes.size).contentEquals(magic), "trailing magic")
        val buffer = ByteBuffer.wrap(bytes, 8, bytes.size - 16).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<Record>()
        while (buffer.hasRemaining()) {
            val opcode = buffer.get().toInt() and 0xFF
            val length = buffer.long.toInt()
            val content = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).limit(length) as ByteBuffer
            buffer.position(buffer.position() + length)
            out += Record(opcode, content)
        }
        return out
    }

    @Test
    fun `an MCAP recording of a run is framed correctly and every channel has a schema`() {
        val bytes = ByteArrayOutputStream()
        val sim = Simulation(Scenarios.named("corridor-explore").settings(1).copy(maxSeconds = 12.0))
        McapSink(McapWriter(bytes)).use { sink ->
            val publisher = FoxglovePublisher(sink)
            sim.listeners += publisher::onFrame
            sim.run()
        }
        val records = records(bytes.toByteArray())

        assertEquals(0x01, records.first().opcode, "header first")
        assertEquals(listOf(0x0F, 0x02), records.takeLast(2).map { it.opcode }, "data end, then footer")

        val schemaIds = records.filter { it.opcode == 0x03 }.map { it.content.short.toInt() }.toSet()
        val channels = records.filter { it.opcode == 0x04 }.associate { r ->
            val id = r.content.short.toInt()
            val schema = r.content.short.toInt()
            val topicLength = r.content.int
            val topic = ByteArray(topicLength).also { r.content.get(it) }.toString(Charsets.UTF_8)
            id to (schema to topic)
        }
        assertTrue(channels.values.all { it.first in schemaIds }, "every channel refers to a schema")
        assertEquals(10, channels.size) // the base topics, /lidar/points included (declared even when no LiDAR publishes)

        val messages = records.filter { it.opcode == 0x05 }
        val perTopic = messages.groupingBy { channels.getValue(it.content.getShort(0).toInt()).second }.eachCount()
        assertTrue(perTopic.keys.containsAll(listOf("/world", "/user", "/engine/grid", "/engine/status")), "$perTopic")
        // A message's payload starts after channel(2) + sequence(4) + log time(8) + publish time(8).
        val first = messages.first().content
        first.position(22)
        val payload = ByteArray(first.remaining()).also { first.get(it) }.toString(Charsets.UTF_8)
        assertTrue(MiniJson.parse(payload) is Map<*, *>, "payloads are JSON objects")
    }

    @Test
    fun `json output escapes what it must and survives a round trip`() {
        val text = json {
            "label" to "Room \"314\"\nnext"
            "n" to 1.5
            "flag" to true
            "nothing" to null
        }
        val parsed = MiniJson.parse(text) as Map<*, *>
        assertEquals("Room \"314\"\nnext", parsed["label"])
        assertEquals(1.5, parsed["n"])
        assertEquals(true, parsed["flag"])
        assertTrue(parsed.containsKey("nothing") && parsed["nothing"] == null)
    }

    @Test
    fun `client messages from Foxglove parse`() {
        val subscribe = MiniJson.parse("""{"op":"subscribe","subscriptions":[{"id":1,"channelId":3}]}""") as Map<*, *>
        val sub = (subscribe["subscriptions"] as List<*>).single() as Map<*, *>
        assertEquals(3.0, sub["channelId"])
        val twist = MiniJson.parse("""{ "linear": {"x": 0.8, "y": 0, "z": 0}, "angular": {"x": 0, "y": 0, "z": -1.2e0} }""") as Map<*, *>
        assertEquals(-1.2, (twist["angular"] as Map<*, *>)["z"])
    }
}
