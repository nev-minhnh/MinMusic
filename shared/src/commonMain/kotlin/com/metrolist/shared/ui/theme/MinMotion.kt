/*
 * MinMusic design tokens: motion (design-system section 7).
 * Motion only animates transforms, alpha and clip. Never layout size inside lazy lists, never blur.
 */

package com.metrolist.shared.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp

object MinMotion {
    /** Emphasized decelerate, for things entering. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Emphasized accelerate, for things leaving. */
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val FAST_MILLIS = 120
    const val STANDARD_MILLIS = 240
    const val STANDARD_EXIT_MILLIS = 160
    const val NAV_MILLIS = 300
    const val NAV_EXIT_FADE_MILLIS = 100
    const val CONTAINER_MILLIS = 380
    const val TRACK_CHANGE_MILLIS = 240
    const val TINT_CROSSFADE_MILLIS = 300

    /** Horizontal travel of the shared-axis X navigation transition. */
    val NavSlideDistance = 30.dp

    /** Pressed scale for artwork, tiles and the play button. */
    const val PRESS_SCALE = 0.96f

    /** Press feedback, chip select, switch. */
    fun <T> fast(): TweenSpec<T> = tween(FAST_MILLIS, easing = FastOutSlowInEasing)

    /** Fades, tab content and sheet content entering. */
    fun <T> standardEnter(): TweenSpec<T> = tween(STANDARD_MILLIS, easing = EmphasizedDecelerate)

    /** Fades, tab content and sheet content leaving. */
    fun <T> standardExit(): TweenSpec<T> = tween(STANDARD_EXIT_MILLIS, easing = EmphasizedAccelerate)

    /** Push/pop between screens (shared-axis X). */
    fun <T> nav(): TweenSpec<T> = tween(NAV_MILLIS, easing = EmphasizedDecelerate)

    /** The outgoing screen of a push/pop fades out quickly. */
    fun <T> navExitFade(): TweenSpec<T> = tween(NAV_EXIT_FADE_MILLIS, easing = EmphasizedAccelerate)

    /** Mini <-> full player, tile -> detail (about 380 ms to settle). */
    fun <T> container(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = 380f)

    /** Scale down on press. */
    fun <T> pressIn(): SpringSpec<T> = spring(dampingRatio = 0.7f, stiffness = 1500f)

    /** Scale back on release. */
    fun <T> pressOut(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 600f)

    /** EQ node drag follow, slider thumb. */
    fun <T> spatialSpring(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = 700f)

    /** Reduced motion: springs snap, fades shorten to [FAST_MILLIS]. */
    fun <T> reduced(fade: Boolean): FiniteAnimationSpec<T> = if (fade) tween(FAST_MILLIS) else tween(0)
}
