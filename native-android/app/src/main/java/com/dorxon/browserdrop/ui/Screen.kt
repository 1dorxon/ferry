package com.dorxon.browserdrop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dorxon.browserdrop.Engine
import com.dorxon.browserdrop.Phase
import com.dorxon.browserdrop.PeerUi
import com.dorxon.browserdrop.R
import com.dorxon.browserdrop.TransferUi
import com.dorxon.browserdrop.UiState
import com.dorxon.browserdrop.proto.Dir

/** What the screen can ask for. */
class Actions(
    val tapPeer: (PeerUi) -> Unit,
    val rename: (String) -> Unit,
    val answer: (String, Boolean) -> Unit,
    val cancel: (String) -> Unit,
    val retry: (String) -> Unit,
    val open: (TransferUi) -> Unit,
    val clearFinished: () -> Unit,
    val clearShared: () -> Unit,
    val openDownloads: () -> Unit,
    /** Start hosting a code (null) or join one from another device. */
    val pair: (String?) -> Unit = {},
    val stopPair: () -> Unit = {},
    val dismissPairPrompt: () -> Unit = {},
    val dismissSpeedTip: () -> Unit = {},
    val setTheme: (ThemeMode) -> Unit = {},
    /** The clipboard: send text to a device, copy text that arrived, read what's copied here. */
    val sendText: (peerId: String, text: String) -> Unit = { _, _ -> },
    val copy: (TransferUi) -> Unit = {},
    val readClipboard: () -> String? = { null },
    val clearSharedText: () -> Unit = {},
)

private fun art(device: String) = when (device) {
    "iphone", "android" -> R.drawable.dev_phone
    "ipad" -> R.drawable.dev_tablet
    "mac" -> R.drawable.dev_laptop
    else -> R.drawable.dev_desktop
}

private fun label(device: String) = when (device) {
    "iphone" -> "iPhone"; "android" -> "Android"; "ipad" -> "iPad"; "mac" -> "Mac"
    "windows" -> "Windows PC"; else -> "Computer"
}

/*
 * Layout: the Chrome extension's side panel (extension/panel.css), which reads
 * best on a narrow screen. A stack of cards on a flat, warm background: this
 * phone, the devices nearby as full-width rows with a Send button, pairing,
 * then transfers. Everything lines up on one left edge.
 *
 * Finish (from a Higgsfield design study, store/design/): no soft shadows.
 * Cards and buttons get hairline edges lit from above, buttons are rounded
 * rectangles with a slight bevel rather than pills, and numbers are monospace.
 */
@Composable
fun Screen(s: UiState, a: Actions, theme: ThemeMode = ThemeMode.AUTO) {
    val p = LocalPalette.current
    var info by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var pairing by rememberSaveable { mutableStateOf(false) }
    var composing by rememberSaveable { mutableStateOf(false) }
    var composeTo by rememberSaveable { mutableStateOf<String?>(null) }
    val openPair = { if (s.pairCode == null) a.pair(null); pairing = true }
    val landed = rememberLandings(s.transfers)
    val later = 3 + s.peers.size.coerceAtMost(5)
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(1400); Entrance.played = true }

    CompositionLocalProvider(LocalCalm provides rememberCalm()) {
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopBar(theme, a.setTheme, onInfo = { info = true }, Modifier.rise(0))
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Reveal(s.shared.isNotEmpty()) { ShareBanner(s.shared, a.clearShared) }
                MeCard(s, onRename = { renaming = true }, Modifier.rise(1))
                Heading("Nearby", Modifier.rise(2))
                // Spacing comes from the cards' own top padding here, so a leaving card can fold to nothing.
                Column {
                    AnimatedEach(s.peers, key = { it.id }) { peer, fresh, first ->
                        PeerCard(peer, sharing = s.shared.isNotEmpty(), fresh = fresh, landed = landed[peer.name] ?: 0,
                            onText = { composeTo = peer.id; composing = true },
                            modifier = Modifier.padding(top = if (first) 0.dp else 10.dp).rise(3 + s.peers.indexOf(peer).coerceAtMost(5))) { a.tapPeer(peer) }
                    }
                    Reveal(s.peers.isEmpty()) { EmptyCard(Modifier.rise(3)) }
                }
                Reveal(s.pairPrompt && !pairing) { PairPrompt(onPair = openPair, onDismiss = a.dismissPairPrompt) }
                PairLine(s, pairing, onPair = openPair, onStop = { pairing = false; a.stopPair() }, Modifier.rise(later))
                Reveal(pairing) { PairCard(s, onJoin = { code -> a.pair(code) }, onClose = { pairing = false }) }
                Reveal(s.transfers.isNotEmpty()) { TransfersSection(s.transfers, a, Modifier.rise(later + 1)) }
                Reveal(s.speedTip) { SpeedTip(a.dismissSpeedTip) }
                Footer(a.openDownloads, Modifier.rise(later + 2))
                Spacer(Modifier.height(20.dp))
            }
        }
    }
    if (info) InfoDialog { info = false }
    if (renaming) RenameDialog(s.name, onDone = { v -> renaming = false; v?.let(a.rename) })
    if (composing || s.sharedText != null) {
        TextDialog(s.peers, composeTo, s.sharedText ?: "", a.readClipboard) { peerId, text ->
            composing = false
            if (s.sharedText != null) a.clearSharedText()
            if (peerId != null && text != null) { composeTo = peerId; a.sendText(peerId, text) }
        }
    }
    }
}

