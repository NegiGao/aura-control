package tech.devline.scropy_ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import tech.devline.scropy_ui.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

@Composable
internal fun StreamConnectionBadge(connectionStatus: String, videoRates: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = Color.Black.copy(alpha = 0.8f), contentColor = Color.White) {
        val summary = streamStatisticsParts(connectionStatus, videoRates).joinToString(" · ") { uiText(it) }
        Text(summary, Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            .horizontalScroll(rememberScrollState()).testTag("stream-status-line"),
            style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
    }
}

@Composable
internal fun StreamToolbar(
    connectionStatus: String,
    videoRates: String,
    controlsVisible: Boolean,
    timer: ExperienceTimerDisplay,
    appCountdown: AppCountdown?,
    now: Long,
    onTimerStart: () -> Unit,
    onCloseAllApps: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleControls: () -> Unit,
) {
    Column(modifier.fillMaxWidth().background(Color.Black).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (controlsVisible) {
            Row(Modifier.fillMaxWidth().testTag("stream-session-row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f).testTag("stream-countdowns")) {
                    Column {
                        Surface(onClick = onTimerStart, enabled = !timer.started,
                            color = timer.phase.background, contentColor = timer.phase.ink,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("stream-timer-start")
                                .semantics { stateDescription = "${uiText("体験 残り")} ${timer.text}" }) {
                            Column(Modifier.padding(4.dp).testTag("stream-total-timer"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                Text(timer.text, fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                if (!timer.started) Text("開始", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                        Surface(color = AppTimerBackground, contentColor = AppTimerInk,
                            modifier = Modifier.fillMaxWidth().testTag("stream-app-timer-row")
                                .semantics { stateDescription = appCountdown?.let {
                                    "${uiText(it.app.label)} ${it.display(now).text}"
                                } ?: "APP --:--" }) {
                            Box(Modifier.padding(4.dp), contentAlignment = Alignment.Center) {
                                Text(appCountdown?.display(now)?.text ?: "--:--",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                        }
                    }
                }
                OutlinedButton(onClick = onCloseAllApps,
                    modifier = Modifier.width(64.dp).heightIn(min = 48.dp).testTag("stream-close-all"),
                    contentPadding = PaddingValues(4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                    Text("全APPを閉じる", style = MaterialTheme.typography.labelSmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Button(onClick = onToggleControls,
                    modifier = Modifier.width(56.dp).heightIn(min = 48.dp).testTag("stream-menu"),
                    contentPadding = PaddingValues(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = timer.phase.background,
                        contentColor = timer.phase.ink)) {
                    Text("閉じる", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        } else {
            ExperienceMenuButton(false, timer,
                Modifier.fillMaxWidth().testTag("stream-menu"), appCountdown, now, onToggleControls)
        }
        StreamConnectionBadge(connectionStatus, videoRates,
            Modifier.fillMaxWidth().testTag("stream-statistics"))
    }
}
