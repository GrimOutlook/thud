package io.github.avikulin.thud.service.dircon

import java.io.InputStream
import java.net.ProtocolException
import java.net.SocketTimeoutException

/** One reusable wire-sized buffer; reads never consume bytes belonging to the next frame. */
internal class DirConInput(
    private val input: InputStream,
    private val nanoTime: () -> Long = System::nanoTime,
    private val lastActivity: (() -> Long)? = null,
    private val beforeRead: (Int) -> Unit = {}
) {
    companion object {
        const val FRAME_TIMEOUT_MS = 15_000L
        const val IDLE_TIMEOUT_MS = 120_000L
    }

    private val buffer = ByteArray(DirConPacket.MAX_FRAME_SIZE)
    private val createdAt = nanoTime()

    fun readPacket(): DirConPacket? {
        var size = 0
        var target = 1 // Reject invalid versions without waiting for the rest of the header.
        var frameStart = 0L
        while (true) {
            val now = nanoTime()
            val remaining = if (size == 0) {
                IDLE_TIMEOUT_MS - (now - (lastActivity?.invoke() ?: createdAt)) / 1_000_000L
            } else {
                FRAME_TIMEOUT_MS - (now - frameStart) / 1_000_000L
            }
            if (remaining <= 0) throw SocketTimeoutException("DirCon input deadline exceeded")
            // Poll idle connections so successful outbound notifications can refresh activity.
            beforeRead(minOf(remaining, 1000L).toInt())
            val count = try {
                input.read(buffer, size, target - size)
            } catch (e: SocketTimeoutException) {
                continue
            }
            if (count < 0) {
                if (size == 0) return null
                throw ProtocolException("Truncated DirCon frame")
            }
            if (count == 0) continue
            if (size == 0) frameStart = nanoTime()
            size += count
            if (nanoTime() - frameStart >= FRAME_TIMEOUT_MS * 1_000_000L) {
                throw SocketTimeoutException("DirCon frame deadline exceeded")
            }
            if (size < target) continue
            val frameSize = DirConPacket.frameSize(buffer, length = size)
            if (target == 1) {
                target = DirConPacket.HEADER_SIZE
            } else if (target == DirConPacket.HEADER_SIZE) {
                target = checkNotNull(frameSize)
                if (target == size) return DirConPacket.parse(buffer, length = size)
            } else {
                return DirConPacket.parse(buffer, length = size)
            }
        }
    }
}
