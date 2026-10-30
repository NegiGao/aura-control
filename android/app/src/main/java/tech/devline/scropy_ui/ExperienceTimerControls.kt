package tech.devline.scropy_ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import tech.devline.scropy_ui.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val AppTimerBackground = Color(0xFFBBDFFF)
internal val AppTimerInk = Color(0xFF0B2A40)

internal val ExperiencePhase.background: Color get() = when (this) {
    ExperiencePhase.READY, ExperiencePhase.RUNNING -> Color(0xFF81C784)
    ExperiencePhase.LAST_MINUTE -> Color(0xFFFFD54F)
    ExperiencePhase.FINISHED -> Color(0xFFE00000)
}

internal val ExperiencePhase.ink: Color get() = when (this) {
    ExperiencePhase.READY, ExperiencePhase.RUNNING -> Color(0xFF102317)
    ExperiencePhase.LAST_MINUTE -> Color(0xFF302600)
    ExperiencePhase.FINISHED -> Color.White
}

private val ExperienceTimerDisplay.description: String get() = when (phase) {
    ExperiencePhase.READY -> "開始前・15分"
    ExperiencePhase.RUNNING -> "残り $text"
    ExperiencePhase.LAST_MINUTE -> "残り1分以内・$text"
    ExperiencePhase.FINISHED -> "15分の体験が終了しました"
}

@Composable
internal fun ExperienceTimerResetButton(onReset: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onReset,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("stream-timer-reset")) {
        Text("リセット")
    }
}

@Composable
internal fun ExperienceTimerHeader(
    timer: ExperienceTimerDisplay,
    onStart: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("XREAL AURA", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Surface(color = timer.phase.background, contentColor = timer.phase.ink,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.semantics { stateDescription = uiText(timer.description) }) {
            Text(timer.text, Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = onStart, enabled = !timer.started,
            contentPadding = PaddingValues(4.dp), modifier = Modifier.defaultMinSize(minWidth = 48.dp)) {
            Text("開始", maxLines = 1)
        }
        TextButton(onClick = onReset, contentPadding = PaddingValues(4.dp),
            modifier = Modifier.defaultMinSize(minWidth = 48.dp)) {
            Text("リセット", maxLines = 1, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun ExperienceMenuButton(
    controlsVisible: Boolean,
    timer: ExperienceTimerDisplay,
    modifier: Modifier = Modifier,
    appCountdown: AppCountdown? = null,
    now: Long = 0L,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)
        .semantics { stateDescription = uiText(timer.description) },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = timer.phase.background,
            contentColor = timer.phase.ink)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().testTag("stream-session-row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (controlsVisible) "閉じる" else "操作", Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(timer.text, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            if (appCountdown != null) {
                Surface(color = AppTimerBackground, contentColor = AppTimerInk,
                    shape = MaterialTheme.shapes.extraSmall) {
                    AppExperienceTimerRow(appCountdown, now,
                        Modifier.fillMaxWidth().padding(4.dp).testTag("stream-app-timer-row"))
                }
            }
        }
    }
}

@Composable
internal fun AppExperienceTimerRow(countdown: AppCountdown?, now: Long, modifier: Modifier = Modifier) {
    if (countdown == null) return
    val display = countdown.display(now)
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(countdown.app.label, Modifier.weight(1f, fill = false),
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        Text(display.text, fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall)
    }
}
