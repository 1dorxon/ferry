package com.dorxon.browserdrop.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Motion, after the website's (public/style.css, "Motion"): the screen rises
 * into place on first open, devices glide in and out, a ring pings round a
 * device that just turned up, a finished transfer bursts green on the device
 * it went to or came from and its row draws a tick, a failed one shakes.
 * All of it stays still when the phone's "Remove animations" is on.
 */

/** The website's --glide: quick out of the blocks, long soft landing. */
val Glide = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** True when the phone asks for no animation (Settings › Accessibility › Remove animations). */
val LocalCalm = staticCompositionLocalOf { false }

@Composable
fun rememberCalm(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}

/** The entrance plays once per launch, not again on rotation or theme change. */
internal object Entrance { var played = false }

/** Rise into place on first open, [index]-th in line. */
@Composable
fun Modifier.rise(index: Int): Modifier {
    val calm = LocalCalm.current
    val play = remember { !Entrance.played && !calm }
    if (!play) return this
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index.coerceAtMost(9) * 55L)
        a.animateTo(1f, tween(620, easing = Glide))
    }
    return graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 18.dp.toPx()
    }
}

/**
 * A list whose items glide in when they arrive and fold away when they go,
 * instead of popping. Items already there on first draw just appear (the
 * entrance covers them). [content] gets the item, whether it arrived later,
 * and whether it is the top one (for dividers).
 */
@Composable
fun <T> AnimatedEach(items: List<T>, key: (T) -> String, content: @Composable (item: T, fresh: Boolean, first: Boolean) -> Unit) {
    val onScreen = remember { LinkedHashMap<String, T>() }   // in order, including items on their way out
    val gone = remember { HashSet<String>() }                // finished leaving: drop on the next pass
    val initial = remember { items.map(key).toSet() }
    val now = LinkedHashMap<String, T>().apply { items.forEach { put(key(it), it) } }
    gone.removeAll(now.keys)
    // Keep a leaving item where it was, so it folds away in place.
    val order = ArrayList<String>(now.keys)
    onScreen.keys.forEachIndexed { i, k -> if (k !in now && k !in gone) order.add(minOf(i, order.size), k) }
    val previous = HashMap(onScreen)
    onScreen.clear()
    order.forEach { k -> onScreen[k] = now[k] ?: previous.getValue(k) }
    gone.clear()

    onScreen.forEach { (k, item) ->
        key(k) {
            val state = remember { MutableTransitionState(k in initial) }
            state.targetState = k in now
            if (state.isIdle && !state.currentState && !state.targetState) gone.add(k)
            AnimatedVisibility(
                state,
                enter = fadeIn(tween(420, easing = Glide)) + expandVertically(tween(480, easing = Glide)),
                exit = fadeOut(tween(220)) + shrinkVertically(tween(380, easing = Glide)),
            ) { content(item, k !in initial, k == order.first()) }
        }
    }
}

/** 0 → 1 once when [trigger] changes to something new (and not on first draw). */
@Composable
fun rememberPulse(trigger: Any?, durationMs: Int, play: Boolean = true): Animatable<Float, *> {
    val a = remember { Animatable(1f) }
    val first = remember { trigger }
    LaunchedEffect(trigger) {
        if (play && trigger != null && trigger != first) {
            a.snapTo(0f)
            a.animateTo(1f, tween(durationMs, easing = Glide))
        }
    }
    return a
}

/** A ring that grows out of a device and fades: "here's someone new". */
fun DrawScope.ping(t: Float, color: Color) {
    if (t >= 1f) return
    val r = size.minDimension / 2
    drawCircle(color.copy(alpha = 0.55f * (1f - t)), radius = r * (0.7f + 0.75f * t), style = Stroke(2.dp.toPx() * (1f - t * 0.6f)))
}

/** Sparks flying out of the centre and fading: a transfer landed. */
fun DrawScope.burst(t: Float, color: Color, count: Int = 16) {
    if (t >= 1f) return
    val c = Offset(size.width / 2, size.height / 2)
    val reach = size.minDimension / 2 + 26.dp.toPx()
    // Hold full strength for the first part of the flight, then fade (the website's keyframe at .45).
    val fade = if (t < 0.45f) 1f else 1f - (t - 0.45f) / 0.55f
    for (i in 0 until count) {
        val ang = (i * 360f / count + (i % 3) * 7f) * PI.toFloat() / 180f
        val d = reach * (0.35f + 0.65f * t) * (0.82f + (i % 4) * 0.06f)
        val p = c + Offset(cos(ang) * d, sin(ang) * d)
        val rad = (3.6f - 2.2f * t).dp.toPx()
        drawCircle(color.copy(alpha = 0.25f * fade), radius = rad * 2.2f, center = p)
        drawCircle(color.copy(alpha = fade), radius = rad, center = p)
    }
}

/** A tick drawn stroke by stroke, [t] of the way. */
@Composable
fun Tick(t: Float, color: Color, modifier: Modifier = Modifier) {
    val path = remember { Path() }
    val part = remember { Path() }
    val measure = remember { PathMeasure() }
    Canvas(modifier) {
        val s = size.minDimension / 24f
        path.reset()
        path.moveTo(5f * s, 12.5f * s); path.lineTo(10f * s, 17.5f * s); path.lineTo(19f * s, 7f * s)
        measure.setPath(path, false)
        part.reset()
        measure.getSegment(0f, measure.length * t.coerceIn(0f, 1f), part, true)
        drawPath(part, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
