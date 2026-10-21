package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.relay.InternetRelayScreenEmptyPreview
import app.getknit.knit.ui.relay.InternetRelayScreenOnPreview
import app.getknit.knit.ui.relay.RelayDetailScreenDisconnectedPreview
import app.getknit.knit.ui.relay.RelayDetailScreenParkedPreview
import app.getknit.knit.ui.relay.RelayDetailScreenPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.relay`.

@PreviewTest
@ScreenShots
@Composable
fun InternetRelayScreenOn() = InternetRelayScreenOnPreview()

@PreviewTest
@ScreenShots
@Composable
fun InternetRelayScreenEmpty() = InternetRelayScreenEmptyPreview()

@PreviewTest
@ScreenShots
@Composable
fun RelayDetailScreen() = RelayDetailScreenPreview()

@PreviewTest
@ScreenShots
@Composable
fun RelayDetailScreenDisconnected() = RelayDetailScreenDisconnectedPreview()

@PreviewTest
@ScreenShots
@Composable
fun RelayDetailScreenParked() = RelayDetailScreenParkedPreview()