@Composable
private fun Reveal(visible: Boolean, content: @Composable () -> Unit) =
    AnimatedVisibility(visible,
        enter = fadeIn(tween(420, easing = Glide)) + expandVertically(tween(480, easing = Glide)),
        exit = fadeOut(tween(220)) + shrinkVertically(tween(380, easing = Glide))) { content() }

/**
 * How many transfers have landed with each device (by name), counting only
 * ones that finish while the screen is up: each new count sets off a burst
 * on that device's card.
 */
@Composable
private fun rememberLandings(transfers: List<TransferUi>): Map<String, Int> {
    val seen = remember { HashMap<String, Phase>() }
    var primed by remember { mutableStateOf(false) }
    val counts = remember { mutableStateMapOf<String, Int>() }
    LaunchedEffect(transfers) {
        transfers.forEach { t ->
            val before = seen.put(t.key, t.phase)
            // A short text can go from nothing to done between two frames, so a new row that's already done counts too.
            if (primed && t.phase == Phase.DONE && before != Phase.DONE) counts[t.peerName] = (counts[t.peerName] ?: 0) + 1
        }
        primed = true
    }
    return counts
}

/* ---------------------------------------------------------------- pieces */

private val CardShape = RoundedCornerShape(14.dp)
private val BtnShape = RoundedCornerShape(10.dp)

/** A hairline that's lighter on top than below, as if lit from above. */
@Composable
private fun Modifier.edge(shape: Shape): Modifier {
    val p = LocalPalette.current
    return border(1.dp, Brush.verticalGradient(listOf(p.edgeTop, p.edgeBottom)), shape)
}

/** The card: solid surface and a lit hairline. No drop shadow. */
@Composable
private fun Modifier.card(): Modifier {
    val p = LocalPalette.current
    return this.clip(CardShape).background(p.card).edge(CardShape)
}

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = type(12, 600, p.fg2, 0.08), modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Link(text: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(text, style = type(13, 500, p.accent),
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp))
}

/* -------------------------------------------------------------- top bar */

@Composable
private fun TopBar(theme: ThemeMode, setTheme: (ThemeMode) -> Unit, onInfo: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo_lg), null, Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)))
            Spacer(Modifier.width(10.dp))
            Text("Ferry", style = type(19, 700, p.fg, -0.024))
            Spacer(Modifier.weight(1f))
            ThemeSwitch(theme, setTheme)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(p.card).edge(CircleShape).clickable(onClick = onInfo),
                contentAlignment = Alignment.Center,
            ) { Icon(Glyph.Info, "About", Modifier.size(17.dp), tint = p.fg2) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.stroke))
    }
}

/** Light / dark / follow the system. The chosen one is filled. */
@Composable
private fun ThemeSwitch(mode: ThemeMode, set: (ThemeMode) -> Unit) {
    val p = LocalPalette.current
    val modes = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.AUTO)
    val seg = RoundedCornerShape(8.dp)
    // The filled segment slides to the one picked, like the website's.
    val at by animateFloatAsState(modes.indexOf(mode).toFloat(), spring(dampingRatio = 0.72f, stiffness = 420f), label = "seg")
    Box(Modifier.clip(BtnShape).background(p.card).edge(BtnShape).padding(2.dp)) {
        Box(Modifier.offset { IntOffset((at * 36.dp.toPx()).roundToInt(), 0) }.size(width = 36.dp, height = 28.dp)
            .clip(seg).background(raised(p.accent)).border(1.dp, bevel(), seg))
        Row(verticalAlignment = Alignment.CenterVertically) {
        for (m in modes) {
            Box(
                Modifier.size(width = 36.dp, height = 28.dp).clip(seg).clickable { set(m) },
                contentAlignment = Alignment.Center,
            ) {
                val tint = lerp(p.fg2, Color.White, (1f - kotlin.math.abs(at - modes.indexOf(m))).coerceIn(0f, 1f))
                val desc = when (m) { ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark"; ThemeMode.AUTO -> "Automatic" }
                when (m) {
                    ThemeMode.LIGHT -> Icon(Glyph.Sun, desc, Modifier.size(16.dp), tint = tint)
                    ThemeMode.DARK -> Icon(Glyph.Moon, desc, Modifier.size(15.dp), tint = tint)
                    ThemeMode.AUTO -> Text("A", style = type(14, 600, tint))
                }
            }
        }
        }
    }
}

