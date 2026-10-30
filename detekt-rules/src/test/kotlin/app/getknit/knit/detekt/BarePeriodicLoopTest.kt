package app.getknit.knit.detekt

import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.Assert.assertEquals
import org.junit.Test

/** Parsed, not compiled: the snippets name coroutine APIs that are not on this module's test classpath. */
class BarePeriodicLoopTest {
    private val rule = BarePeriodicLoop(Config.empty)

    private fun findings(code: String) = rule.lint(code, compile = false)

    private fun assertFlagged(code: String) = assertEquals(code, 1, findings(code).size)

    private fun assertClean(code: String) = assertEquals(code, 0, findings(code).size)

    @Test
    fun aLaunchedTickerWithAnUnguardedRoundIsFlagged() =
        assertFlagged(
            """
            fun start(scope: CoroutineScope) {
                scope.launch {
                    while (true) {
                        delay(60_000L)
                        sweep()
                    }
                }
            }
            """,
        )

    @Test
    fun theActiveConditionsAndAQualifiedDelayAreTickersToo() {
        assertFlagged("fun f() { scope.launch { while (isActive) { delay(1L); work() } } }")
        assertFlagged("fun f() { scope.launch { while (currentCoroutineContext().isActive) { delay(1L); work() } } }")
        assertFlagged("fun f() { scope.launch { while (true) { kotlinx.coroutines.delay(1L); work() } } }")
        assertFlagged("fun f() { scope.async(start = LAZY) { while (true) { work(); delay(1L) } } }")
        assertFlagged("fun f() { scope.launch(Dispatchers.IO, block = { while (true) { delay(1L); work() } }) }")
        assertFlagged("fun f() { scope.launch { tick@ while (true) { delay(1L); work() } } }")
    }

    @Test
    fun aRoundCaughtInsideTheLoopIsNotFlagged() {
        assertClean(
            """
            fun f() {
                scope.launch {
                    while (true) {
                        delay(1L)
                        try { work() } catch (e: Exception) { log(e) }
                    }
                }
            }
            """,
        )
        assertClean("fun f() { scope.launch { while (true) { delay(1L); runCatching { work() }.onFailure { log(it) } } } }")
    }

    @Test
    fun aGuardTheConfigNamesGuardsItsRound() {
        val code = "fun f() { scope.launch { while (true) { delay(1L); runRound(::log) { work() } } } }"
        assertFlagged(code)
        assertEquals(0, BarePeriodicLoop(TestConfig("guards" to listOf("runCatching", "runRound"))).lint(code, compile = false).size)
    }

    @Test
    fun aTryWithOnlyAFinallyCatchesNothing() =
        assertFlagged("fun f() { scope.launch { while (true) { delay(1L); try { work() } finally { done() } } } }")

    @Test
    fun aLoopThatCanLeaveIsAWaitNotATicker() {
        assertClean("fun f() { scope.launch { var w = 1L; while (true) { delay(w); w = next() ?: break } } }")
        assertClean("fun f() { scope.launch { while (true) { delay(1L); if (done()) return@launch; work() } } }")
        assertClean("fun f() { scope.launch { outer@ while (true) { delay(1L); for (x in xs) { if (x) break@outer } } } }")
    }

    @Test
    fun anExitThatEndsSomethingInsideTheLoopDoesNotLeaveIt() {
        assertFlagged("fun f() { scope.launch { while (true) { delay(1L); xs.forEach { if (it) return@forEach; work(it) } } } }")
        assertFlagged("fun f() { scope.launch { while (true) { delay(1L); for (x in xs) { if (x) break }; work() } } }")
        assertFlagged("fun f() { scope.launch { while (true) { delay(1L); fun g(): Int { return 1 }; work(g()) } } }")
    }

    @Test
    fun loopsThatAreNotTheLaunchedCoroutineAreNotFlagged() {
        assertClean("suspend fun f() { while (true) { delay(1L); work() } }")
        assertClean("fun f() = flow { while (true) { emit(read()); delay(1L) } }")
        assertClean("fun f() { scope.launch { while (true) { val x = channel.receive(); work(x) } } }")
        assertClean("fun f() { scope.launch { while (true) { delay(1L) } } }")
        assertClean("fun f() { scope.launch { while (ready) { delay(1L); work() } } }")
    }

    @Test
    fun everyRoundIsTheShapeTheRulePointsTo() = assertClean("fun f() { scope.everyRound(60_000L, onFailure = { log(it) }) { sweep() } }")
}
