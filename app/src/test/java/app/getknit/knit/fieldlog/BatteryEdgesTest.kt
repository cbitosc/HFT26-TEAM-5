package app.getknit.knit.fieldlog

import app.getknit.knit.ui.BackgroundBattery
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryEdgesTest {
    private fun battery(
        usage: BackgroundBattery,
        exempt: Boolean = false,
        bucket: StandbyBucket = StandbyBucket.ACTIVE,
    ) = FieldEvent.Battery(usage, exempt, bucket)

    @Test
    fun theStandingIsWrittenAtTheStartAndOnlyWhenItChanges() =
        runTest {
            val reads =
                ArrayDeque(
                    listOf(
                        battery(BackgroundBattery.Optimized),
                        battery(BackgroundBattery.Optimized), // unchanged: no line
                        battery(BackgroundBattery.Unrestricted, exempt = true),
                        battery(BackgroundBattery.Unrestricted, exempt = true, bucket = StandbyBucket.WORKING_SET),
                        battery(BackgroundBattery.Restricted, exempt = true, bucket = StandbyBucket.RESTRICTED),
                    ),
                )
            val waits = mutableListOf<Long>()

            val lines = batteryEdges(read = { reads.removeFirst() }, everyMs = 300_000, wait = { waits += it }).take(4).toList()

            assertEquals(
                listOf(
                    battery(BackgroundBattery.Optimized),
                    battery(BackgroundBattery.Unrestricted, exempt = true),
                    battery(BackgroundBattery.Unrestricted, exempt = true, bucket = StandbyBucket.WORKING_SET),
                    battery(BackgroundBattery.Restricted, exempt = true, bucket = StandbyBucket.RESTRICTED),
                ),
                lines,
            )
            // One wait between each two reads, each the asked interval.
            assertEquals(listOf(300_000L, 300_000L, 300_000L, 300_000L), waits)
        }

    @Test
    fun aBucketIsReadByItsPlatformValueAndAnUnknownOneIsOther() {
        assertEquals(StandbyBucket.EXEMPTED, StandbyBucket.of(5))
        assertEquals(StandbyBucket.ACTIVE, StandbyBucket.of(10))
        assertEquals(StandbyBucket.WORKING_SET, StandbyBucket.of(20))
        assertEquals(StandbyBucket.FREQUENT, StandbyBucket.of(30))
        assertEquals(StandbyBucket.RARE, StandbyBucket.of(40))
        assertEquals(StandbyBucket.RESTRICTED, StandbyBucket.of(45))
        assertEquals(StandbyBucket.NEVER, StandbyBucket.of(50))
        assertEquals(StandbyBucket.OTHER, StandbyBucket.of(-1))
        assertEquals(StandbyBucket.OTHER, StandbyBucket.of(25))
    }
}