/** A solid colour, a touch lighter at the top: the face of a raised button. */
private fun raised(c: Color) = Brush.verticalGradient(listOf(lerp(c, Color.White, 0.12f), c))

/** The edge of a raised button: a highlight on top, a darker lip below. */
private fun bevel() = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Black.copy(alpha = 0.18f)))

/* ---------------------------------------------------------- this phone */

@Composable
private fun MeCard(s: UiState, onRename: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().card().clickable(onClick = onRename).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.dev_phone), null, Modifier.size(56.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("THIS PHONE", style = type(11, 600, p.fg3, 0.08))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, style = type(17, 600, p.fg, -0.02), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Icon(Glyph.Pencil, "Rename", Modifier.size(15.dp), tint = p.fg3)
            }
            // Connection status lives here, next to the device it describes.
            val (dot, status) = when {
                !s.connected -> p.err to "Reconnecting…"
                s.joined -> p.okDot to "Visible to devices on this Wi-Fi"
                else -> p.fg3 to "Connecting…"
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(6.dp))
                Text(status, style = type(12, 400, p.fg2))
            }
        }
    }
}

/* -------------------------------------------------------------- nearby */

@Composable
private fun PeerCard(peer: PeerUi, sharing: Boolean, fresh: Boolean, landed: Int, onText: () -> Unit,
                     modifier: Modifier = Modifier, onTap: () -> Unit) {
    val p = LocalPalette.current
    val calm = LocalCalm.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.985f else 1f, label = "press")
    val ring by animateFloatAsState(peer.progress ?: 0f, tween(200), label = "ring")
    // Turned up after the screen opened: a ring pings out of it.
    val ping = remember { Animatable(if (fresh && !calm) 0f else 1f) }
    LaunchedEffect(Unit) { if (ping.value < 1f) { kotlinx.coroutines.delay(180); ping.animateTo(1f, tween(1100, easing = Glide)) } }
    // A transfer with it just finished: sparks, a green flush and a small bounce.
    val burst = rememberPulse(landed, 1150, play = !calm)
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(landed) { if (landed > 0) haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
    val bounce = if (burst.value < 1f) 1f + 0.08f * sin(burst.value * PI.toFloat()) * (1f - burst.value) else 1f
    // While something's moving, the device breathes and a bright head rides the ring.
    val moving = peer.progress != null && !calm
    val glow = if (moving) {
        val t by rememberInfiniteTransition(label = "moving").animateFloat(0f, 1f,
            infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse), label = "breathe")
        t
    } else 0f
    Row(
        modifier.fillMaxWidth().scale(press).card()
            .drawBehind { if (burst.value < 1f) drawRect(p.ok.copy(alpha = 0.14f * (1f - burst.value))) }
            .clickable(interactionSource = src, indication = null, onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                if (moving) drawCircle(p.accent.copy(alpha = 0.06f + 0.08f * glow), radius = size.minDimension / 2 - 3.dp.toPx())
                ping(ping.value, p.accent)
            }
            if (peer.progress != null) {
                // AirDrop-style progress ring around the device it's going to.
                Canvas(Modifier.fillMaxSize()) {
                    val sw = 3.dp.toPx()
                    val tl = Offset(sw / 2, sw / 2)
                    val sz = Size(size.width - sw, size.height - sw)
                    drawArc(p.fill, 0f, 360f, false, tl, sz, style = Stroke(sw))
                    drawArc(p.accent, -90f, 360f * ring, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                    if (moving && ring > 0.01f) {
                        val a = (-90f + 360f * ring) * PI.toFloat() / 180f
                        val r = sz.width / 2
                        val head = Offset(size.width / 2 + cos(a) * r, size.height / 2 + sin(a) * r)
                        drawCircle(p.accent2.copy(alpha = 0.35f + 0.25f * glow), radius = sw * 1.9f, center = head)
                        drawCircle(Color.White.copy(alpha = 0.9f), radius = sw * 0.55f, center = head)
                    }
                }
            }
            Image(painterResource(art(peer.device)), null, Modifier.size(48.dp).scale(bounce * (1f + 0.025f * glow)))
            Canvas(Modifier.fillMaxSize()) { burst(burst.value, p.okDot) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.name, style = type(15, 600, p.fg, -0.016), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label(peer.device) + " · " + Engine.tagOf(peer.id) + if (peer.paired) " · paired" else "", style = type(12, 400, p.fg2), maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        if (!sharing) { IconBtn(Glyph.Clip, "Send text to ${peer.name}", size = 34, onClick = onText); Spacer(Modifier.width(6.dp)) }
        Btn(if (sharing) "Send here" else "Send", Kind.TINT, onClick = onTap)
    }
}

@Composable
private fun EmptyCard(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val t = rememberInfiniteTransition(label = "radar")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "ping")
    // The computer bobs gently while it waits (the website's "bob").
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val calm = LocalCalm.current
    Column(modifier.fillMaxWidth().card().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                for (k in 0 until 2) {
                    val q = (phase + k / 2f) % 1f
                    drawCircle(p.accent.copy(alpha = 0.4f * (1f - q)), radius = size.minDimension / 2 * (0.45f + 0.55f * q),
                        style = Stroke(1.5.dp.toPx()))
                }
            }
            Image(painterResource(R.drawable.dev_desktop), null,
                Modifier.size(46.dp).graphicsLayer { translationY = if (calm) 0f else (bob - 0.5f) * 4.dp.toPx() })
        }
        Spacer(Modifier.height(6.dp))
        Text("Looking for devices…", style = type(15, 600, p.fg))
        Spacer(Modifier.height(2.dp))
        Text("On your computer or another phone, open ferry.luitlabs.in (or Ferry for Chrome) on the same Wi-Fi.",
            style = type(13, 400, p.fg2), textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
    }
}

