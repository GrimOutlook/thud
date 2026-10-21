package io.github.avikulin.thud.service.ftms

/** Per-transport control lease. Session keys must identify a connection, not a remote IP.
 * Dispatch stays under the same lock as acquisition and cleanup so a disconnected or
 * competing sender cannot slip a command between the ownership check and dispatch.
 */
internal class FtmsControlAuthorization<S : Any>(private val controlAllowed: Boolean) {
    private val sessions = mutableSetOf<S>()
    private var owner: S? = null
    private var active = false

    @Synchronized
    fun start() {
        sessions.clear()
        owner = null
        active = true
    }

    @Synchronized
    fun connected(sender: S) {
        if (active) sessions.add(sender)
    }

    @Synchronized
    fun disconnected(sender: S) {
        sessions.remove(sender)
        if (owner == sender) owner = null
    }

    @Synchronized
    fun clear() {
        active = false
        sessions.clear()
        owner = null
    }

    /** Null means control not permitted; denied requests never invoke callbacks. */
    @Synchronized
    fun <R : Any> execute(
        sender: S,
        requestControl: Boolean,
        reset: Boolean,
        approve: () -> Boolean,
        dispatch: () -> R
    ): R? {
        if (!active || !controlAllowed || sender !in sessions) return null
        if (requestControl) {
            if (owner != null && owner != sender) return null
            if (owner == null) {
                if (!approve()) return null
                owner = sender
            }
        } else if (owner != sender) {
            return null
        }
        if (reset) owner = null
        return dispatch()
    }
}
