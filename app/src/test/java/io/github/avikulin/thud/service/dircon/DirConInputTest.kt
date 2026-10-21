package io.github.avikulin.thud.service.dircon

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.ProtocolException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class DirConInputTest {
    @Test fun fragmentedAndCoalescedFrames() {
        val packets = listOf(DirConPacket(identifier = 1),
            DirConPacket(identifier = 4, payload = ByteArray(65535) { it.toByte() }))
        val bytes = ByteArrayInputStream(packets.flatMap { it.encode().toList() }.toByteArray())
        val fragmented = object : InputStream() {
            override fun read() = bytes.read()
            override fun read(b: ByteArray, off: Int, len: Int) = bytes.read(b, off, minOf(len, 1))
        }
        val reader = DirConInput(fragmented)
        packets.forEach { assertEquals(it, reader.readPacket()) }
        assertNull(reader.readPacket())
    }

    @Test fun everySplitPreservesPackets() {
        val packet = DirConPacket(identifier = 4, payload = ByteArray(32) { it.toByte() })
        val encoded = packet.encode()
        for (split in 1 until encoded.size) {
            val bytes = ByteArrayInputStream(encoded + encoded)
            var first = true
            val reader = DirConInput(object : InputStream() {
                override fun read() = bytes.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = bytes.read(b, off, if (first) minOf(split, len) else len)
                    first = false
                    return n
                }
            })
            assertEquals(packet, reader.readPacket())
            assertEquals(packet, reader.readPacket())
        }
    }

    @Test fun malformedVersionRejectedBeforeBody() {
        assertThrows(ProtocolException::class.java) {
            DirConInput(ByteArrayInputStream(byteArrayOf(2))).readPacket()
        }
    }

    @Test fun truncatedFramesRejected() {
        val encoded = DirConPacket(identifier = 4, payload = byteArrayOf(1, 2)).encode()
        for (size in 1 until encoded.size) {
            assertThrows(ProtocolException::class.java) {
                DirConInput(ByteArrayInputStream(encoded.copyOf(size))).readPacket()
            }
        }
    }

    @Test fun trickleCannotExtendFrameDeadline() {
        var now = 0L
        val bytes = ByteArrayInputStream(DirConPacket(identifier = 4, payload = ByteArray(100)).encode())
        val reader = DirConInput(object : InputStream() {
            override fun read() = bytes.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                now += 5_000_000_000L
                return bytes.read(b, off, 1)
            }
        }, nanoTime = { now })
        assertThrows(SocketTimeoutException::class.java) { reader.readPacket() }
    }

    @Test fun encodeRejectsUnrepresentableLength() {
        assertThrows(IllegalArgumentException::class.java) {
            DirConPacket(identifier = 4, payload = ByteArray(65536)).encode()
        }
    }

    @Test fun idleDeadlineAndOutboundActivity() {
        var now = 0L
        var activity = 0L
        var polls = 0
        val reader = DirConInput(object : InputStream() {
            override fun read(): Int = error("bulk reads only")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                now += 1_000_000_000L
                polls++
                if (polls == 100) activity = now // A successful notification extends idle lifetime.
                throw SocketTimeoutException()
            }
        }, nanoTime = { now }, lastActivity = { activity })
        assertThrows(SocketTimeoutException::class.java) { reader.readPacket() }
        assertEquals(220, polls)
    }

    @Test fun parserDistinguishesIncompleteAndMalformedAndHonorsRange() {
        val bytes = DirConPacket(identifier = 4, payload = byteArrayOf(9)).encode()
        assertNull(DirConPacket.parse(bytes, length = 6))
        assertEquals(DirConPacket.parse(bytes), DirConPacket.parse(byteArrayOf(0) + bytes, 1))
        assertThrows(IllegalArgumentException::class.java) { DirConPacket.parse(bytes, -1) }
        assertThrows(ProtocolException::class.java) { DirConPacket.parse(byteArrayOf(0)) }
    }
}