@Composable
private fun ShareBanner(names: List<String>, onDiscard: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().card().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(BtnShape).background(raised(p.accent)).border(1.dp, bevel(), BtnShape), contentAlignment = Alignment.Center) {
            Icon(Glyph.Share, null, Modifier.size(19.dp), tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (names.size == 1) names[0] else "${names.size} files", style = type(15, 600, p.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Tap a device to send", style = type(12, 500, p.accent))
        }
        Btn("Discard", Kind.PLAIN, onClick = onDiscard)
    }
}

/* ------------------------------------------------------------ transfers */

@Composable
private fun TransfersSection(list: List<TransferUi>, a: Actions, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Heading("Transfers") {
            if (list.any { it.phase == Phase.DONE || it.phase == Phase.FAILED }) Link("Clear", a.clearFinished)
        }
        Column(Modifier.fillMaxWidth().card().animateContentSize(tween(380, easing = Glide))) {
            AnimatedEach(list, key = { it.key }) { t, fresh, first ->
                Column {
                    if (!first) Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(1.dp).background(p.stroke))
                    TransferRow(t, a, fresh)
                }
            }
        }
    }
}

@Composable
private fun TransferRow(t: TransferUi, a: Actions, fresh: Boolean) {
    val p = LocalPalette.current
    val calm = LocalCalm.current
    val tone by animateColorAsState(
        when (t.phase) { Phase.DONE -> p.ok; Phase.FAILED -> p.err; Phase.ASKING -> p.warn; else -> p.accent }, tween(400), label = "tone")
    // Rows already finished when the screen opened stay still; ones that finish now draw their tick.
    val live = remember(t.key) { fresh || t.phase != Phase.DONE }
    val tick = remember(t.key) { Animatable(if (t.phase == Phase.DONE && (!live || calm)) 1f else 0f) }
    val pop = remember(t.key) { Animatable(1f) }
    val shake = remember(t.key) { Animatable(0f) }
    val glow = remember(t.key) { Animatable(0f) }
    val failedAtStart = remember(t.key) { t.phase == Phase.FAILED && !fresh }
    LaunchedEffect(t.phase) {
        when {
            t.phase == Phase.DONE && tick.value < 1f -> {
                launch { glow.snapTo(1f); glow.animateTo(0f, tween(1600)) }
                launch { pop.snapTo(0.6f); pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 520f)) }
                tick.animateTo(1f, tween(520, delayMillis = 120, easing = Glide))
            }
            t.phase == Phase.FAILED && !failedAtStart && !calm ->
                shake.animateTo(0f, keyframes {
                    durationMillis = 460
                    -7f at 60; 6f at 140; -4f at 220; 3f at 300; -1f at 380
                })
        }
    }
    Column(Modifier.drawBehind { if (glow.value > 0f) drawRect(tone.copy(alpha = 0.12f * glow.value)) }
        .padding(horizontal = 14.dp).padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).graphicsLayer { translationX = shake.value.dp.toPx(); scaleX = pop.value; scaleY = pop.value }
                .clip(CircleShape).background(tone.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                if (t.phase == Phase.DONE) Tick(tick.value, tone, Modifier.size(16.dp))
                else Icon(if (t.text != null) Glyph.Clip else if (t.dir == Dir.OUT) Glyph.Up else Glyph.Down, null, Modifier.size(14.dp), tint = tone)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = type(14, 600, p.fg, -0.016), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(t.note, style = type(12, 400, when (t.phase) { Phase.DONE -> p.ok; Phase.FAILED -> p.err; else -> p.fg2 }),
                    maxLines = if (t.phase == Phase.FAILED) 3 else 1, overflow = TextOverflow.Ellipsis)
            }
            // One action slot: cancel while it runs, then Open / Try again.
            when {
                t.phase == Phase.ACTIVE || t.phase == Phase.PENDING -> {
                    Spacer(Modifier.width(8.dp))
                    IconBtn(Glyph.Close, "Cancel") { a.cancel(t.key) }
                }
                t.phase == Phase.DONE && t.text != null -> { Spacer(Modifier.width(8.dp)); Btn("Copy", Kind.PRIMARY) { a.copy(t) } }
                t.phase == Phase.DONE && t.location != null -> { Spacer(Modifier.width(8.dp)); Btn("Open", Kind.PLAIN) { a.open(t) } }
                t.phase == Phase.FAILED && t.canRetry -> { Spacer(Modifier.width(8.dp)); Btn("Try again", Kind.PRIMARY) { a.retry(t.key) } }
            }
        }
        Column(Modifier.padding(start = 40.dp)) {
            if (t.text != null) {
                // The text itself, four lines until tapped.
                var open by rememberSaveable(t.key) { mutableStateOf(false) }
                Text(t.text.take(20_000), style = type(13, 400, p.fg),
                    maxLines = if (open) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(p.bg).clickable { open = !open }.padding(horizontal = 10.dp, vertical = 8.dp))
            }
            if (t.phase == Phase.ASKING) {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Decline", Kind.PLAIN, big = true, modifier = Modifier.weight(1f)) { a.answer(t.key, false) }
                    Btn("Accept", Kind.PRIMARY, big = true, modifier = Modifier.weight(1f)) { a.answer(t.key, true) }
                }
            }
            if (t.phase == Phase.ACTIVE) {
                val frac by animateFloatAsState((t.done.toFloat() / maxOf(1L, t.size)).coerceIn(0f, 1f), tween(200), label = "bar")
                // The filled part carries a light that sweeps along it, so a slow transfer still looks alive.
                val sweep = if (calm) -1f else {
                    val v by rememberInfiniteTransition(label = "shimmer").animateFloat(-0.4f, 1.4f,
                        infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "sweep")
                    v
                }
                Box(Modifier.padding(top = 9.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(p.fill)) {
                    Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(CircleShape).background(p.accent).drawWithContent {
                        drawContent()
                        if (sweep > -1f) {
                            val w = size.width
                            val x = sweep * w
                            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                                startX = x - 40.dp.toPx(), endX = x + 40.dp.toPx()))
                        }
                    })
                }
                Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${Engine.fmtBytes(t.done)} of ${Engine.fmtBytes(t.size)}", style = mono(11, 400, p.fg2))
                    Text(if (t.rate > 0) Engine.fmtRate(t.rate) else "—", style = mono(11, 600, p.accent))
                    Text(if (t.rate > 0) Engine.fmtEta((t.size - t.done) / t.rate) + " left" else "—", style = mono(11, 400, p.fg2))
                }
            }
        }
    }
}

