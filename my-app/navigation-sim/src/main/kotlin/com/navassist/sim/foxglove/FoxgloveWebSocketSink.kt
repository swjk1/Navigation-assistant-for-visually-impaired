package com.navassist.sim.foxglove

import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.protocols.Protocol
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * A Foxglove WebSocket server (protocol `foxglove.websocket.v1`) that publishes whatever a
 * [FoxglovePublisher] writes to it. Open Foxglove -> "Open connection" -> Foxglove WebSocket ->
 * ws://localhost:PORT.
 *
 * Clients may also publish (capability `clientPublish`, JSON): messages on any topic arrive at
 * [onClientMessage], which the live runner uses to let a person drive the virtual user.
 */
class FoxgloveWebSocketSink(
    port: Int,
    private val onClientMessage: (topic: String, payload: Map<String, Any?>) -> Unit = { _, _ -> },
) : FoxgloveSink {

    private data class Channel(val id: Int, val topic: String, val schemaName: String, val schema: String)

    private val channels = ArrayList<Channel>()

    /** Per client: subscription id -> channel id. */
    private val subscriptions = ConcurrentHashMap<WebSocket, ConcurrentHashMap<Int, Int>>()

    /** Per client: client channel id -> topic. */
    private val clientChannels = ConcurrentHashMap<WebSocket, ConcurrentHashMap<Int, String>>()

    private val server = object : WebSocketServer(
        InetSocketAddress(port),
        listOf(Draft_6455(emptyList(), listOf(Protocol(SUBPROTOCOL)))),
    ) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            subscriptions[conn] = ConcurrentHashMap()
            clientChannels[conn] = ConcurrentHashMap()
            conn.send(json {
                "op" to "serverInfo"
                "name" to "navassist-sim"
                "capabilities" to arr { add("clientPublish"); add("time") }
                "supportedEncodings" to arr { add("json") }
                "metadata" to obj {}
            })
            conn.send(advertisement(synchronized(channels) { channels.toList() }))
            connected = true
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            subscriptions.remove(conn)
            clientChannels.remove(conn)
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val msg = runCatching { MiniJson.parse(message) as? Map<*, *> }.getOrNull() ?: return
            when (msg["op"]) {
                "subscribe" -> (msg["subscriptions"] as? List<*>)?.forEach { entry ->
                    val sub = entry as? Map<*, *> ?: return@forEach
                    val id = (sub["id"] as? Double)?.toInt() ?: return@forEach
                    val channel = (sub["channelId"] as? Double)?.toInt() ?: return@forEach
                    subscriptions[conn]?.put(id, channel)
                }
                "unsubscribe" -> (msg["subscriptionIds"] as? List<*>)?.forEach { id ->
                    (id as? Double)?.toInt()?.let { subscriptions[conn]?.remove(it) }
                }
                "advertise" -> (msg["channels"] as? List<*>)?.forEach { entry ->
                    val ch = entry as? Map<*, *> ?: return@forEach
                    val id = (ch["id"] as? Double)?.toInt() ?: return@forEach
                    val topic = ch["topic"] as? String ?: return@forEach
                    clientChannels[conn]?.put(id, topic)
                }
                "unadvertise" -> (msg["channelIds"] as? List<*>)?.forEach { id ->
                    (id as? Double)?.toInt()?.let { clientChannels[conn]?.remove(it) }
                }
            }
        }

        override fun onMessage(conn: WebSocket, message: ByteBuffer) {
            message.order(ByteOrder.LITTLE_ENDIAN)
            if (message.remaining() < 5 || message.get() != OP_CLIENT_MESSAGE) return
            val topic = clientChannels[conn]?.get(message.int) ?: return
            val bytes = ByteArray(message.remaining()).also { message.get(it) }
            val payload = runCatching { MiniJson.parse(String(bytes, Charsets.UTF_8)) as? Map<String, Any?> }.getOrNull()
                ?: return
            onClientMessage(topic, payload)
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            System.err.println("foxglove websocket: ${ex.message}")
        }

        override fun onStart() {
            println("Foxglove: open a Foxglove WebSocket connection to ws://localhost:$port")
        }
    }

    /** True once any client has connected. */
    @Volatile
    var connected = false
        private set

    init {
        server.isReuseAddr = true
        server.start()
    }

    override fun channel(topic: String, schemaName: String, jsonSchema: String): Int {
        val channel = synchronized(channels) {
            Channel(channels.size + 1, topic, schemaName, jsonSchema).also { channels += it }
        }
        // Clients already connected learn about it now; later ones get it in their first advertise.
        val message = advertisement(listOf(channel))
        for (conn in subscriptions.keys) if (conn.isOpen) runCatching { conn.send(message) }
        return channel.id
    }

    private fun advertisement(list: List<Channel>) = json {
        "op" to "advertise"
        "channels" to arr {
            for (c in list) add(obj {
                "id" to c.id
                "topic" to c.topic
                "encoding" to "json"
                "schemaName" to c.schemaName
                "schema" to c.schema
                "schemaEncoding" to "jsonschema"
            })
        }
    }

    override fun publish(channel: Int, timeNanos: Long, jsonPayload: String) {
        if (subscriptions.isEmpty()) return
        val payload = jsonPayload.toByteArray(Charsets.UTF_8)
        for ((conn, subs) in subscriptions) {
            for ((subscriptionId, channelId) in subs) {
                if (channelId != channel || !conn.isOpen) continue
                val frame = ByteBuffer.allocate(13 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
                frame.put(OP_MESSAGE_DATA).putInt(subscriptionId).putLong(timeNanos).put(payload)
                frame.flip()
                runCatching { conn.send(frame) }
            }
        }
    }

    /** Tells clients what time it is in the simulation (capability `time`). */
    fun time(timeNanos: Long) {
        val frame = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).put(OP_TIME).putLong(timeNanos)
        frame.flip()
        for (conn in subscriptions.keys) if (conn.isOpen) runCatching { conn.send(frame.duplicate()) }
    }

    override fun close() {
        server.stop(1000)
    }

    private companion object {
        const val SUBPROTOCOL = "foxglove.websocket.v1"
        const val OP_MESSAGE_DATA: Byte = 0x01
        const val OP_TIME: Byte = 0x02
        const val OP_CLIENT_MESSAGE: Byte = 0x01
    }
}
