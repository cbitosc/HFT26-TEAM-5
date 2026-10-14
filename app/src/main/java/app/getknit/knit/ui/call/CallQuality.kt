package app.getknit.knit.ui.call

/** How a call sounds, worst last, so a higher [ordinal] is the worse level. */
internal enum class QualityLevel { GOOD, FAIR, POOR }

/**
 * Which way a call is hurting, as the call screen words it. A direction, never a network: loss on what this phone hears
 * is the peer's uplink or anything between, and the stats cannot say which hop dropped it.
 */
internal enum class QualityIssue {
    // Nothing heard from the peer since the last reading, with the media up.
    NOT_HEARING,

    // This phone is losing the peer's packets (or they arrive too unevenly to play).
    HEARING_BREAKS,

    // The peer reports losing this phone's packets.
    HEARD_BREAKS,

    // Both at once: the path itself, or both phones' networks.
    BOTH_BREAK,

    // Nothing lost, but the round trip is long enough to talk over each other.
    DELAY,
}

internal data class Quality(
    val level: QualityLevel,
    val issue: QualityIssue? = null,
)

/**
 * Judges each stats reading ([CallStats]) into a [Quality] the call screen shows beside the route, with hysteresis: a
 * worse level is shown after [WORSE_AFTER] readings in a row, a better one after [BETTER_AFTER], so one late receiver
 * report does not flash a warning and one clean second does not clear it. One judge per call, fed in order.
 */
internal class QualityJudge {
    private var shown = Quality(QualityLevel.GOOD)
    private var pending: QualityLevel? = null
    private var streak = 0

    @Synchronized
    fun next(stats: CallStats?): Quality {
        val raw = stats?.let(::judge) ?: return shown
        if (raw.level == shown.level) {
            shown = raw
            pending = null
            streak = 0
            return shown
        }
        if (raw.level != pending) {
            pending = raw.level
            streak = 0
        }
        streak++
        val needed = if (raw.level > shown.level) WORSE_AFTER else BETTER_AFTER
        if (streak >= needed) {
            shown = raw
            pending = null
            streak = 0
        }
        return shown
    }

    companion object {
        const val WORSE_AFTER = 2
        const val BETTER_AFTER = 3

        // Loss in percent: Opus with FEC hides a few percent; past ten, words go missing.
        private const val LOSS_FAIR = 3.0
        private const val LOSS_POOR = 10.0

        // Jitter in ms: past these the jitter buffer stretches the audio or drops it.
        private const val JITTER_FAIR = 30.0
        private const val JITTER_POOR = 60.0

        // Round trip in ms: ITU-T G.114 puts one-way delay past 150 ms as noticeable, past 400 as unacceptable.
        private const val RTT_FAIR = 350.0
        private const val RTT_POOR = 700.0

        /** One reading's verdict, before hysteresis. */
        fun judge(stats: CallStats): Quality {
            if (stats.recentPacketsReceived == 0L) return Quality(QualityLevel.POOR, QualityIssue.NOT_HEARING)
            val hearing =
                maxOf(
                    levelOf(stats.recentLossPercent, LOSS_FAIR, LOSS_POOR),
                    levelOf(stats.jitterMs, JITTER_FAIR, JITTER_POOR),
                )
            val heard = levelOf(stats.sentLossPercent, LOSS_FAIR, LOSS_POOR)
            val delay = levelOf(stats.rttMs, RTT_FAIR, RTT_POOR)
            val issue =
                when {
                    hearing > QualityLevel.GOOD && heard > QualityLevel.GOOD -> QualityIssue.BOTH_BREAK
                    hearing > QualityLevel.GOOD -> QualityIssue.HEARING_BREAKS
                    heard > QualityLevel.GOOD -> QualityIssue.HEARD_BREAKS
                    delay > QualityLevel.GOOD -> QualityIssue.DELAY
                    else -> null
                }
            return Quality(maxOf(hearing, heard, delay), issue)
        }

        private fun levelOf(
            value: Double?,
            fair: Double,
            poor: Double,
        ): QualityLevel =
            when {
                value == null -> QualityLevel.GOOD
                value >= poor -> QualityLevel.POOR
                value >= fair -> QualityLevel.FAIR
                else -> QualityLevel.GOOD
            }
    }
}
