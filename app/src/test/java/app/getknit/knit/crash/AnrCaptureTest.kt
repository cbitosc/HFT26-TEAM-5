package app.getknit.knit.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager

/**
 * The platform's record of an earlier process's ANR becomes a Diagnostics report (ADR 2026-10.fzsy): only the
 * ANRs among the exits, with the thread dump the platform kept, and one that ran before this install says so instead
 * of naming this build. Through the real [CrashStore], so what is checked is the report a person would read.
 */
@RunWith(AndroidJUnit4::class)
class AnrCaptureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: CrashStore

    @Before
    fun setUp() {
        store = CrashStore(tmp.newFolder("crashes"))
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).lastUpdateTime = INSTALLED_AT
    }

    private fun exited(
        reason: Int,
        at: Long,
        description: String = "executing service app.getknit.knit/.mesh.MeshService",
        trace: String? = null,
    ) {
        val info =
            ShadowActivityManager.ApplicationExitInfoBuilder
                .newBuilder()
                .setProcessName(context.packageName)
                .setReason(reason)
                .setTimestamp(at)
                .setDescription(description)
                .apply { if (trace != null) setTraceInputStream(trace.byteInputStream()) }
                .build()
        shadowOf(context.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(info)
    }

    private fun import() = importAnrs(context, store, testEnvironment())

    @Test
    fun noRecordedExitImportsNothing() {
        assertEquals(0, import())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun onlyTheAnrsAmongTheExitsAreImported() {
        exited(ApplicationExitInfo.REASON_CRASH, at = INSTALLED_AT + 1_000L)
        exited(ApplicationExitInfo.REASON_ANR, at = INSTALLED_AT + 2_000L)
        exited(ApplicationExitInfo.REASON_LOW_MEMORY, at = INSTALLED_AT + 3_000L)

        assertEquals(1, import())
        assertEquals(listOf(INSTALLED_AT + 2_000L), store.list().map { it.at })
    }

    @Test
    fun theDumpThePlatformKeptIsTheReportsBody() {
        exited(ApplicationExitInfo.REASON_ANR, at = INSTALLED_AT + 1_000L, trace = MAIN_IDLE)

        assertEquals(1, import())
        val ref = store.latest()!!
        assertEquals("ApplicationNotResponding at Native method", ref.summary)
        assertTrue(ref.file.readText().contains("android.os.MessageQueue.nativePollOnce"))
    }

    @Test
    fun withoutADumpTheReportCarriesThePlatformsDescription() {
        exited(ApplicationExitInfo.REASON_ANR, at = INSTALLED_AT + 1_000L, description = "Input dispatching timed out")

        assertEquals(1, import())
        assertTrue(
            store
                .latest()!!
                .file
                .readText()
                .contains("ApplicationNotResponding: Input dispatching timed out"),
        )
    }

    @Test
    fun anAnrFromBeforeThisInstallNamesNoVersionAndOneAfterItDoes() {
        exited(ApplicationExitInfo.REASON_ANR, at = INSTALLED_AT - 1L, trace = MAIN_IDLE)
        assertEquals(1, import())
        val earlier = store.latest()!!
        assertTrue(earlier.appVersion, earlier.appVersion.startsWith("unknown"))

        exited(ApplicationExitInfo.REASON_ANR, at = INSTALLED_AT + 1L, trace = MAIN_IDLE)
        assertEquals(1, import())
        val current = store.latest()!!
        assertEquals(INSTALLED_AT + 1L, current.at)
        assertFalse(current.appVersion, current.appVersion.startsWith("unknown"))
        assertEquals("2.3.0 (13) debug", current.appVersion)
    }

    @Test
    fun anAnrAlreadyImportedIsNotImportedOnTheNextStart() {
        exited(ApplicationExitInfo.REASON_ANR, at = INSTALLED_AT + 1_000L, trace = MAIN_IDLE)
        assertEquals(1, import())

        assertEquals(0, import())
        assertEquals(1, store.list().size)
    }

    private companion object {
        const val INSTALLED_AT = 1_700_000_000_000L

        val MAIN_IDLE =
            """
            ----- pid 4242 at 2026-10-01 12:00:00 -----
            "main" prio=5 tid=1 Native
              at android.os.MessageQueue.nativePollOnce(Native method)
              at android.os.MessageQueue.next(MessageQueue.java:335)
              at android.os.Looper.loop(Looper.java:183)
            """.trimIndent()
    }
}
