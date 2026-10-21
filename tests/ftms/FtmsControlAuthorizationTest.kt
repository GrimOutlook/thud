package io.github.avikulin.thud.service.ftms

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Hardware-free regression suite. Run with the commands in tests/ftms/README.md. */
fun main() {
    var checks = 0
    fun expect(value: Boolean) { check(value); checks++ }
    for (allowed in listOf(false, true)) {
        val gate = FtmsControlAuthorization<Any>(allowed)
        val a = Any()
        val b = Any()
        gate.start()
        gate.connected(a)
        gate.connected(b)
        var callbacks = 0
        var approvals = 0
        fun command(sender: Any, request: Boolean = false, reset: Boolean = false,
                    approve: Boolean = true): Boolean = gate.execute(
            sender, request, reset, { approvals++; approve }
        ) { callbacks++; true } ?: false

        // Ordinary dispatch and reset both require acquisition.
        expect(!command(a))
        expect(!command(b, reset = true))
        expect(callbacks == 0 && approvals == 0)
        expect(!command(a, request = true, approve = false))
        expect(command(a, request = true) == allowed)
        if (!allowed) {
            expect(approvals == 0 && callbacks == 0)
            continue
        }
        val approved = approvals
        expect(command(a, request = true))
        expect(approvals == approved) // Repeated acquisition is idempotent.
        expect(!command(b, request = true))
        expect(!command(b, reset = true)) // Cannot release somebody else's lease.
        expect(approvals == approved)
        expect(command(a))
        gate.disconnected(b)
        expect(command(a)) // Observer disconnect cannot revoke owner.
        expect(command(a, reset = true))
        expect(!command(a))
        gate.connected(b)
        expect(command(b, request = true))
        gate.disconnected(b)
        expect(!command(b))
        expect(!command(b, request = true)) // Stale connection cannot reacquire.
        gate.connected(b)
        expect(!command(b)) // Reconnect requires a fresh Request Control.
        expect(command(a, request = true))
        gate.clear()
        gate.connected(b) // Late connection callback after shutdown is ignored.
        expect(!command(b, request = true))
        expect(!command(a))
        gate.start()
        expect(!command(a, request = true))
        gate.connected(a)
        expect(command(a, request = true))
    }

    // Concurrent contenders must produce exactly one successful owner/approval.
    repeat(100) {
        val gate = FtmsControlAuthorization<Int>(true)
        gate.start()
        gate.connected(1)
        gate.connected(2)
        val ready = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        var approvals = 0
        val results = (1..2).map { sender ->
            pool.submit<Boolean> {
                check(ready.await(5, TimeUnit.SECONDS))
                gate.execute(sender, true, false, { approvals++; true }) { true } ?: false
            }
        }
        ready.countDown()
        expect(results.count { it.get(5, TimeUnit.SECONDS) } == 1)
        expect(approvals == 1)
        pool.shutdownNow()
    }

    // Cleanup must wait for an already-authorized dispatch, then deny stale work.
    val gate = FtmsControlAuthorization<Int>(true)
    gate.start()
    gate.connected(1)
    expect(gate.execute(1, true, false, { true }) { true } == true)
    val entered = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val cleanupStarted = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    val dispatch = pool.submit<Boolean> {
        gate.execute(1, false, false, { error("Unexpected acquisition") }) {
            entered.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            true
        } ?: false
    }
    check(entered.await(5, TimeUnit.SECONDS))
    val cleanup = pool.submit {
        cleanupStarted.countDown()
        gate.disconnected(1)
    }
    check(cleanupStarted.await(5, TimeUnit.SECONDS))
    expect(!cleanup.isDone)
    finish.countDown()
    expect(dispatch.get(5, TimeUnit.SECONDS))
    cleanup.get(5, TimeUnit.SECONDS)
    expect(gate.execute<Boolean>(1, false, false, { true }) { error("Stale dispatch") } == null)
    pool.shutdownNow()
    println("PASS: $checks FTMS authorization checks")
}
