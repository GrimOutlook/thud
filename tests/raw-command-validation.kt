package io.github.avikulin.thud.service

/** Hardware-free regression suite; run with bash tests/run-raw-command-tests.sh. */
fun main() {
    var passed = 0
    fun test(name: String, body: () -> Unit) {
        body()
        passed++
        println("PASS: $name")
    }
    val speedLimits = RawCommandLimits(1.6, 20.0)
    val inclineLimits = RawCommandLimits(-6.0, 40.0)
    val acceptsSpeed = { raw: Double -> RawCommandLimits.acceptsSpeed(raw, speedLimits) }
    fun speed(adjusted: Double, conversion: (Double) -> Double = { it }) =
        RawCommandPlanner.speed(adjusted, conversion, acceptsSpeed)
    fun incline(
        effective: Double = 4.0,
        adjustment: Double = 1.0,
        target: Double? = 10.0,
        conversion: (Double, Double) -> Double = { value, _ -> value }
    ) = RawCommandPlanner.incline(effective, adjustment, target, conversion,
        inclineLimits::accepts, acceptsSpeed)

    test("nonfinite raw speed and incline are rejected") {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            check(!acceptsSpeed(value))
            check(!inclineLimits.accepts(value))
        }
    }
    test("inclusive hardware boundaries and stop are preserved without clamping") {
        for (value in listOf(0.0, -0.0, 1.6, 20.0)) check(acceptsSpeed(value))
        for (value in listOf(-1.0, 0.1, Math.nextDown(1.6), Math.nextUp(20.0))) {
            check(!acceptsSpeed(value))
        }
        check(inclineLimits.accepts(-6.0))
        check(inclineLimits.accepts(40.0))
        check(!inclineLimits.accepts(Math.nextDown(-6.0)))
        check(!inclineLimits.accepts(Math.nextUp(40.0)))
    }
    test("absent capabilities fail closed except exact stop") {
        check(!RawCommandLimits.acceptsSpeed(10.0, null))
        check(RawCommandLimits.acceptsSpeed(0.0, null))
        check(RawCommandLimits.acceptsSpeed(-0.0, null))
        check(RawCommandPlanner.incline(4.0, 1.0, 10.0,
            { _, _ -> error("unsupported incline must reject before compensation") },
            { false }, acceptsSpeed) == null)
        check(RawCommandPlanner.incline(4.0, 1.0, 10.0,
            { target, _ -> target }, inclineLimits::accepts,
            { RawCommandLimits.acceptsSpeed(it, null) }) == null)
    }
    test("malformed discovered ranges cannot authorize movement") {
        val badRanges = listOf(RawCommandLimits(Double.NaN, 20.0),
            RawCommandLimits(1.6, Double.POSITIVE_INFINITY), RawCommandLimits(20.0, 1.6),
            RawCommandLimits(Double.NEGATIVE_INFINITY, 20.0))
        for (limits in badRanges) {
            check(!limits.accepts(10.0))
            check(!RawCommandLimits.acceptsSpeed(10.0, limits))
        }
        check(!RawCommandLimits.acceptsSpeed(1.0, RawCommandLimits(-1.0, 20.0)))
        check(!RawCommandLimits.acceptsSpeed(1.0, RawCommandLimits(0.0, 0.0)))
        check(RawCommandLimits(0.0, 0.0).accepts(0.0)) // Fixed, supported incline.
    }
    test("invalid adjusted inputs are rejected before calibration") {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            check(speed(value) { error("conversion must not run") } == null)
        }
    }
    test("stop bypasses even broken calibration") {
        check(speed(0.0) { error("stop must bypass calibration") } == 0.0)
        check(speed(-0.0) { Double.NaN } == 0.0)
    }
    test("final calibrated output, not adjusted input, determines hardware validity") {
        check(speed(10.0) { (it - 1.0) / 0.25 } == null) // raw 36
        check(speed(10.0) { it / 0.0 } == null)
        check(speed(10.0) { Double.NaN } == null)
        check(speed(10.0) { -2.0 } == null)
        check(speed(10.0) { 0.5 } == null)
        check(speed(30.0) { it / 2.0 } == 15.0) // Valid raw despite adjusted > max.
        check(speed(10.0) { 1.6 } == 1.6)
        check(speed(10.0) { 20.0 } == 20.0)
    }
    test("incline adjustment is validated after conversion") {
        check(incline(effective = -7.0)?.rawPercent == -6.0)
        check(incline(effective = 39.0)?.rawPercent == 40.0)
        check(incline(effective = 40.0) == null)
        check(incline(effective = -8.0) == null)
        check(incline(effective = Double.NaN) == null)
        check(incline(adjustment = Double.POSITIVE_INFINITY) == null)
        check(incline(effective = Double.MAX_VALUE, adjustment = Double.MAX_VALUE) == null)
    }
    test("invalid compensation rejects the entire incline plan") {
        for (raw in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            -1.0, 0.5, 20.1)) {
            check(incline(conversion = { _, _ -> raw }) == null)
        }
        check(incline(target = Double.NaN) == null)
        check(incline(target = -1.0) == null)
    }
    test("compensation uses the new raw incline and preserves raw boundaries") {
        val plan = incline(conversion = { adjusted, rawIncline -> adjusted + 2 * rawIncline })!!
        check(plan.rawPercent == 5.0)
        check(plan.compensatedRawSpeed == 20.0)
        check(incline(conversion = { _, _ -> 1.6 })?.compensatedRawSpeed == 1.6)
    }
    test("disabled compensation or stopped belt never computes a movement command") {
        for (target in listOf(null, 0.0)) {
            check(incline(target = target, conversion = { _, _ -> error("not running") })
                ?.compensatedRawSpeed == null)
        }
    }
    println("$passed tests passed")
}
