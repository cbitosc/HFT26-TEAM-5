package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.callsettings.CallSettingsScreenIncomingPreview
import app.getknit.knit.ui.callsettings.CallSettingsScreenOwnServersPreview
import app.getknit.knit.ui.callsettings.CallSettingsScreenPreview
import app.getknit.knit.ui.callsettings.CallSettingsScreenTranscriptsPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.callsettings`.

@PreviewTest
@ScreenShots
@Composable
fun CallSettingsScreen() = CallSettingsScreenPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallSettingsScreenIncoming() = CallSettingsScreenIncomingPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallSettingsScreenTranscripts() = CallSettingsScreenTranscriptsPreview()

@PreviewTest
@ScreenShots
@Composable
fun CallSettingsScreenOwnServers() = CallSettingsScreenOwnServersPreview()
