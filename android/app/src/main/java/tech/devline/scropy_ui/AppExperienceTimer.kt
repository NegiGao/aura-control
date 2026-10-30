package tech.devline.scropy_ui

internal data class ExperienceApp(val label: String, val packageName: String, val seconds: Int)
internal data class AppCountdown(val app: ExperienceApp, val deadline: Long) {
    fun display(now: Long): ExperienceTimerDisplay {
        val remaining = (deadline - now).coerceIn(0, app.seconds * 1000L)
        return ExperienceTimerDisplay(((remaining + 999) / 1000).toInt(), when {
            remaining == 0L -> ExperiencePhase.FINISHED
            remaining <= 15_000L -> ExperiencePhase.LAST_MINUTE
            else -> ExperiencePhase.RUNNING
        })
    }
}

internal val experienceApps = listOf(
    ExperienceApp("YouTube XR", "com.google.android.apps.youtube.xr", 120),
    ExperienceApp("Google Maps 2D", "com.google.android.apps.maps", 30),
    ExperienceApp("Google Maps XR", "com.google.android.apps.immersiveviewxr", 150),
    ExperienceApp("Gemini", "com.google.android.googlequicksearchbox", 60),
    ExperienceApp("Chrome", "com.android.chrome", 60),
)

internal fun experienceAppForCommand(command: String): ExperienceApp? {
    val label = AuraCommands.apps.firstOrNull { it.command == command }?.label
    if (label != null) return experienceApps.first { it.label == label }
    return when {
        command == AuraCommands.mapsXrGuide || AuraCommands.quickDestinations.drop(1).any { it.command == command } ->
            experienceApps.first { it.label == "Google Maps XR" }
        command == AuraCommands.quickDestinations.first().command -> experienceApps.last()
        command == AuraCommands.controls.last().command -> experienceApps.first()
        else -> null
    }
}

internal fun hasAppTask(tasks: String, app: ExperienceApp): Boolean =
    Regex("(?<![A-Za-z0-9_.])${Regex.escape(app.packageName)}/").containsMatchIn(tasks)

internal fun appCountdownAfterTaskSnapshot(current: AppCountdown?, tasks: String): AppCountdown? {
    // Unknown or failed snapshots are not evidence that the guest closed the app.
    val valid = Regex("(?m)^\\s*(?:RootTask|Stack|taskId=)").containsMatchIn(tasks) &&
        !Regex("(?i)(error|exception|permission denial)").containsMatchIn(tasks)
    return current?.takeUnless { valid && !hasAppTask(tasks, it.app) }
}

internal fun nextAppCountdown(current: AppCountdown?, command: String, succeeded: Boolean, now: Long): AppCountdown? {
    if (!succeeded) return current
    if (command == AuraCommands.controls.first { it.label == "全APPを閉じる" }.command) return null
    if (command == AuraCommands.geminiClose) {
        return current?.takeUnless { it.app.label == "Gemini" }
    }
    val app = experienceAppForCommand(command) ?: return current
    return if (current?.app == app) current else AppCountdown(app, now + app.seconds * 1000L)
}
