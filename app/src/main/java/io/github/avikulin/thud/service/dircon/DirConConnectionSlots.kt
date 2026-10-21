package io.github.avikulin.thud.service.dircon

import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** A lease survives server stop/restart until the owning reader AND writer have terminated. */
internal class DirConConnectionSlots(limit: Int) {
    private val permits = Semaphore(limit)

    fun acquire(socket: Socket): Lease? {
        if (permits.tryAcquire()) return Lease()
        socket.close()
        return null
    }

    inner class Lease internal constructor() : AutoCloseable {
        private val released = AtomicBoolean()
        override fun close() {
            if (released.compareAndSet(false, true)) permits.release()
        }
    }
}
