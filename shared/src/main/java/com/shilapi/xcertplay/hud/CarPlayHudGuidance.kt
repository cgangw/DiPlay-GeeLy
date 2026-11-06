package com.shilapi.xcertplay.hud

/** The next CarPlay maneuver in the compact form needed by secondary-display HUDs. */
data class CarPlayHudGuidance(
    val distanceMeters: Int,
    val maneuverCode: Int,
    val road: String,
    /** Phone-supplied arrival time, in epoch seconds; absent when the iPhone does not send one. */
    val arrivalEpochSeconds: Long? = null,
    /** Distance left on the whole route, not to the next maneuver. */
    val remainingMeters: Long? = null,
)
