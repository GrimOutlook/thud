package io.github.avikulin.thud.service.dircon

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class DirConResourcesTest {
    private fun sockets(): Pair<Socket, Socket> =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val sender = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
            sender to listener.accept()
        }

    @Test fun loopbackPreservesSendOrderAndFragments() {
        val (sender, receiver) = sockets()
        receiver.use {
            receiver.soTimeout = 2_000
            val writer = DirConSocketWriter(sender, {}, {})
            try {
                val packets = (1..8).map { DirConPacket(identifier = 4, sequenceNumber = it,
                    payload = ByteArray(it * 100) { it.toByte() }) }
                packets.forEach { writer.send(it.encode()) }
                val input = DirConInput(receiver.getInputStream())
                packets.forEach { assertEquals(it, input.readPacket()) }
            } finally {
                writer.close()
                assertTrue(writer.awaitTermination(2_000))
            }
        }
    }

    @Test fun slowLoopbackReceiverTimesOutWithoutBlockingHealthyReceiver() {
        val (slowSender, slowReceiver) = sockets()
        val (fastSender, fastReceiver) = sockets()
        val closed = CountDownLatch(1)
        slowSender.sendBufferSize = 1024
        slowReceiver.receiveBufferSize = 1024
        val slow = DirConSocketWriter(slowSender, { closed.countDown() }, {}, 500, 64)
        val fast = DirConSocketWriter(fastSender, {}, {})
        try {
            val data = DirConPacket(identifier = 4, payload = ByteArray(65535)).encode()
            repeat(32) { slow.send(data) } // More than the peer can buffer; peer never reads.
            fastReceiver.soTimeout = 2_000
            val packet = DirConPacket(identifier = 1)
            fast.send(packet.encode())
            assertEquals(packet, DirConInput(fastReceiver.getInputStream()).readPacket())
            assertTrue("watchdog must close blocked sender", closed.await(3, TimeUnit.SECONDS))
            assertTrue("blocked writer must exit", slow.awaitTermination(2_000))
            assertTrue(slowSender.isClosed)
        } finally {
            slow.close()
            fast.close()
            slowReceiver.close()
            fastReceiver.close()
            assertTrue(fast.awaitTermination(2_000))
        }
    }

    @Test fun queueOverflowClosesWithoutWaitingForWriteMonitor() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val socket = object : Socket() {
            override fun getOutputStream() = object : OutputStream() {
                override fun write(b: Int) { error("bulk writes only") }
                override fun write(b: ByteArray) {
                    writing.countDown()
                    try {
                        release.await(3, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        throw java.io.IOException("closed")
                    }
                }
            }
            override fun close() { closes.incrementAndGet(); release.countDown() }
        }
        val writer = DirConSocketWriter(socket, {}, {}, 2_000, 1)
        try {
            writer.send(byteArrayOf(1))
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            writer.send(byteArrayOf(2))
            writer.send(byteArrayOf(3))
            assertTrue(writer.awaitTermination(2_000))
            writer.close()
            assertEquals(1, closes.get())
        } finally { writer.close() }
    }

    @Test fun concurrentSlotsAreBoundedAndReleaseExactlyOnce() {
        val slots = DirConConnectionSlots(8)
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val attempts = (1..32).map {
            pool.submit<Pair<Socket, DirConConnectionSlots.Lease?>> {
                start.await()
                val socket = Socket()
                socket to slots.acquire(socket)
            }
        }
        try {
            start.countDown()
            val results = attempts.map { it.get(3, TimeUnit.SECONDS) }
            assertEquals(8, results.count { it.second != null })
            results.filter { it.second == null }.forEach { assertTrue(it.first.isClosed) }
            results.forEach { (socket, lease) -> socket.close(); lease?.close(); lease?.close() }
            val leases = (1..8).map { slots.acquire(Socket()) }
            assertTrue(leases.all { it != null })
            assertNull(slots.acquire(Socket())) // Double release must not manufacture extra slots.
            leases.forEach { it?.close() }
        } finally { pool.shutdownNow() }
    }

    @Test fun rejectedLoopbackSocketIsClosedBeforeHandlerAllocation() {
        val slots = DirConConnectionSlots(1)
        val first = slots.acquire(Socket())!!
        val (sender, peer) = sockets()
        try {
            assertNull(slots.acquire(sender))
            peer.soTimeout = 2_000
            assertEquals(-1, peer.getInputStream().read())
            first.close()
            slots.acquire(Socket())!!.close()
        } finally { sender.close(); peer.close(); first.close() }
    }

    @Test fun loopbackFragmentationCoalescingAndTruncatedEof() {
        val (sender, receiver) = sockets()
        try {
            receiver.soTimeout = 2_000
            val packet = DirConPacket(identifier = 4, payload = ByteArray(20) { it.toByte() })
            val bytes = packet.encode()
            // Deliberate one-byte writes, followed by two frames in one write, then partial EOF.
            bytes.forEach { sender.getOutputStream().write(it.toInt()) }
            sender.getOutputStream().write(bytes + bytes + bytes.copyOf(5))
            sender.shutdownOutput()
            val reader = DirConInput(receiver.getInputStream())
            repeat(3) { assertEquals(packet, reader.readPacket()) }
            assertThrows(java.net.ProtocolException::class.java) { reader.readPacket() }
        } finally { sender.close(); receiver.close() }
    }
}
