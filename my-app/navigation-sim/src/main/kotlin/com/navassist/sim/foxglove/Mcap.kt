package com.navassist.sim.foxglove

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A minimal MCAP writer (https://mcap.dev/spec): header, schemas, channels and messages, no chunks
 * and no summary section. The spec makes both optional; Foxglove and the official readers open such
 * files by scanning them. There is no JVM MCAP library, and this needs a few dozen lines.
 */
class McapWriter(private val out: OutputStream) : AutoCloseable {

    private var nextSchemaId = 1
    private var nextChannelId = 0
    private val sequences = HashMap<Int, Int>()

    init {
        out.write(MAGIC)
        record(OP_HEADER) {
            string("")
            string("navassist-sim")
        }
    }

    /** Registers a schema and returns its id. */
    fun schema(name: String, encoding: String, data: ByteArray): Int {
        val id = nextSchemaId++
        record(OP_SCHEMA) {
            u16(id)
            string(name)
            string(encoding)
            u32(data.size)
            bytes(data)
        }
        return id
    }

    /** Registers a channel and returns its id. */
    fun channel(schemaId: Int, topic: String, messageEncoding: String): Int {
        val id = nextChannelId++
        record(OP_CHANNEL) {
            u16(id)
            u16(schemaId)
            string(topic)
            string(messageEncoding)
            u32(0) // empty metadata map
        }
        return id
    }

    fun message(channelId: Int, timeNanos: Long, data: ByteArray) {
        val sequence = sequences.getOrDefault(channelId, 0)
        sequences[channelId] = sequence + 1
        record(OP_MESSAGE) {
            u16(channelId)
            u32(sequence)
            u64(timeNanos)
            u64(timeNanos)
            bytes(data)
        }
    }

    override fun close() {
        record(OP_DATA_END) { u32(0) }
        record(OP_FOOTER) {
            u64(0)
            u64(0)
            u32(0)
        }
        out.write(MAGIC)
        out.close()
    }

    private class Fields {
        val buffer = ByteArrayOutputStream()
        private val scratch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

        fun u16(value: Int) = put(2) { scratch.putShort(value.toShort()) }
        fun u32(value: Int) = put(4) { scratch.putInt(value) }
        fun u64(value: Long) = put(8) { scratch.putLong(value) }
        fun bytes(value: ByteArray) = buffer.write(value)
        fun string(value: String) {
            val utf8 = value.toByteArray(Charsets.UTF_8)
            u32(utf8.size)
            buffer.write(utf8)
        }

        private inline fun put(size: Int, write: () -> Unit) {
            scratch.clear()
            write()
            buffer.write(scratch.array(), 0, size)
        }
    }

    private inline fun record(opcode: Int, build: Fields.() -> Unit) {
        val fields = Fields().apply(build)
        out.write(opcode)
        val length = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(fields.buffer.size().toLong())
        out.write(length.array())
        fields.buffer.writeTo(out)
    }

    companion object {
        private val MAGIC = byteArrayOf(0x89.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte(), 0x30, '\r'.code.toByte(), '\n'.code.toByte())
        private const val OP_HEADER = 0x01
        private const val OP_FOOTER = 0x02
        private const val OP_SCHEMA = 0x03
        private const val OP_CHANNEL = 0x04
        private const val OP_MESSAGE = 0x05
        private const val OP_DATA_END = 0x0F

        fun open(file: File): McapWriter {
            file.parentFile?.mkdirs()
            return McapWriter(BufferedOutputStream(file.outputStream(), 1 shl 16))
        }
    }
}
