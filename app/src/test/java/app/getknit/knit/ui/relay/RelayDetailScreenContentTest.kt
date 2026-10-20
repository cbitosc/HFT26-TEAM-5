package app.getknit.knit.ui.relay

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.relay.FreeUploadChoice
import app.getknit.knit.mesh.spool.CommonsInvite
import app.getknit.knit.mesh.spool.KeyAnswer
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * A relay's own screen: what its connection, calls, tickets and room say, that each verb reports this relay, and that
 * Remove asks first. The list row's behaviours are [InternetRelayScreenContentTest]'s.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RelayDetailScreenContentTest {
    private companion object {
        /** The §13 fixture invite — 32 bytes, unpadded base64url, the daemon's own grammar. */
        const val INVITE = "knit-commons:v1:ChEYHyYtNDtCSVBXXmVsc3qBiI-WnaSrsrnAx87V3OM"

        const val HOST = "lab.lax.spool.getknit.app"
    }

    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        relay: RelayRow,
        planeEnabled: Boolean = true,
        offline: Boolean = false,
        onSetEnabled: (Boolean) -> Unit = {},
        onRemove: () -> Unit = {},
        onJoinCommons: (String) -> Unit = {},
        onLeaveCommons: (String) -> Unit = {},
        onShareInvite: () -> Unit = {},
        onCopyInvite: () -> Unit = {},
        onSetFreeUploads: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                RelayDetailScreenContent(
                    relay = relay,
                    planeEnabled = planeEnabled,
                    offline = offline,
                    onBack = {},
                    onSetEnabled = onSetEnabled,
                    onRemove = onRemove,
                    onJoinCommons = onJoinCommons,
                    onLeaveCommons = onLeaveCommons,
                    isValidInvite = { CommonsInvite.looksLikeInvite(it) },
                    onShareInvite = onShareInvite,
                    onCopyInvite = onCopyInvite,
                    onSetFreeUploads = onSetFreeUploads,
                )
            }
        }
    }

    private fun relay(
        enabled: Boolean = true,
        connected: Boolean = true,
        scopeCount: Int? = if (connected) 3 else null,
        lastError: String? = null,
        commons: RelayCommons? = null,
        calls: RelayCalls? = null,
        account: RelayAccount? = null,
        freeUploads: FreeUploadChoice? = null,
        maxAttachBytes: Int? = if (connected) 26_214_400 else null,
        software: String? = null,
        powBits: Int? = if (connected) 0 else null,
    ) = RelayRow(
        url = "wss://$HOST/spool/v1",
        host = HOST,
        enabled = enabled,
        connected = connected,
        scopeCount = scopeCount,
        carriesPhotos = maxAttachBytes?.let { true } ?: if (connected) false else null,
        lastError = lastError,
        commons = commons,
        calls = calls,
        account = account,
        freeUploads = freeUploads,
        maxAttachBytes = maxAttachBytes,
        software = software,
        powBits = powBits,
    )

    @Test
    fun aConnectedRelayStatesWhatItsConnectionSays() {
        render(relay(software = "knit-spool 0.9.0 (e8a7790)"))
        compose.onNodeWithText(HOST).assertIsDisplayed()
        compose.onNodeWithText("Connected · 3 conversations").assertIsDisplayed()
        compose.onNodeWithText("Up to 26 MB each").assertIsDisplayed()
        compose.onNodeWithText("Not asked").assertIsDisplayed()
        compose.onNodeWithText("knit-spool 0.9.0 (e8a7790)").assertIsDisplayed()
    }

    @Test
    fun aFramesOnlyRelayWhoAsksForWorkSaysBoth() {
        render(relay(maxAttachBytes = null, powBits = 12))
        compose.onNodeWithText("Messages only").assertIsDisplayed()
        compose.onNodeWithText("12 bits").assertIsDisplayed()
        // An unnamed build is no row rather than an empty one.
        compose.onAllNodesWithText("Software").assertCountEquals(0)
    }

    @Test
    fun aRelayThatIsDownSaysWhyAndPromisesNoFigures() {
        render(relay(connected = false, lastError = "unreachable"))
        compose.onNodeWithText("Cannot be reached").assertIsDisplayed()
        compose.onNodeWithText("The rest shows here once it connects.").assertIsDisplayed()
        compose.onAllNodesWithText("Conversations").assertCountEquals(0)
    }

    @Test
    fun theSwitchReportsTheRequestedState() {
        val flipped = mutableListOf<Boolean>()
        render(relay(), onSetEnabled = { flipped += it })
        compose.onNodeWithTag("relay_detail_switch").assertIsOn().performClick()
        assertEquals(listOf(false), flipped)
    }

    @Test
    fun theSwitchCannotBeFlippedWhileThePlaneIsOff() {
        val flipped = mutableListOf<Boolean>()
        render(relay(), planeEnabled = false, onSetEnabled = { flipped += it })
        compose.onNodeWithTag("relay_detail_switch").assertIsNotEnabled().performClick()
        assertEquals(emptyList<Boolean>(), flipped)
        compose.onNodeWithText("Not in use").assertIsDisplayed()
    }

    @Test
    fun aParkedRelayReadsAsParked() {
        render(relay(enabled = false, connected = false))
        compose.onNodeWithTag("relay_detail_switch").assertIsOff()
        compose.onNodeWithText("Turned off").assertIsDisplayed()
    }

    @Test
    fun aRelayWithCallFeaturesNamesThemItsTicketAndItsKey() {
        render(
            relay(
                calls =
                    RelayCalls(
                        turn = true,
                        stunServers = 2,
                        walkie = true,
                        ticket = RelayTicket.Live(exp = 1_800_086_400L, turn = true, walkie = true),
                    ),
                account = RelayAccount("issuer.example.org", KeyAnswer.OK, endsAt = 1_801_000_000_000L, failures = 0),
            ),
        )
        compose.onNodeWithTag("relay_calls_$HOST").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Offered").assertCountEquals(2)
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("Ticket good until", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Unlocks TURN, walkie").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ticket key from issuer.example.org · account ends", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aRelayThatOffersCallsNothingHasNoCallsSection() {
        render(relay(calls = RelayCalls(turn = false, stunServers = 0, walkie = false, ticket = RelayTicket.None)))
        compose.onAllNodesWithTag("relay_calls_$HOST").assertCountEquals(0)
        // Its ticket still has a place.
        compose.onNodeWithText("No ticket").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aFilesTicketNamesFilesItsLimitsAndASpentDay() {
        render(
            relay(
                calls =
                    RelayCalls(
                        turn = true,
                        stunServers = 1,
                        walkie = true,
                        ticket =
                            RelayTicket.Live(
                                exp = 1_800_086_400L,
                                turn = true,
                                walkie = true,
                                files = true,
                                ab = 50_000_000L,
                                am = 2_000_000L,
                            ),
                        uploadsSpent = true,
                    ),
            ),
        )
        compose.onNodeWithText("Unlocks TURN, walkie, files").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Files up to 2.0 MB each, 50 MB a day").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Today's uploads here are used up").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aTicketedRelayWithoutATicketSaysWhatTurnNeeds() {
        render(
            relay(
                calls = RelayCalls(turn = true, stunServers = 1, walkie = false, ticket = RelayTicket.None),
                account = RelayAccount("issuer.example.org", KeyAnswer.UNKNOWN_KEY, endsAt = null, failures = 0),
            ),
        )
        compose.onNodeWithText("No ticket — TURN here needs one").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("issuer.example.org doesn't recognise this ticket key").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aDownRelaysKeyStillShowsWithWhatItsIssuerSaid() {
        // The key is not a fact about the connection, so it shows while the relay is down.
        render(
            relay(
                connected = false,
                account = RelayAccount("issuer.example.org", answer = null, endsAt = null, failures = 3),
            ),
        )
        compose.onNodeWithText("Ticket key from issuer.example.org · not answering").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theFreeTicketsSwitchNamesItsIssuerAndReportsTheAnswer() {
        val answers = mutableListOf<Boolean>()
        render(relay(freeUploads = FreeUploadChoice("free.example.org", allowed = null)), onSetFreeUploads = { answers += it })
        compose.onNodeWithText("Fetched from free.example.org on days this phone sends files here").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("relay_free_uploads_$HOST").assertIsOff().performClick()
        assertEquals(listOf(true), answers)
    }

    @Test
    fun aRelayWithoutACommonsOffersNothingToJoin() {
        render(relay())
        compose.onAllNodesWithText("Join").assertCountEquals(0)
        compose.onAllNodesWithText("Leave").assertCountEquals(0)
    }

    @Test
    fun aRelayThatRunsACommonsShowsItsNameAndAJoinThatTakesOnlyARealInvite() {
        val joined = mutableListOf<String>()
        render(relay(commons = RelayCommons(name = "Home", joinedId = null)), onJoinCommons = { joined += it })
        compose.onNodeWithText("Commons · Home").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("relay_commons_join_$HOST").performClick()
        // The invite is the room's whole key: a near-miss is refused at the field, never turned into a scope id.
        compose.onNodeWithTag("relay_commons_invite_field").performTextInput("knit-commons:v1:not-really")
        compose.onNodeWithText("That is not a commons invite").assertIsDisplayed()
        compose.onNodeWithTag("relay_commons_join_confirm").assertIsNotEnabled()
        assertEquals(emptyList<String>(), joined)
        compose.onNodeWithTag("relay_commons_invite_field").performTextClearance()
        compose.onNodeWithTag("relay_commons_invite_field").performTextInput(INVITE)
        compose.onNodeWithTag("relay_commons_join_confirm").performClick()
        assertEquals(listOf(INVITE), joined)
    }

    @Test
    fun aJoinedCommonsOffersLeaveAndNamesWhatStays() {
        val left = mutableListOf<String>()
        render(relay(commons = RelayCommons(name = null, joinedId = "c-abc")), onLeaveCommons = { left += it })
        // No operator name: the generic title stands in.
        compose.onNodeWithText("Commons · Commons").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("relay_commons_leave_$HOST").performClick()
        compose.onNodeWithText("The people in it stay in your contacts.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("relay_commons_leave_confirm").performClick()
        assertEquals(listOf("c-abc"), left)
    }

    @Test
    fun theShareMenuOffersShareAndCopy() {
        var shared = 0
        var copied = 0
        render(relay(), onShareInvite = { shared++ }, onCopyInvite = { copied++ })
        compose.onNodeWithTag("relay_invite_$HOST").performClick()
        compose.onNodeWithTag("relay_invite_share_$HOST").performClick()
        assertEquals(1 to 0, shared to copied)
        compose.onNodeWithTag("relay_invite_$HOST").performClick()
        compose.onNodeWithTag("relay_invite_copy_$HOST").performClick()
        assertEquals(1 to 1, shared to copied)
    }

    @Test
    fun removeAsksFirstAndAParkedRelayCanStillGo() {
        var removed = 0
        render(relay(enabled = false, connected = false), onRemove = { removed++ })
        compose.onNodeWithTag("relay_remove").performScrollTo().performClick()
        // Nothing goes until the dialog's confirm, which names the relay.
        assertEquals(0, removed)
        compose.onAllNodesWithText(HOST).assertCountEquals(2)
        compose.onNodeWithTag("relay_remove_confirm").performClick()
        assertEquals(1, removed)
    }
}
