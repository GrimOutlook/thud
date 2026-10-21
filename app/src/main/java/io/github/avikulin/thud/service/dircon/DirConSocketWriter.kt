package io.github.avikulin.thud.service.dircon

import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded, ordered sending. No caller (including the notification loop) waits for network IO. */
internal class DirConSocketWriter(
    private val socket: Socket,
    private val onClose: () -> Unit,
    private val onSent: () -> Unit,
    private val timeoutMs: Long = 5_000,
    queueCapacity: Int = 16
) {
    companion object {
        private val watchdog = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "DirCon-write-watchdog").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }

    private val closed = AtomicBoolean()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(queueCapacity), { task ->
            Thread(task, "DirCon-writer").apply { isDaemon = true }
        })

    fun send(data: ByteArray) {
        require(data.size <= DirConPacket.MAX_FRAME_SIZE)
        if (closed.get()) return
        try {
            worker.execute {
                if (!closed.get()) {
                    // close() never takes the writer monitor: it must interrupt a blocked write.
                    val deadline = watchdog.schedule({ close() }, timeoutMs, TimeUnit.MILLISECONDS)
                    try {
                        socket.getOutputStream().write(data)
                        socket.getOutputStream().flush()
                        onSent()
                    } catch (_: java.io.IOException) {
                        close()
                    } finally {
                        deadline.cancel(false)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            close() // Includes queue overflow; never retain an unbounded backlog.
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            socket.close()
        } catch (_: java.io.IOException) {
            // Still discard queued work and publish closure if socket teardown reports an error.
        } finally {
            worker.shutdownNow()
            onClose()
        }
    }

    internal fun awaitTermination(timeoutMs: Long): Boolean = worker.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
}
