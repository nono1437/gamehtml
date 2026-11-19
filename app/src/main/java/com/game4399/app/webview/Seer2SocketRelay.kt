package com.game4399.app.webview

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors

/** Carries binary Flash socket data for a single official game page. */
internal class Seer2SocketRelay(
    private val dial: (Socket, Address) -> Unit = { socket, address ->
        socket.connect(InetSocketAddress(address.host, address.port), CONNECT_TIMEOUT)
    }
) : Closeable {
    data class Address(val host: String, val port: Int)
    data class Event(val type: String, val data: ByteArray? = null)

    private val loginAddresses = mutableSetOf<Address>()
    private val worldAddresses = mutableSetOf<Address>()
    private val connections = mutableMapOf<Int, Connection>()
    private val workers = Executors.newFixedThreadPool(MAX_CONNECTIONS * 2)
    private var closed = false

    @Synchronized fun rememberLoginAddress(bytes: ByteArray) {
        if (closed || loginAddresses.size >= 24 || !Seer2Assets.validPayload("/ip.txt", bytes)) return
        val value = bytes.toString(Charsets.UTF_8).trim()
        publicAddress(value.substringBefore(':'), value.substringAfter(':').toInt())?.let(loginAddresses::add)
    }

    @Synchronized fun open(id: Int, host: String, port: Int, publish: (Event) -> Unit): Boolean {
        if (closed || id <= 0 || id in connections || connections.size >= MAX_CONNECTIONS) return false
        val address = publicAddress(host, port) ?: return false
        val login = address in loginAddresses
        if (!login && address !in worldAddresses) return false
        val connection = Connection(id, address, login, publish)
        connections[id] = connection
        workers.execute(connection::read)
        return true
    }

    @Synchronized fun send(id: Int, data: ByteArray): Boolean {
        val connection = connections[id] ?: return false
        if (connection.closing) return false
        if (data.size in 1..CHUNK_BYTES && connection.enqueue(data)) return true
        connection.stop()
        return false
    }

    @Synchronized fun disconnect(id: Int) {
        connections[id]?.finish()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        connections.values.forEach(Connection::stop)
        connections.clear()
        loginAddresses.clear()
        worldAddresses.clear()
        workers.shutdownNow()
    }

    @Synchronized private fun rememberWorldAddresses(packet: ByteArray) {
        if (closed) return
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.getShort(4).toInt()
        if (command !in 105..106 || buffer.getInt(14) != 0) return
        // 105 starts with the total number of servers, then the same list format as 106.
        val list = HEADER_BYTES + if (command == 105) Int.SIZE_BYTES else 0
        if (packet.size < list + Int.SIZE_BYTES) return
        val count = buffer.getInt(list)
        if (count !in 0..(packet.size - list - Int.SIZE_BYTES) / WORLD_ENTRY_BYTES) return
        buffer.position(list + Int.SIZE_BYTES)
        val ip = ByteArray(16)
        repeat(count) {
            buffer.short // server ID
            buffer.get(ip)
            val port = buffer.short.toInt() and 0xffff
            buffer.position(buffer.position() + 6) // user count and two flags
            val host = ip.toString(Charsets.US_ASCII).trimEnd('\u0000', ' ')
            if (worldAddresses.size < 4096) publicAddress(host, port)?.let(worldAddresses::add)
        }
    }

    private inner class Connection(
        private val id: Int,
        private val address: Address,
        login: Boolean,
        private val publish: (Event) -> Unit
    ) {
        private val socket = Socket()
        private val writes = ArrayBlockingQueue<ByteArray>(64)
        private val frames = if (login) LoginFrames(::rememberWorldAddresses) else null
        @Volatile var closing = false
            private set
        private var opened = false

        fun enqueue(data: ByteArray): Boolean = writes.offer(data)

        fun read() {
            var outcome = "close"
            try {
                dial(socket, address)
                socket.tcpNoDelay = true
                // Submit the writer while the relay still owns and can cancel this connection.
                synchronized(this@Seer2SocketRelay) {
                    if (connections[id] !== this || closing) return
                    workers.execute(::write)
                    opened = true
                }
                publish(Event("open"))
                val input = socket.getInputStream()
                val buffer = ByteArray(CHUNK_BYTES)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    frames?.accept(buffer, size)
                    publish(Event("data", buffer.copyOf(size)))
                }
            } catch (_: IOException) {
                if (!closing) outcome = "error"
            } finally {
                stop()
                val ended = synchronized(this@Seer2SocketRelay) {
                    if (connections[id] === this) connections.remove(id) else null
                }
                if (ended != null) publish(Event(outcome))
            }
        }

        private fun write() {
            try {
                val output = socket.getOutputStream()
                while (true) {
                    val data = writes.take()
                    if (data.isEmpty()) {
                        stop()
                        return
                    }
                    output.write(data)
                }
            } catch (_: IOException) {
                stop()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        fun finish() {
            if (closing) return
            closing = true
            if (!opened || !writes.offer(ByteArray(0))) stop()
        }

        fun stop() {
            runCatching { socket.close() }
            writes.clear()
            writes.offer(ByteArray(0))
        }
    }

    /** Reads login reply framing without modifying any bytes passed to the game. */
    private class LoginFrames(private val acceptPacket: (ByteArray) -> Unit) {
        private var packet = ByteArray(HEADER_BYTES)
        private var written = 0
        private var valid = true

        fun accept(data: ByteArray, size: Int) {
            var offset = 0
            while (valid && offset < size) {
                val copy = minOf(packet.size - written, size - offset)
                data.copyInto(packet, written, offset, offset + copy)
                written += copy
                offset += copy
                if (written < packet.size) continue
                if (packet.size == HEADER_BYTES) {
                    val length = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).int
                    if (length !in HEADER_BYTES..MAX_PACKET_BYTES) {
                        valid = false
                        return
                    }
                    if (length > HEADER_BYTES) {
                        packet = packet.copyOf(length)
                        continue
                    }
                }
                acceptPacket(packet)
                packet = ByteArray(HEADER_BYTES)
                written = 0
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT = 8000
        const val MAX_CONNECTIONS = 4
        const val HEADER_BYTES = 18
        const val WORLD_ENTRY_BYTES = 26
        const val MAX_PACKET_BYTES = 1024 * 1024
        const val CHUNK_BYTES = 16 * 1024

        fun publicAddress(host: String, port: Int): Address? {
            if (port !in 1..65535 || host.length > 15) return null
            val parts = host.split('.')
            if (parts.size != 4) return null
            val octets = parts.map { it.toIntOrNull()?.takeIf { part -> part in 0..255 && it == "$part" } ?: return null }
            val (first, second, third) = octets
            if (first !in 1..223 || first == 100 && second in 64..127 ||
                first == 192 && second == 0 && third in setOf(0, 2) ||
                first == 198 && (second in 18..19 || second == 51 && third == 100) ||
                first == 203 && second == 0 && third == 113) return null
            val ip = InetAddress.getByAddress(octets.map(Int::toByte).toByteArray())
            if (ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress) return null
            return Address(host, port)
        }
    }
}
