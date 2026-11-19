package com.game4399.app.webview

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class Seer2SocketRelayTest {
    @Test fun relaysLoginAndAdvertisedWorldTrafficThroughSeparateSockets() {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { login ->
            ServerSocket(0, 1, loopback).use { world ->
                login.soTimeout = 2000
                world.soTimeout = 2000
                val events = LinkedBlockingQueue<Seer2SocketRelay.Event>()
                Seer2SocketRelay { socket, address ->
                    socket.sendBufferSize = 4096
                    socket.connect(InetSocketAddress(loopback,
                        if (address.port == 1863) login.localPort else world.localPort), 1000)
                }.use { relay ->
                    assertFalse(relay.open(1, "8.8.8.8", 1863, events::add))
                    relay.rememberLoginAddress("8.8.8.8:1863\r\n".toByteArray())
                    assertTrue(relay.open(1, "8.8.8.8", 1863, events::add))
                    login.accept().use { peer ->
                        peer.soTimeout = 2000
                        assertEquals("open", events.poll(2, TimeUnit.SECONDS)?.type)
                        val request = byteArrayOf(0, 0x80.toByte(), 3)
                        assertTrue(relay.send(1, request))
                        assertArrayEquals(request, ByteArray(request.size).also { DataInputStream(peer.getInputStream()).readFully(it) })
                        val response = worldListPacket("1.1.1.1", 1864)
                        peer.getOutputStream().write(response, 0, 3)
                        assertArrayEquals(response.copyOfRange(0, 3), events.poll(2, TimeUnit.SECONDS)?.data)
                        assertFalse(relay.open(2, "1.1.1.1", 1864, events::add))
                        peer.getOutputStream().write(response, 3, response.size - 3)
                        val received = java.io.ByteArrayOutputStream()
                        while (received.size() < response.size - 3) {
                            received.write(checkNotNull(events.poll(2, TimeUnit.SECONDS)?.data))
                        }
                        assertArrayEquals(response.copyOfRange(3, response.size), received.toByteArray())
                        assertFalse(relay.open(2, "1.0.0.1", 1864, events::add))
                        assertTrue(relay.open(2, "1.1.1.1", 1864, events::add))
                        world.accept().use { worldPeer ->
                            assertEquals("open", events.poll(2, TimeUnit.SECONDS)?.type)
                            worldPeer.getOutputStream().write(byteArrayOf(7, 8))
                            assertArrayEquals(byteArrayOf(7, 8), events.poll(2, TimeUnit.SECONDS)?.data)
                            val chunk = ByteArray(16 * 1024) { (it % 251).toByte() }
                            repeat(20) { assertTrue(relay.send(2, chunk)) }
                            relay.disconnect(2)
                            worldPeer.soTimeout = 2000
                            val incoming = DataInputStream(worldPeer.getInputStream())
                            repeat(20) {
                                val bytes = ByteArray(chunk.size).also(incoming::readFully)
                                assertArrayEquals(chunk, bytes)
                            }
                            assertEquals(-1, worldPeer.getInputStream().read())
                        }
                    }
                }
            }
        }
    }

    @Test fun neverAllowsLoopbackOrPrivateAddressesFromDiscovery() {
        Seer2SocketRelay { _, _ -> error("Private targets must never reach the socket dialer") }.use { relay ->
            for (host in listOf("127.0.0.1", "10.1.2.3", "169.254.169.254", "192.168.1.1", "100.64.0.1")) {
                relay.rememberLoginAddress("$host:1863".toByteArray())
                assertFalse(relay.open(1, host, 1863) {})
            }
        }
    }

    private fun worldListPacket(host: String, port: Int): ByteArray {
        val length = 18 + 4 + 4 + 26 + 1
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(length).putShort(105).putInt(0).putInt(0).putInt(0)
            putInt(100).putInt(1).putShort(5)
            put(host.toByteArray().copyOf(16)).putShort(port.toShort()).putInt(10).put(0).put(0).put(0)
        }.array()
    }
}
