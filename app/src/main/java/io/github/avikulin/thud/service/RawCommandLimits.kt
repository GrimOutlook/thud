package io.github.avikulin.thud.service

/** Discovered hardware limits only; UI defaults must never authorize movement. */
internal data class RawCommandLimits(val min: Double, val max: Double) {
    fun accepts(value: Double): Boolean =
        min.isFinite() && max.isFinite() && min <= max &&
            value.isFinite() && value >= min && value <= max

    companion object {
        fun acceptsSpeed(value: Double, limits: RawCommandLimits?): Boolean =
            value.isFinite() && (value == 0.0 ||
                (value > 0.0 && limits != null && limits.min >= 0.0 &&
                    limits.max > 0.0 && limits.accepts(value)))
    }
}
