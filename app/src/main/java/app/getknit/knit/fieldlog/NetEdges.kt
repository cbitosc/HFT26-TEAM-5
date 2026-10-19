package app.getknit.knit.fieldlog

import app.getknit.knit.net.InternetGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * The field log's `net` lines (ADR 2026-10.a239): [gate]'s route as it stands at the session's start, then each time it
 * changes — gained, lost, or a different network (a Wi-Fi to cellular hand-over keeps [InternetGate.online] true, so
 * the route changes are followed too). Kind and Data Saver only, never the network itself.
 */
fun netEdges(gate: InternetGate): Flow<FieldEvent> =
    merge(gate.online.map { }, gate.routeChanges)
        .map { FieldEvent.Net(gate.routeKind(), gate.isDataRestricted()) }
        .distinctUntilChanged()
