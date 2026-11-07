package dev.codexpocket.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Capsule for chips, segmented pills and text buttons: straight top and bottom edges with two
 * complete half-circle ends (corner radius = half the height). Square boxes become circles.
 */
internal val PocketCapsule = RoundedCornerShape(percent = 50)

/** Legacy name kept so every call site shares the one capsule. */
internal val PocketOval = PocketCapsule
