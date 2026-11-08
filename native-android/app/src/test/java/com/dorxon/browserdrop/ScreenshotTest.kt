package com.dorxon.browserdrop

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.dorxon.browserdrop.proto.Dir
import com.dorxon.browserdrop.ui.Actions
import com.dorxon.browserdrop.ui.FerryTheme
import com.dorxon.browserdrop.ui.Screen
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real screen in representative states to build/outputs/roborazzi.
 * Not a pixel-diff gate -- it's how the UI gets looked at without a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val noop = Actions({}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})

    private val peers = listOf(
        PeerUi("a", "Studio Mac", "mac", null),
        PeerUi("b", "Living Room PC", "windows", null),
        PeerUi("c", "Arjun's iPhone", "iphone", null),
        PeerUi("d", "Maya's iPad", "ipad", null),
    )

    private fun tr(key: String, dir: Dir, phase: Phase, note: String, done: Long = 0, rate: Double = 0.0, retry: Boolean = false) =
        TransferUi(key, dir, if (dir == Dir.IN) "Goa trip 2026.mov" else "Quarterly review final.pdf", 734_003_200,
            "Studio Mac", phase, done, rate, note, if (phase == Phase.DONE && dir == Dir.IN) android.net.Uri.parse("content://x") else null,
            "video/quicktime", retry)

    private fun shot(name: String, s: UiState, dark: Boolean = false) {
        if (dark) RuntimeEnvironment.setQualifiers("+night")
        compose.mainClock.autoAdvance = false
        compose.setContent { FerryTheme { Screen(s, noop) } }
        compose.mainClock.advanceTimeBy(1800)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun empty() = shot("01-empty", UiState(name = "Maya's Pixel 9", connected = true, joined = true))

    @Test fun radar() = shot("02-radar", UiState(name = "Maya's Pixel 9", connected = true, joined = true, peers = peers))

    @Test fun asking() = shot("03-asking", UiState(name = "Maya's Pixel 9", connected = true, joined = true, peers = peers,
        transfers = listOf(tr("1", Dir.IN, Phase.ASKING, "Studio Mac wants to send 700 MB"))))

    @Test fun moving() = shot("04-moving", UiState(name = "Maya's Pixel 9", connected = true, joined = true,
        peers = peers.mapIndexed { i, p -> if (i == 0) p.copy(progress = 0.42f) else p },
        transfers = listOf(
            tr("2", Dir.IN, Phase.ACTIVE, "42% · from Studio Mac", done = 308_281_344, rate = 38_400_000.0),
            tr("1", Dir.OUT, Phase.FAILED, "Studio Mac declined"),
        )))

    @Test fun finished() = shot("05-finished", UiState(name = "Maya's Pixel 9", connected = true, joined = true, peers = peers,
        transfers = listOf(
            tr("3", Dir.IN, Phase.DONE, "Received · 700 MB in 18.2s"),
            tr("2", Dir.OUT, Phase.DONE, "Sent · 700 MB in 17.9s"),
            tr("1", Dir.OUT, Phase.FAILED, "Direct connection timed out — both devices must be on the same network", retry = true),
        )))

    @Test fun dark() = shot("06-dark", UiState(name = "Maya's Pixel 9", connected = true, joined = true,
        peers = peers + PeerUi("e", "Kitchen Chromebook", "desktop", null),
        shared = listOf("IMG_2041.HEIC", "IMG_2042.HEIC")), dark = true)

    @Test fun pairPrompt() = shot("08-pair-prompt", UiState(name = "Maya's Pixel 9", connected = true, joined = true, pairPrompt = true))

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun pairDialog() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FerryTheme {
                Screen(UiState(name = "Maya's Pixel 9", connected = true, joined = true, pairPrompt = true,
                    pairCode = "784949", pairHosting = true), noop)
            }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("Pair with a code").performClick()   // the prompt's button opens the dialog
        compose.mainClock.advanceTimeBy(800)
        captureScreenRoboImage("build/outputs/roborazzi/09-pair-card.png")
    }

    @Test fun paired() = shot("10-paired", UiState(name = "Maya's Pixel 9", connected = true, joined = true,
        peers = listOf(PeerUi("x", "Studio PC", "windows", null, paired = true)), pairCode = "784949", pairFound = listOf("Studio PC")))

    @Test fun speedTip() = shot("11-speed-tip", UiState(name = "Maya's Pixel 9", connected = true, joined = true,
        peers = peers, speedTip = true,
        transfers = listOf(tr("3", Dir.IN, Phase.DONE, "Received · 700 MB in 2:21"))))

    @Test fun darkMoving() = shot("12-dark-moving", UiState(name = "Maya's Pixel 9", connected = true, joined = true,
        peers = peers.mapIndexed { i, p -> if (i == 0) p.copy(progress = 0.42f) else p },
        transfers = listOf(
            tr("3", Dir.IN, Phase.ASKING, "Arjun's iPhone wants to send 700 MB"),
            tr("2", Dir.IN, Phase.ACTIVE, "42% · from Studio Mac", done = 308_281_344, rate = 38_400_000.0),
        )), dark = true)

    @Test fun text() = shot("13-text", UiState(name = "Maya's Pixel 9", connected = true, joined = true, peers = peers.take(2),
        transfers = listOf(
            TransferUi("5", Dir.IN, "Wi-Fi password for the studio: lantern-orbit-42", 46, "Studio Mac", Phase.DONE, 46, 0.0,
                "Received · 46 characters · from Studio Mac", null, null, false,
                text = "Wi-Fi password for the studio: lantern-orbit-42"),
            TransferUi("4", Dir.OUT, "https://maps.app.goo.gl/Xy7Qp2", 300, "Living Room PC", Phase.DONE, 300, 0.0,
                "Sent · 300 characters · to Living Room PC", null, null, false,
                text = "https://maps.app.goo.gl/Xy7Qp2\nMeet at the north gate at 7. Bring the long lens and the spare batteries; " +
                    "the light goes fast after sunset and we want the bridge before the lamps come on. Parking is on the left " +
                    "past the second roundabout, and the gate code is 4471."),
            tr("1", Dir.IN, Phase.DONE, "Received · 700 MB in 18.2s"),
        )))

    /** The send-text sheet's card (drawn without its Dialog window; see SheetSurface). */
    @Test fun textSheet() {
        compose.setContent {
            FerryTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(24.dp)) {
                    com.dorxon.browserdrop.ui.SheetSurface {
                        com.dorxon.browserdrop.ui.TextSheet(peers.take(2), "a", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", { null }) { _, _ -> }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/14-text-sheet.png")
    }

    /** Motion: a transfer landing (burst on the device, tick drawing in its row) and a device arriving. */
    @Test fun landing() {
        val before = UiState(name = "Maya's Pixel 9", connected = true, joined = true, peers = peers.take(2).mapIndexed { i, p -> if (i == 0) p.copy(progress = 0.97f) else p },
            transfers = listOf(tr("2", Dir.IN, Phase.ACTIVE, "97% · from Studio Mac", done = 712_003_200, rate = 38_400_000.0)))
        val after = before.copy(peers = peers.take(3),
            transfers = listOf(tr("2", Dir.IN, Phase.DONE, "Received · 700 MB in 18.2s")))
        val state = androidx.compose.runtime.mutableStateOf(before)
        compose.mainClock.autoAdvance = false
        compose.setContent { FerryTheme { Screen(state.value, noop) } }
        compose.mainClock.advanceTimeBy(1800)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/15-landing-before.png")
        state.value = after
        compose.mainClock.advanceTimeBy(320)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/16-landing-mid.png")
        compose.mainClock.advanceTimeBy(1800)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/17-landing-after.png")
    }

    @Test fun crowded() = shot("07-crowded", UiState(name = "Maya's Pixel 9", connected = true, joined = true,
        peers = (1..9).map { PeerUi("p$it", "Device $it", listOf("mac", "windows", "iphone", "android", "ipad")[it % 5], null) }))
}
