package app.getknit.knit.fieldlog

import app.getknit.knit.net.InternetGate
import app.getknit.knit.net.InternetGate.RouteKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetEdgesTest {
    private class Gate : InternetGate {
        var kind = RouteKind.WIFI
        override val online = MutableStateFlow(true)
        override val routeChanges = MutableSharedFlow<Unit>()

        override fun isOnline() = online.value

        override fun isDataRestricted() = false

        override fun routeKind() = if (online.value) kind else RouteKind.NONE
    }

    @Test
    fun theRouteIsWrittenAtTheStartAndOnEachChangeIncludingAHandOverThatStaysOnline() =
        runTest {
            val gate = Gate()
            val lines = mutableListOf<FieldEvent>()
            // Unconfined: the collector is subscribed to both streams before the first change is emitted.
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { netEdges(gate).toList(lines) }

            gate.routeChanges.emit(Unit) // another Wi-Fi network: the same kind, so no new line
            gate.kind = RouteKind.CELLULAR
            gate.routeChanges.emit(Unit) // Wi-Fi → cellular, online throughout
            gate.online.value = false

            assertEquals(
                listOf(RouteKind.WIFI, RouteKind.CELLULAR, RouteKind.NONE),
                lines.map { (it as FieldEvent.Net).route },
            )
        }
}
