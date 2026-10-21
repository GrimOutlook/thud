# DirCon resource regression tests

Run without an Android SDK (requires Java, `kotlinc`, JUnit 4.13.2 and Hamcrest Core 1.3):

```sh
JUNIT_JAR=/path/to/junit-4.13.2.jar \
HAMCREST_JAR=/path/to/hamcrest-core-1.3.jar \
bash tests/dircon/run.sh
```

Optionally set `ANDROID_JAR` to an Android API jar and `COROUTINES_JAR` to
`kotlinx-coroutines-core-jvm` to also compile all production DirCon classes,
including `ClientHandler`, `DirConServer`, and the shared FTMS authorization helper.
This is a focused compile check, not an Android application build.

With an SDK configured, the same JUnit tests are available through:

```sh
bash gradlew :app:testDebugUnitTest --tests 'io.github.avikulin.thud.service.dircon.*'
```

Tests use only in-memory streams and ephemeral loopback sockets. Coverage includes
frame bounds, fragmentation/coalescing, malformed/truncated frames, input deadlines,
notification activity, ordered writes, a non-reading peer alongside a healthy peer,
write-watchdog termination, queue overflow without writer-monitor deadlock, concurrent
slot acquisition, idempotent lease release, and rejected-socket EOF.

Policy: at most eight admitted clients per server instance, including clients still
tearing down across restart; 16 queued frames plus one active write per client;
five seconds per active write. Overflow closes the client. The shared watchdog
closes sockets directly without taking the writer lock. Completion returns an
admission lease only after the reader job and writer terminate; an unexpected
writer teardown failure retains the slot rather than allowing unbounded replacement
threads. These tests do not exercise Android NSD or full server start/stop integration.
