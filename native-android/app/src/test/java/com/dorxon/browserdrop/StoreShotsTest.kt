package com.dorxon.browserdrop

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.dorxon.browserdrop.proto.Dir
import com.dorxon.browserdrop.ui.Actions
import com.dorxon.browserdrop.ui.FerryTheme
import com.dorxon.browserdrop.ui.Screen
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The real screen in the states shown on the Play listing, with made-up devices.
 * `./gradlew recordRoborazziDebug --tests '*StoreShotsTest*'` writes them to
 * build/outputs/roborazzi/store-*.png; store/play/render.py frames them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class StoreShotsTest {
    @get:Rule val compose = createComposeRule()

    private val noop = Actions({}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})
    private val me = "Maya's Pixel 9"

    // Real-looking ids, so each device shows a tag like a real one does.
    private val mac = PeerUi("7f3a91c2d4e5f60718293a4b5c6d7e8f", "Studio Mac", "mac", null)
    private val pc = PeerUi("b21e0c9d8a7f6e5d4c3b2a1908f7e6d5", "Living Room PC", "windows", null)
    private val iphone = PeerUi("c94d5e6f708192a3b4c5d6e7f8091a2b", "Arjun's iPhone", "iphone", null)
    private val ipad = PeerUi("e05b1a2b3c4d5e6f708192a3b4c5d6e7", "Maya's iPad", "ipad", null)
    private val peers = listOf(mac, pc, iphone, ipad)

    private fun file(key: String, dir: Dir, name: String, size: Long, from: String, phase: Phase, note: String,
                     done: Long = 0, rate: Double = 0.0) =
        TransferUi(key, dir, name, size, from, phase, done, rate, note,
            if (phase == Phase.DONE && dir == Dir.IN) android.net.Uri.parse("content://x") else null, null, false)

    private fun text(key: String, dir: Dir, body: String, peer: String, note: String) =
        TransferUi(key, dir, body.lineSequence().first(), body.length.toLong(), peer, Phase.DONE, body.length.toLong(), 0.0,
            note, null, null, false, text = body)

    private fun shot(name: String, s: UiState, dark: Boolean = false) {
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        compose.mainClock.autoAdvance = false
        compose.setContent { FerryTheme { Screen(s, noop) } }
        compose.mainClock.advanceTimeBy(1800)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/store-$name.png")
    }

    private val video = 734_003_200L

    @Test fun devices() = shot("1-devices", UiState(name = me, connected = true, joined = true, peers = peers,
        transfers = listOf(
            file("2", Dir.IN, "IMG_4021.HEIC", 3_355_443, "Arjun's iPhone", Phase.DONE, "Received · 3.2 MB in 0.3s"),
            file("1", Dir.OUT, "Project brief.pdf", 1_887_437, "Studio Mac", Phase.DONE, "Sent · 1.8 MB in 0.4s"),
        )))

    @Test fun asking() = shot("2-asking", UiState(name = me, connected = true, joined = true, peers = peers,
        transfers = listOf(file("1", Dir.IN, "Goa trip 2026.mov", video, "Studio Mac", Phase.ASKING, "Studio Mac wants to send 700 MB"))))

    @Test fun moving() = shot("3-moving", UiState(name = me, connected = true, joined = true,
        peers = listOf(mac.copy(progress = 0.62f), pc, iphone, ipad),
        transfers = listOf(
            file("2", Dir.IN, "Goa trip 2026.mov", video, "Studio Mac", Phase.ACTIVE, "62% · from Studio Mac",
                done = (video * 0.62).toLong(), rate = 38_400_000.0),
            file("1", Dir.IN, "IMG_4021.HEIC", 3_355_443, "Arjun's iPhone", Phase.DONE, "Received · 3.2 MB in 0.3s"),
        )))

    @Test fun clipboard() = shot("4-text", UiState(name = me, connected = true, joined = true, peers = listOf(mac, pc),
        transfers = listOf(
            text("3", Dir.IN, "Wi-Fi password for the studio: lantern-orbit-42", "Studio Mac",
                "Received · 46 characters · from Studio Mac"),
            text("2", Dir.OUT, "https://maps.app.goo.gl/Xy7Qp2\nMeet at the north gate at 7. The gate code is 4471.",
                "Living Room PC", "Sent · 83 characters · to Living Room PC"),
            file("1", Dir.IN, "Goa trip 2026.mov", video, "Studio Mac", Phase.DONE, "Received · 700 MB in 18.2s"),
        )))

    @Test fun dark() = shot("5-dark", UiState(name = me, connected = true, joined = true,
        peers = listOf(mac.copy(progress = 0.42f), pc, iphone, ipad),
        transfers = listOf(
            file("3", Dir.IN, "Concert.mp4", 1_288_490_188, "Arjun's iPhone", Phase.ASKING, "Arjun's iPhone wants to send 1.2 GB"),
            file("2", Dir.IN, "Goa trip 2026.mov", video, "Studio Mac", Phase.ACTIVE, "42% · from Studio Mac",
                done = (video * 0.42).toLong(), rate = 38_400_000.0),
        )), dark = true)

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun pair() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FerryTheme { Screen(UiState(name = me, connected = true, joined = true, pairPrompt = true, pairCode = "784949", pairHosting = true), noop) }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("Pair with a code").performClick()
        compose.mainClock.advanceTimeBy(800)
        captureScreenRoboImage("build/outputs/roborazzi/store-6-pair.png")
    }
}
