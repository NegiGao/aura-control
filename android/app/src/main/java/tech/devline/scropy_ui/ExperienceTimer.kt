package tech.devline.scropy_ui

internal const val EXPERIENCE_DURATION_MS = 15 * 60 * 1000L

internal enum class ExperiencePhase { READY, RUNNING, LAST_MINUTE, FINISHED }

internal data class ExperienceTimerDisplay(val seconds: Int, val phase: ExperiencePhase) {
    val text: String get() = "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    val started: Boolean get() = phase != ExperiencePhase.READY
}

internal fun startExperienceTimer(deadline: Long?, now: Long): Long =
    deadline ?: (now + EXPERIENCE_DURATION_MS)

internal fun experienceTimerDisplay(deadline: Long?, now: Long): ExperienceTimerDisplay {
    if (deadline == null) return ExperienceTimerDisplay(900, ExperiencePhase.READY)
    val remaining = (deadline - now).coerceIn(0, EXPERIENCE_DURATION_MS)
    val phase = when {
        remaining == 0L -> ExperiencePhase.FINISHED
        remaining <= 60_000L -> ExperiencePhase.LAST_MINUTE
        else -> ExperiencePhase.RUNNING
    }
    return ExperienceTimerDisplay(((remaining + 999) / 1000).toInt(), phase)
}
