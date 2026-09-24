package com.navassist.sim.foxglove

import java.util.Base64

/**
 * Just enough JSON writing for Foxglove messages, so the simulator needs no JSON library.
 *
 *   json { "frame_id" to "engine"; "size" to obj { "x" to 1.0 } }
 */
class JsonObject internal constructor() {
    internal val sb = StringBuilder("{")
    private var first = true

    private fun key(name: String) {
        if (!first) sb.append(',')
        first = false
        quote(sb, name)
        sb.append(':')
    }

    infix fun String.to(value: String?) {
        key(this)
        if (value == null) sb.append("null") else quote(sb, value)
    }

    infix fun String.to(value: Number) {
        key(this)
        appendNumber(sb, value)
    }

    infix fun String.to(value: Boolean) {
        key(this)
        sb.append(value)
    }

    infix fun String.to(value: JsonObject) {
        key(this)
        sb.append(value.close())
    }

    infix fun String.to(value: JsonArray) {
        key(this)
        sb.append(value.close())
    }

    /** Raw bytes, as Foxglove's JSON encoding wants them: base64. */
    infix fun String.bytes(value: ByteArray) {
        key(this)
        quote(sb, Base64.getEncoder().encodeToString(value))
    }

    internal fun close(): String = sb.append('}').toString()
}

class JsonArray internal constructor() {
    internal val sb = StringBuilder("[")
    private var first = true

    private fun comma() {
        if (!first) sb.append(',')
        first = false
    }

    fun add(value: JsonObject) { comma(); sb.append(value.close()) }
    fun add(value: Number) { comma(); appendNumber(sb, value) }
    fun add(value: String) { comma(); quote(sb, value) }

    internal fun close(): String = sb.append(']').toString()
}

fun obj(build: JsonObject.() -> Unit): JsonObject = JsonObject().apply(build)

fun arr(build: JsonArray.() -> Unit): JsonArray = JsonArray().apply(build)

fun json(build: JsonObject.() -> Unit): String = obj(build).close()

private fun appendNumber(sb: StringBuilder, value: Number) {
    when (value) {
        is Float -> if (value.isFinite()) sb.append(value) else sb.append(0)
        is Double -> if (value.isFinite()) sb.append(value) else sb.append(0)
        else -> sb.append(value)
    }
}

private fun quote(sb: StringBuilder, value: String) {
    sb.append('"')
    for (c in value) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
    }
    sb.append('"')
}