private enum class Kind { PRIMARY, TINT, PLAIN }

/**
 * Buttons are rounded rectangles, not pills. PRIMARY is raised (lighter face,
 * bevelled edge); TINT is the accent at low strength with an accent outline;
 * PLAIN sits on the card with a lit hairline. Pressing sinks them slightly.
 */
@Composable
private fun Btn(text: String, kind: Kind, big: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, label = "btn")
    val face = when (kind) {
        Kind.PRIMARY -> Modifier.background(raised(if (pressed) lerp(p.accent, Color.Black, 0.12f) else p.accent)).border(1.dp, bevel(), BtnShape)
        Kind.TINT -> Modifier.background(if (pressed) p.accentLine else p.accentTint).border(1.dp, p.accentLine, BtnShape)
        Kind.PLAIN -> Modifier.background(if (pressed) p.fill else p.card).edge(BtnShape)
    }
    Box(
        modifier.scale(s).clip(BtnShape).then(face)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = if (big) 16.dp else 14.dp, vertical = if (big) 11.dp else 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        val ink = when (kind) { Kind.PRIMARY -> Color.White; Kind.TINT -> p.accent; Kind.PLAIN -> p.fg }
        Text(text, style = type(if (big) 15 else 13, 600, ink))
    }
}

@Composable
private fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, size: Int = 28, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(if (size > 30) 10.dp else 8.dp)
    Box(Modifier.size(size.dp).clip(shape).background(p.card).edge(shape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, Modifier.size((size * 0.47f).dp), tint = if (size > 30) p.fg else p.fg2)
    }
}

