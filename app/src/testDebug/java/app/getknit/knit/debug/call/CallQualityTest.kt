package app.getknit.knit.debug.call

import org.junit.Assert.assertEquals
import org.junit.Test

class CallQualityTest {
    private val clean = CallStats(rttMs = 80.0, jitterMs = 4.0, recentLossPercent = 0.0, sentLossPercent = 0.0, recentPacketsReceived = 50)

    @Test
    fun eachDirectionIsNamedOnItsOwnAndBothTogether() {
        assertEquals(Quality(QualityLevel.GOOD), QualityJudge.judge(clean))
        assertEquals(
            Quality(QualityLevel.POOR, QualityIssue.HEARING_BREAKS),
            QualityJudge.judge(clean.copy(recentLossPercent = 12.0)),
        )
        assertEquals(
            Quality(QualityLevel.FAIR, QualityIssue.HEARING_BREAKS),
            QualityJudge.judge(clean.copy(jitterMs = 40.0)),
        )
        assertEquals(
            Quality(QualityLevel.FAIR, QualityIssue.HEARD_BREAKS),
            QualityJudge.judge(clean.copy(sentLossPercent = 5.0)),
        )
        assertEquals(
            Quality(QualityLevel.POOR, QualityIssue.BOTH_BREAK),
            QualityJudge.judge(clean.copy(recentLossPercent = 4.0, sentLossPercent = 30.0)),
        )
        assertEquals(Quality(QualityLevel.POOR, QualityIssue.DELAY), QualityJudge.judge(clean.copy(rttMs = 900.0)))
        assertEquals(
            Quality(QualityLevel.POOR, QualityIssue.NOT_HEARING),
            QualityJudge.judge(clean.copy(recentPacketsReceived = 0)),
        )
        // A reading with nothing to judge yet (the first one) is good, not a warning.
        assertEquals(Quality(QualityLevel.GOOD), QualityJudge.judge(CallStats()))
    }

    @Test
    fun aWarningNeedsTwoReadingsInARowAndClearsAfterThree() {
        val judge = QualityJudge()
        val bad = clean.copy(recentLossPercent = 15.0)
        assertEquals(QualityLevel.GOOD, judge.next(bad).level)
        assertEquals(QualityLevel.POOR, judge.next(bad).level)
        // A missing reading changes nothing.
        assertEquals(QualityLevel.POOR, judge.next(null).level)
        assertEquals(QualityLevel.POOR, judge.next(clean).level)
        assertEquals(QualityLevel.POOR, judge.next(clean).level)
        assertEquals(QualityLevel.GOOD, judge.next(clean).level)
    }

    @Test
    fun aStreakIsBrokenByAReadingAtAnotherLevel() {
        val judge = QualityJudge()
        val poor = clean.copy(recentLossPercent = 15.0)
        val fair = clean.copy(recentLossPercent = 5.0)
        judge.next(poor)
        assertEquals(QualityLevel.GOOD, judge.next(fair).level)
        assertEquals(QualityLevel.FAIR, judge.next(fair).level)
        // At the shown level the issue follows the reading at once.
        assertEquals(QualityIssue.HEARD_BREAKS, judge.next(clean.copy(sentLossPercent = 5.0)).issue)
    }

    @Test
    fun theRoutePillNamesTheNetwork() {
        val wifi = CallStats(networkType = "wifi")
        assertEquals(RouteView("Relayed · Wi-Fi", RouteKind.RELAYED), routeView("relay/srflx", wifi))
        assertEquals(RouteView("Direct · Cellular", RouteKind.DIRECT), routeView("srflx/prflx", CallStats(networkType = "cellular")))
        assertEquals(RouteView("Direct", RouteKind.DIRECT), routeView("srflx/srflx", null))
        assertEquals(RouteView("Direct · same network", RouteKind.DIRECT), routeView("host/host", wifi))
        assertEquals(RouteView("Wi-Fi Direct", RouteKind.WIFI_DIRECT), routeView("host/host/wifi-direct", wifi))
        assertEquals("Your audio is breaking up for Ada", issueText(QualityIssue.HEARD_BREAKS, "Ada"))
    }
}
