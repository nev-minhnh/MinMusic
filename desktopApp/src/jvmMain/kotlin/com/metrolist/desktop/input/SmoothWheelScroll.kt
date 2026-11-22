package com.metrolist.desktop.input

import androidx.compose.runtime.withFrameNanos
import com.metrolist.shared.utils.OsUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.skiko.SkiaLayer
import java.awt.AWTEvent
import java.awt.Component
import java.awt.GraphicsEnvironment
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.exp

/**
 * Smooth scrolling for classic mouse-wheel notches on Windows.
 *
 * Compose animates every notch on its own: a linear tween of at most 100 ms that stops dead before the next notch
 * (notches usually come every 80-150 ms), so the list moves, stops, moves again. That reads as judder even when the
 * renderer keeps up, because many presented frames show the same scroll position.
 *
 * Here each notch only moves a target. A critically damped spring follows the target, advanced once per rendered
 * frame from the frame clock, so consecutive notches blend into one continuous motion with a steady per-frame step.
 * The step is handed back to Compose as a high-resolution wheel event (fractional preciseWheelRotation), which
 * Compose applies immediately through the normal hit testing and nested scrolling, so the scrolled distance per notch
 * stays exactly Compose's (3/20 of the viewport per notch on Windows).
 *
 * Touchpads and high-resolution wheels already send fractional deltas; they pass through untouched, as do
 * Ctrl+wheel, page scrolling and anything outside a Compose surface.
 */
internal class SmoothWheelScroller(
    private val scope: CoroutineScope,
    private val post: (AWTEvent) -> Unit,
) {
    private var source: Component? = null
    private var horizontal = false
    private var template: MouseWheelEvent? = null
    private var x = 0
    private var y = 0
    private var xOnScreen = 0
    private var yOnScreen = 0

    // Positions are in wheel notches.
    private val spring = WheelSpring()
    private var job: Job? = null
    private var lastFrameNanos = 0L

    /** Returns true when [event] was taken over and must not be dispatched. */
    fun intercept(
        event: MouseEvent,
        dispatchNow: (AWTEvent) -> Unit,
    ): Boolean {
        when (event.id) {
            MouseEvent.MOUSE_WHEEL -> {
                return event is MouseWheelEvent && onWheel(event, dispatchNow)
            }

            MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_DRAGGED -> {
                if (event.component === source) {
                    x = event.x
                    y = event.y
                    xOnScreen = event.xOnScreen
                    yOnScreen = event.yOnScreen
                }
            }

            MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_EXITED -> {
                if (event.component === source) stop()
            }
        }
        return false
    }

    private fun onWheel(
        event: MouseWheelEvent,
        dispatchNow: (AWTEvent) -> Unit,
    ): Boolean {
        if (event is SmoothWheelEvent || !isNotch(event) || !isComposeSurface(event.component)) return false
        if (event.component !== source || event.isShiftDown != horizontal) {
            stop()
            source = event.component
            horizontal = event.isShiftDown
        }
        template = event
        com.metrolist.desktop.ui.DesktopFrameTrace.log("N ${System.nanoTime()} ${event.wheelRotation} ${event.preciseWheelRotation}")
        x = event.x
        y = event.y
        xOnScreen = event.xOnScreen
        yOnScreen = event.yOnScreen
        spring.target += event.preciseWheelRotation
        if (job?.isActive != true) {
            // Show the first step in the very next frame instead of one frame later.
            spring.advance(frameSeconds())
            emit(dispatchNow)
            lastFrameNanos = 0L
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) { animate() }
        }
        return true
    }

    private suspend fun animate() {
        while (!spring.isSettled) {
            withFrameNanos { frameNanos ->
                // A step posted in this frame is applied before the next one, so it covers the coming frame interval.
                val dt = if (lastFrameNanos == 0L) frameSeconds() else (frameNanos - lastFrameNanos) / 1e9
                lastFrameNanos = frameNanos
                spring.advance(dt.coerceIn(0.0, MaxStepSeconds))
                emit(post)
            }
        }
        spring.reset()
    }

    private fun emit(send: (AWTEvent) -> Unit) {
        val step = spring.takeStep() ?: return
        val base = template ?: return
        val component = source ?: return
        send(SmoothWheelEvent(component, base, x, y, xOnScreen, yOnScreen, step))
    }

    private fun stop() {
        job?.cancel()
        job = null
        spring.reset()
    }

    private fun frameSeconds(): Double {
        val rate =
            runCatching {
                (source?.graphicsConfiguration?.device ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice)
                    .displayMode.refreshRate
            }.getOrDefault(0)
        return 1.0 / (if (rate in 24..1000) rate else 60)
    }

    private companion object {
        const val MaxStepSeconds = 0.1
    }
}

/** Critically damped spring towards [target]; [takeStep] returns the movement not yet emitted. */
internal class WheelSpring(
    private val omega: Double = DefaultOmega,
) {
    var target = 0.0
    private var position = 0.0
    private var velocity = 0.0
    private var emitted = 0.0

    val isSettled: Boolean get() = position == target && emitted == position

    fun advance(dt: Double) {
        if (dt <= 0.0) return
        val error = position - target
        val decay = exp(-omega * dt)
        val newError = (error + (velocity + omega * error) * dt) * decay
        velocity = (velocity - omega * (velocity + omega * error) * dt) * decay
        position = target + newError
        if (abs(newError) < SettleDistance && abs(velocity) < SettleVelocity) {
            position = target
            velocity = 0.0
        }
    }

    fun takeStep(): Double? {
        val step = position - emitted
        // Compose drops a lone wheel delta below half a pixel; keep tiny steps until they add up.
        if (step == 0.0 || (abs(step) < MinStep && position != target)) return null
        emitted = position
        return step
    }

    fun reset() {
        target = 0.0
        position = 0.0
        velocity = 0.0
        emitted = 0.0
    }

    companion object {
        /** Rad/s: one notch is 90 % there after ~180 ms, and notches 80-150 ms apart blend into one motion. */
        const val DefaultOmega = 22.0
        const val SettleDistance = 0.002
        const val SettleVelocity = 0.05
        const val MinStep = 0.004
    }
}

/** A frame step of [SmoothWheelScroller]; fractional rotation makes Compose apply it at once (no own animation). */
internal class SmoothWheelEvent(
    component: Component,
    base: MouseWheelEvent,
    x: Int,
    y: Int,
    xOnScreen: Int,
    yOnScreen: Int,
    rotation: Double,
) : MouseWheelEvent(
        component,
        MOUSE_WHEEL,
        System.currentTimeMillis(),
        base.modifiersEx,
        x,
        y,
        xOnScreen,
        yOnScreen,
        0,
        false,
        WHEEL_UNIT_SCROLL,
        base.scrollAmount,
        0,
        rotation,
    )

internal val smoothWheelEnabled: Boolean =
    OsUtil.isWindows &&
        System.getProperty("minmusic.wheel.smooth")?.equals("false", ignoreCase = true) != true

private fun isNotch(event: MouseWheelEvent): Boolean =
    event.scrollType == MouseWheelEvent.WHEEL_UNIT_SCROLL &&
        event.wheelRotation != 0 &&
        !event.isControlDown &&
        !isPreciseWheelRotation(event.preciseWheelRotation, event.wheelRotation)

private fun isComposeSurface(component: Component?): Boolean =
    component is SkiaLayer || (component != null && SwingUtilities.getAncestorOfClass(SkiaLayer::class.java, component) != null)