/** This phone is on 2.4 GHz Wi-Fi: the usual cap on speed, and an easy fix. */
@Composable
private fun SpeedTip(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().card().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text("Faster on 5 GHz Wi-Fi", style = type(15, 600, p.fg))
        Spacer(Modifier.height(3.dp))
        Text("This phone is on 2.4 GHz Wi-Fi. If your router has a 5 GHz network (often named with \"5G\"), " +
            "connect both devices to it — transfers are usually 3–5× faster.", style = type(13, 400, p.fg2))
        Spacer(Modifier.height(10.dp))
        Btn("Got it", Kind.PLAIN, onClick = onDismiss)
    }
}

@Composable
private fun Footer(openDownloads: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth().padding(top = 8.dp, start = 10.dp, end = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Received files are in Downloads › Ferry", style = type(12, 500, p.accent),
            modifier = Modifier.clip(CircleShape).clickable(onClick = openDownloads).padding(horizontal = 8.dp, vertical = 4.dp))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyph.Lock, null, Modifier.size(12.dp), tint = p.fg3)
            Spacer(Modifier.width(4.dp))
            Text("Files go straight between devices on this network.", style = type(11, 400, p.fg3))
        }
        Text("Nothing is uploaded or stored.", style = type(11, 400, p.fg3))
    }
}

/* -------------------------------------------------------------- pairing */

private fun fmtCode(c: String?) = if (c == null || c.length != 6) "––– –––" else "${c.take(3)} ${c.drop(3)}"

/** "Can't see your other device?" -- shown after a quiet spell with nobody around. */
@Composable
private fun PairPrompt(onPair: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().card().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text("Can't see your other device?", style = type(15, 600, p.fg))
        Spacer(Modifier.height(3.dp))
        Text("Some Wi-Fi setups hide devices from each other. Pair them with a code instead.", style = type(13, 400, p.fg2))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Pair with a code", Kind.PRIMARY, onClick = onPair)
            Btn("Not now", Kind.PLAIN, onClick = onDismiss)
        }
    }
}

/** The "Paired · code …  Stop" chip, or the quiet "Pair with a code" link. */
@Composable
private fun PairLine(s: UiState, open: Boolean, onPair: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (s.pairCode != null) {
            Row(
                Modifier.clip(BtnShape).background(p.card).edge(BtnShape).padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Paired · code ", style = type(12, 400, p.fg2))
                Text(fmtCode(s.pairCode), style = mono(12, 700, p.fg))
                Text("Stop", style = type(12, 500, p.accent),
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onStop).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        } else if (!s.pairPrompt && !open) {
            Link("Don't see your device? Pair with a code", onPair)
        }
    }
}

