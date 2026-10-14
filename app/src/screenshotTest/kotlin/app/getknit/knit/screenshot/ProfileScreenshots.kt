package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.profile.AvatarCropDialogPreview
import app.getknit.knit.ui.profile.BadgeSheetContentPreview
import app.getknit.knit.ui.profile.OpenToChatRowPreview
import app.getknit.knit.ui.profile.ProfileDetailsScreenBlockedPreview
import app.getknit.knit.ui.profile.ProfileDetailsScreenIndirectPreview
import app.getknit.knit.ui.profile.ProfileDetailsScreenNoKeyPreview
import app.getknit.knit.ui.profile.ProfileDetailsScreenOnlineVerifiedPreview
import app.getknit.knit.ui.profile.ProfileDetailsScreenSupporterPreview
import app.getknit.knit.ui.profile.ProfileDetailsScreenViaRelayPreview
import app.getknit.knit.ui.profile.ProfileScreenNewUserPreview
import app.getknit.knit.ui.profile.ProfileScreenPreview
import app.getknit.knit.ui.profile.RemoveContactDialogPreview
import app.getknit.knit.ui.profile.SupporterBadgeRowPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.profile`.

@PreviewTest
@ComponentShots
@Composable
fun AvatarCropDialog() = AvatarCropDialogPreview()

@PreviewTest
@ScreenShots
@Composable
fun ProfileDetailsScreenOnlineVerified() = ProfileDetailsScreenOnlineVerifiedPreview()

@PreviewTest
@ScreenShots
@Composable
fun ProfileDetailsScreenIndirect() = ProfileDetailsScreenIndirectPreview()

@PreviewTest
@ScreenShots
@Composable
fun ProfileDetailsScreenViaRelay() = ProfileDetailsScreenViaRelayPreview()

@PreviewTest
@ScreenShots
@Composable
fun ProfileDetailsScreenSupporter() = ProfileDetailsScreenSupporterPreview()

@PreviewTest
@ScreenShots
@Composable
fun ProfileDetailsScreenNoKey() = ProfileDetailsScreenNoKeyPreview()

@PreviewTest
@ScreenShots
@Composable
fun ProfileDetailsScreenBlocked() = ProfileDetailsScreenBlockedPreview()

@PreviewTest
@ComponentShots
@Composable
fun RemoveContactDialog() = RemoveContactDialogPreview()

@PreviewTest
@ComponentShots
@Composable
fun BadgeSheetContent() = BadgeSheetContentPreview()

@PreviewTest
@ComponentShots
@Composable
fun SupporterBadgeRow() = SupporterBadgeRowPreview()

@PreviewTest
@ComponentShots
@Composable
fun OpenToChatRow() = OpenToChatRowPreview()

@PreviewTest
@ScreenShots
@Composable
fun ProfileScreen() = ProfileScreenPreview()

@PreviewTest
@ScreenShots
@Composable
fun ProfileScreenNewUser() = ProfileScreenNewUserPreview()
