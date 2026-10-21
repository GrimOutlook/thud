# FTMS authorization regression tests

These tests execute the production authorization gate on the JVM, without Android,
network connections, or treadmill hardware. Requires Kotlin and a JDK:

```sh
kotlinc app/src/main/java/io/github/avikulin/thud/service/ftms/FtmsControlAuthorization.kt tests/ftms/FtmsControlAuthorizationTest.kt -include-runtime -d /tmp/ftms-authorization-tests.jar
java -jar /tmp/ftms-authorization-tests.jar
```

On Nix, run those commands inside `nix-shell -p kotlin jdk17`.

Covers disabled control, acquisition rejection, commands before acquisition,
exclusive ownership, non-owner reset, owner reset, observer/owner disconnect,
reconnection, shutdown/restart, 100 concurrent acquisition races, and cleanup
serialization against an in-flight dispatch. Callback
counters verify that denied operations never dispatch or ask for approval.
This suite does not exercise Android GATT callbacks or real DirCon sockets.