/** The pairing card, in place in the list (the panel's layout), not a dialog. */
@Composable
private fun PairCard(s: UiState, onJoin: (String) -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    var input by rememberSaveable { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    val found = s.pairFound
    // The other device turned up: say so, then get out of the way.
    LaunchedEffect(found.isNotEmpty()) {
        if (found.isNotEmpty()) { kotlinx.coroutines.delay(1400); onClose() }
    }
    val join = {
        val d = input.filter(Char::isDigit)
        if (d.length == 6) onJoin(d) else hint = "Enter the 6-digit code shown on the other device."
    }
    Box(Modifier.fillMaxWidth().card()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Pair with a code", style = type(16, 600, p.fg))
            Text("On your other device, open Ferry and enter this code.", style = type(13, 400, p.fg2), textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(fmtCode(s.pairCode), style = mono(36, 700, p.fg, 0.04))
            Text(
                hint ?: when {
                    found.isNotEmpty() -> "✓ Found ${found.joinToString(", ")}"
                    s.pairHosting -> "Waiting for the other device…"
                    else -> "Joining…"
                },
                style = type(13, if (found.isNotEmpty()) 600 else 400, if (found.isNotEmpty()) p.ok else p.fg2),
                textAlign = TextAlign.Center,
            )
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(p.strokeStrong))
                Text("  or enter a code from the other device  ", style = type(11, 400, p.fg3))
                Box(Modifier.weight(1f).height(1.dp).background(p.strokeStrong))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { v ->
                        val d = v.filter(Char::isDigit).take(6)
                        input = if (d.length > 3) "${d.take(3)} ${d.drop(3)}" else d
                        hint = null
                    },
                    singleLine = true,
                    placeholder = { Text("123 456", style = mono(18, 600, p.fg3).copy(textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth()) },
                    textStyle = mono(18, 600, p.fg).copy(textAlign = TextAlign.Center),
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { join() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = p.accent, unfocusedBorderColor = p.strokeStrong, cursorColor = p.accent,
                        focusedContainerColor = p.bg, unfocusedContainerColor = p.bg),
                    modifier = Modifier.width(150.dp),
                )
                Btn("Join", Kind.PRIMARY, big = true, onClick = join)
            }
            Spacer(Modifier.height(12.dp))
            Text("Both devices still need to be on the same Wi-Fi to send files.", style = type(11, 400, p.fg3), textAlign = TextAlign.Center)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) { IconBtn(Glyph.Close, "Close", onClick = onClose) }
    }
}

/* -------------------------------------------------------------- dialogs */

/**
 * A dialog that springs up into place and sinks away before it closes.
 * [content] gets `close { then }`: play the exit, then do `then`.
 */
@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable (close: (() -> Unit) -> Unit) -> Unit) {
    val calm = LocalCalm.current
    val shown = remember { MutableTransitionState(calm).apply { targetState = true } }
    var after by remember { mutableStateOf<(() -> Unit)?>(null) }
    val close: (() -> Unit) -> Unit = { then ->
        if (calm) then() else if (after == null) { after = then; shown.targetState = false }
    }
    LaunchedEffect(shown.isIdle, shown.currentState) {
        if (shown.isIdle && !shown.currentState) after?.invoke()
    }
    Dialog(onDismissRequest = { close(onDismiss) }) {
        AnimatedVisibility(
            shown,
            enter = fadeIn(tween(240)) + scaleIn(spring(dampingRatio = 0.74f, stiffness = 420f), initialScale = 0.9f) +
                slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it / 10 },
            exit = fadeOut(tween(170)) + scaleOut(tween(200, easing = Glide), targetScale = 0.95f) +
                slideOutVertically(tween(200)) { it / 20 },
        ) { SheetSurface { content(close) } }
    }
}

/** A dialog's card, on its own so tests can draw one (Robolectric never settles a text field inside a Dialog). */
@Composable
internal fun SheetSurface(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card)
            .edge(RoundedCornerShape(20.dp)).padding(horizontal = 22.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun InfoDialog(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    Sheet(onDismiss) { close ->
        Image(painterResource(R.drawable.logo_lg), null, Modifier.size(64.dp).shadow(6.dp, RoundedCornerShape(15.dp)).clip(RoundedCornerShape(15.dp)))
        Spacer(Modifier.height(12.dp))
        Text("Ferry", style = type(22, 700, p.fg, -0.028))
        Spacer(Modifier.height(6.dp))
        Text("Send files between devices on the same Wi-Fi. They travel straight from one device to the other — nothing is uploaded, nothing is kept.",
            style = type(14, 400, p.fg2), textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Section("Other devices don't need the app", "On an iPhone, Mac, Windows PC or another phone, open ferry.luitlabs.in in the browser. It appears here. On a computer with Chrome, Ferry for Chrome keeps it visible all the time.")
        Section("Your name", "Others see this phone by its device name. Tap the \"This phone\" card to change it.")
        Section("Light or dark", "The sun, moon and A buttons at the top pick light, dark, or automatic (follows your phone's setting).")
        Section("Share from any app", "In Photos, Files or anywhere else, tap Share → Ferry, then tap a device.")
        Section("Copy here, paste there", "Tap the clipboard button next to a device, paste, and send. It shows up on the other device with a Copy button. Links and text shared to Ferry from another app go the same way.")
        Section("Keeps going in the background", "Transfers carry on with the screen off or another app open; the notification shows progress.")
        Section("Faster transfers", "Speed is set by your Wi-Fi. Connect both devices to your router's 5 GHz network (often named with \"5G\") — usually 3–5× faster than 2.4 GHz — and keep them near the router.")
        Section("Why the same network", "Files never pass through a server, so the two devices have to reach each other directly. Guest Wi-Fi that isolates devices blocks that.")
        Spacer(Modifier.height(18.dp))
        Btn("Done", Kind.PRIMARY, big = true) { close(onDismiss) }
    }
}

@Composable
private fun Section(title: String, body: String) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(title, style = type(14, 600, p.fg))
        Spacer(Modifier.height(2.dp))
        Text(body, style = type(13, 400, p.fg2))
    }
}

