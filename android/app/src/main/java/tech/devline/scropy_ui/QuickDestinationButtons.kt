package tech.devline.scropy_ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import tech.devline.scropy_ui.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun QuickDestinationButtons(onCommand: (String, String) -> Unit) {
    val labels = listOf("XREAL\n公式", "スカイ\nツリー")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val action = AuraCommands.shortcutDestinations[index]
            OutlinedButton(
                onClick = { onCommand(action.label, action.command) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                    contentDescription = uiText(action.label)
                },
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}
