package app.getknit.knit.di

import android.app.Application
import android.content.Context
import app.getknit.knit.call.CallSignals
import app.getknit.knit.call.CallWalkie
import app.getknit.knit.call.TurnSource
import app.getknit.knit.data.crypto.KeystoreSecret
import app.getknit.knit.data.settings.ContributionJournal
import app.getknit.knit.data.settings.InboundSettings
import app.getknit.knit.data.settings.ModelLoadJournal
import app.getknit.knit.fieldlog.FieldJournal
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.mesh.RadioSupport
import app.getknit.knit.moderation.TextModerator
import com.google.crypto.tink.KeysetHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.test.verify.verify
import java.io.File

/**
 * The whole production graph — the four modules `KnitApplication` starts — checked without building it:
 * every constructor parameter of every definition must resolve to another definition. A type a module never
 * provides is a crash at first use on the phone (or, in a test, an exception that surfaces as the *next*
 * class's failure, `testing.md`'s `UncaughtExceptionsBeforeTest`), so it fails here instead, named.
 *
 * Koin's verifier reads constructors, not the lambdas, so a parameter the module passes by hand rather than
 * through `get()` is listed in [extraTypes] with the reason it is not a definition.
 */
class KoinGraphVerifyTest {
    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun everyDefinitionResolves() {
        // Plus the voice calls' graph where this variant wires one (di/CallWiring; unit tests build the debug one).
        val calls = listOfNotNull(callModule()).toTypedArray()
        module { includes(appModule, meshModule, moderationModule, uiModule, *calls) }.verify(extraTypes = extraTypes)
    }

    private companion object {
        val extraTypes =
            listOf(
                // androidContext() — supplied by startKoin, not a definition.
                Context::class,
                Application::class,
                // Lambdas and values a module writes inline: EmojiCatalogLoader's asset opener and glyph test,
                // LinkPreviewService's screening hooks, ContactImporter's plane flag, CrashStore's directory,
                // ThemePreferences' dynamic-colour flow, and WalkieTalk's send (peer, chunk, cast flag, continuation).
                Function0::class,
                Function1::class,
                Function2::class,
                Function3::class,
                Function4::class,
                Boolean::class,
                File::class,
                Flow::class,
                // Built in the lambda itself: the identity file's Keystore wrap, MessageCrypto's keysets (read off
                // IdentityKeyStore), ScopedTextModerator's per-scope screeners, Diagnostics' RadioSupport.probe.
                KeystoreSecret::class,
                KeysetHandle::class,
                TextModerator::class,
                RadioSupport::class,
                // The field log's journal, built over its noBackupFilesDir directory in the FieldLog definition.
                FieldJournal::class,
                // Narrow interfaces a module satisfies with a concrete singleton it already holds:
                // `get<SettingsStore>()` as InboundSettings / ContributionJournal / ModelLoadJournal,
                // `get<Identity>()` as IdentitySource.
                InboundSettings::class,
                ContributionJournal::class,
                ModelLoadJournal::class,
                IdentitySource::class,
                // The voice calls' seams, written inline over MeshManager so building the call graph builds nothing
                // else (di/CallWiring), and the ring's alert timeout.
                CallSignals::class,
                TurnSource::class,
                Long::class,
                // The direct leg's link, handed to the media as a field of the DirectCallPath it already holds.
                StateFlow::class,
                // The walkie's seam over the same late-bound reads (§3.11).
                CallWalkie::class,
            )
    }
}