@Composable
private fun RenameDialog(current: String, onDone: (String?) -> Unit) {
    val p = LocalPalette.current
    var value by rememberSaveable { mutableStateOf(current) }
    Sheet({ onDone(null) }) { close ->
        Text("Device name", style = type(19, 700, p.fg, -0.024))
        Spacer(Modifier.height(4.dp))
        Text("How this phone appears to others nearby.", style = type(13, 400, p.fg2), textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = value, onValueChange = { if (it.length <= 32) value = it }, singleLine = true,
            textStyle = type(16, 500, p.fg),
            shape = BtnShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { close { onDone(value) } }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.strokeStrong, cursorColor = p.accent),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Cancel", Kind.PLAIN, big = true, modifier = Modifier.weight(1f)) { close { onDone(null) } }
            Btn("Save", Kind.PRIMARY, big = true, modifier = Modifier.weight(1f)) { close { onDone(value) } }
        }
    }
}

/**
 * Send text (the clipboard). Paste fills the box from what's copied on this
 * phone; Android shows its own "pasted" notice when we read it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextDialog(peers: List<PeerUi>, preselect: String?, initial: String, readClipboard: () -> String?,
                       onDone: (peerId: String?, text: String?) -> Unit) {
    Sheet({ onDone(null, null) }) { close -> TextSheet(peers, preselect, initial, readClipboard) { id, text -> close { onDone(id, text) } } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TextSheet(peers: List<PeerUi>, preselect: String?, initial: String, readClipboard: () -> String?,
                       onDone: (peerId: String?, text: String?) -> Unit) {
    val p = LocalPalette.current
    var value by rememberSaveable { mutableStateOf(initial) }
    var to by rememberSaveable { mutableStateOf(preselect) }
    val target = to?.takeIf { id -> peers.any { it.id == id } } ?: peers.singleOrNull()?.id
    val over = value.length > com.dorxon.browserdrop.proto.Proto.TEXT_MAX
    run {
        Text("Send text", style = type(19, 700, p.fg, -0.024))
        Spacer(Modifier.height(4.dp))
        Text("Paste what you copied, or type it. On the other device, one tap copies it.",
            style = type(13, 400, p.fg2), textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = value, onValueChange = { value = it },
            textStyle = type(15, 400, p.fg),
            placeholder = { Text("Paste or type here", style = type(15, 400, p.fg3)) },
            shape = BtnShape, minLines = 4, maxLines = 8,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = p.accent, unfocusedBorderColor = p.strokeStrong, cursorColor = p.accent),
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Btn("Paste", Kind.PLAIN) { readClipboard()?.let { value = if (value.isEmpty()) it else value + it } }
            Spacer(Modifier.weight(1f))
            Text(if (over) "Too long — send it as a file" else if (value.isNotEmpty()) Engine.fmtChars(value.length) else "",
                style = mono(11, 400, if (over) p.err else p.fg3))
        }
        Text("SEND TO", style = type(11, 600, p.fg2, 0.08), modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp))
        if (peers.isEmpty()) Text("No devices nearby yet.", style = type(13, 400, p.fg2), modifier = Modifier.fillMaxWidth())
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            peers.forEach { peer -> Btn(peer.name, if (peer.id == target) Kind.PRIMARY else Kind.PLAIN) { to = peer.id } }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Cancel", Kind.PLAIN, big = true, modifier = Modifier.weight(1f)) { onDone(null, null) }
            val ready = value.isNotEmpty() && !over && target != null
            Btn("Send", Kind.PRIMARY, big = true, modifier = Modifier.weight(1f).then(if (ready) Modifier else Modifier.alpha(0.4f))) {
                if (ready) onDone(target, value)
            }
        }
    }
}
