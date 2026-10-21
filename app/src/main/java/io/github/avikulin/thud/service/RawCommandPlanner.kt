package io.github.avikulin.thud.service

/** Pure preflight: no RPC or tracked command state changes until a plan is valid. */
internal object RawCommandPlanner {
    fun speed(
        adjustedKph: Double,
        toRaw: (Double) -> Double,
        acceptsRaw: (Double) -> Boolean
    ): Double? {
        if (!adjustedKph.isFinite() || adjustedKph < 0.0) return null
        // A stop must not turn into movement because of a calibration intercept.
        val raw = if (adjustedKph == 0.0) 0.0 else toRaw(adjustedKph)
        return raw.takeIf(acceptsRaw)
    }

    data class Incline(val rawPercent: Double, val compensatedRawSpeed: Double?)

    fun incline(
        effectivePercent: Double,
        adjustment: Double,
        compensationTarget: Double?,
        toRawSpeed: (Double, Double) -> Double,
        acceptsIncline: (Double) -> Boolean,
        acceptsSpeed: (Double) -> Boolean
    ): Incline? {
        if (!effectivePercent.isFinite()) return null
        val rawIncline = effectivePercent + adjustment
        if (!rawIncline.isFinite() || !acceptsIncline(rawIncline)) return null
        val speed = if (compensationTarget != null && compensationTarget != 0.0) {
            speed(compensationTarget, { toRawSpeed(it, rawIncline) }, acceptsSpeed)
                ?: return null
        } else null
        return Incline(rawIncline, speed)
    }
}
