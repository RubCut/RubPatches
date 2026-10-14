package cat.narezany.rubp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import cat.narezany.rubp.data.Prefs
import cat.narezany.rubp.work.Engine

/** One's own package, label, icon, and LSPatch inside. Set first, built after. */
@Composable
fun CustomizeCard() {
    val words = LocalWords.current
    val context = LocalContext.current
    val prefs by Prefs.of(context).now.collectAsStateWithLifecycle()
    val state by Engine.state.collectAsStateWithLifecycle()
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        val store = Prefs.of(context)
        val idle = state !is Engine.State.Working
        val iconPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()) { chosen ->
            if (chosen != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(chosen,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { }
                store.set { it.copy(customIcon = chosen.toString()) }
            }
        }
        val modulePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()) { chosen ->
            for (uri in chosen) try {
                context.contentResolver.takePersistableUriPermission(uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            store.set { it.copy(lspatchModules = chosen.map { uri -> uri.toString() }.toSet()) }
        }
        val patchPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()) { chosen ->
            if (chosen != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(chosen,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { }
                store.set { it.copy(localPatch = chosen.toString()) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(words.customize, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(words.customizeNote, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = prefs.customPackage,
                onValueChange = { typed -> store.set { it.copy(customPackage = typed.trim()) } },
                label = { Text(words.customPackage) },
                supportingText = { Text(words.customPackageNote) },
                singleLine = true,
                enabled = idle,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prefs.customLabel,
                onValueChange = { typed -> store.set { it.copy(customLabel = typed) } },
                label = { Text(words.customLabel) },
                supportingText = { Text(words.customLabelNote) },
                singleLine = true,
                enabled = idle,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(words.customIcon, style = MaterialTheme.typography.bodyLarge)
                    Text(if (prefs.customIcon.isBlank()) words.customIconNote
                        else prefs.customIcon.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(enabled = idle,
                    onClick = { iconPicker.launch(arrayOf("image/png")) }) {
                    Text(words.pickFile)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(words.localPatch, style = MaterialTheme.typography.bodyLarge)
                    Text(if (prefs.localPatch.isBlank()) words.localPatchNote
                        else prefs.localPatch.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(enabled = idle,
                    onClick = { patchPicker.launch(arrayOf("*/*")) }) {
                    Text(words.pickFile)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(words.lspatchTitle, style = MaterialTheme.typography.bodyLarge)
                    Text(words.lspatchNote, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = prefs.lspatch, enabled = idle,
                    onCheckedChange = { on -> store.set { it.copy(lspatch = on) } })
            }
            if (prefs.lspatch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(words.lspatchModules, style = MaterialTheme.typography.bodyLarge)
                        Text(words.lspatchModulesNote(prefs.lspatchModules.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(enabled = idle, onClick = {
                        modulePicker.launch(arrayOf("application/vnd.android.package-archive",
                            "application/octet-stream", "*/*"))
                    }) { Text(words.pickFile) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(words.sigbypass, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f))
                    TextButton(enabled = idle, onClick = {
                        store.set { it.copy(sigbypass = (it.sigbypass + 1) % 3) }
                    }) { Text(words.sigbypassLevels[prefs.sigbypass.coerceIn(0, 2)]) }
                }
            }
            if (prefs.customPackage.isNotBlank() || prefs.customLabel.isNotBlank() ||
                prefs.customIcon.isNotBlank() || prefs.localPatch.isNotBlank() ||
                prefs.lspatch) {
                TextButton(enabled = idle, onClick = {
                    store.set { it.copy(customPackage = "", customLabel = "",
                        customIcon = "", localPatch = "", lspatch = false,
                        lspatchModules = emptySet(), sigbypass = 0) }
                }) { Text(words.clear) }
            }
        }
    }
}
