package app.getknit.knit.fieldlog

import android.app.usage.UsageStatsManager
import android.content.Context
import app.getknit.knit.ui.backgroundBattery
import app.getknit.knit.ui.isIgnoringBatteryOptimizations
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * The field log's `battery` lines (ADR 2026-10.a239): [read] at the session's start, then again every [everyMs],
 * written only when it changed. None of the three settings it reads has a broadcast, so it is polled; the wait
 * stretches while the CPU sleeps, and that costs nothing, since only someone at the screen can change them.
 */
fun batteryEdges(
    read: () -> FieldEvent.Battery,
    everyMs: Long = FieldLog.SAMPLE_EVERY_MS,
    wait: suspend (Long) -> Unit = { delay(it) },
): Flow<FieldEvent> =
    flow {
        while (true) {
            emit(read())
            wait(everyMs)
        }
    }.distinctUntilChanged()

/** What [batteryEdges] reads on a phone: the Settings row's position, the bare exemption and the standby bucket. */
fun readBattery(context: Context): FieldEvent.Battery =
    FieldEvent.Battery(
        usage = backgroundBattery(context),
        exempt = isIgnoringBatteryOptimizations(context),
        bucket = StandbyBucket.of(context.getSystemService(UsageStatsManager::class.java).appStandbyBucket),
    )
