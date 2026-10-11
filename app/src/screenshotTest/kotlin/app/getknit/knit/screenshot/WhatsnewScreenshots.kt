package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.whatsnew.WhatsNewCardPreview
import app.getknit.knit.ui.whatsnew.WhatsNewScreenPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.whatsnew`.

@PreviewTest
@ComponentShots
@Composable
fun WhatsNewCard() = WhatsNewCardPreview()

@PreviewTest
@ComponentShots
@Composable
fun WhatsNewScreen() = WhatsNewScreenPreview()
