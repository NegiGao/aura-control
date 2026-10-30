package tech.devline.scropy_ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import tech.devline.scropy_ui.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun UsbProjectionButtons(enabled: Boolean, onUsb: () -> Unit, onWifi: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onUsb, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text("USBで投影")
        }
        Button(onClick = onWifi, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text("Wi-Fiで投影")
        }
    }
}
