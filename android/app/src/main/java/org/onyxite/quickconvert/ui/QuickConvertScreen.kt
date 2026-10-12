package org.onyxite.quickconvert.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.onyxite.quickconvert.convert.OutputTemplate
import org.onyxite.quickconvert.model.Adjustment
import org.onyxite.quickconvert.model.SongFile
import org.onyxite.quickconvert.model.UsrdirMode
import java.util.Locale

@Composable
fun QuickConvertScreen(
    viewModel: QuickConvertViewModel,
    onAddSongs: () -> Unit,
    onAddFolder: () -> Unit,
    onPickOutput: () -> Unit,
) {
    val settings = viewModel.settings
    val songs = viewModel.songs
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("Quick Convert", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "One to one  ·  PKG (PS3)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Each input file produces a new PKG.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Text(
                    fileCount(songs.size),
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            if (songs.isEmpty()) {
                item {
                    Card(colors = cardColors()) {
                        Text(
                            "No Rock Band songs yet.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(songs, key = { it.uri }) { song ->
                    SongRow(song, settings.template)
                }
            }

            item {
                Button(
                    onClick = onAddSongs,
                    enabled = !viewModel.running && !viewModel.scanning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Add Rock Band song")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onAddFolder,
                    enabled = !viewModel.running && !viewModel.scanning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (viewModel.scanning) "Adding folder…" else "Add folder")
                }
                TextButton(
                    onClick = viewModel::clearSongs,
                    enabled = songs.isNotEmpty() && !viewModel.running && !viewModel.scanning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Clear Rock Band songs")
                }
            }

            item {
                SectionCard(title = "Output") {
                    ReadOnlyValue(
                        label = "Mode",
                        value = "One to one: each input file produces a new output file",
                    )
                    ReadOnlyValue(label = "Format", value = "PKG (PS3)")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = settings.encryptMidEdat,
                                enabled = !viewModel.running,
                                role = Role.Checkbox,
                                onValueChange = viewModel::setEncryptMidEdat,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = settings.encryptMidEdat,
                            onCheckedChange = null,
                        )
                        Text("Encrypt .mid.edat", modifier = Modifier.padding(start = 8.dp))
                    }
                    UsrdirMenu(
                        selected = settings.usrdir,
                        enabled = !viewModel.running,
                        onSelect = viewModel::setUsrdir,
                    )
                }
            }

            item {
                SectionCard(title = "Adjustments") {
                    Adjustment.entries.forEach { adjustment ->
                        val checked = adjustment in settings.adjustments
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    enabled = !viewModel.running,
                                    role = Role.Switch,
                                    onValueChange = { viewModel.toggleAdjustment(adjustment) },
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(adjustment.label, fontWeight = FontWeight.Medium)
                                Text(
                                    adjustment.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = checked, onCheckedChange = null)
                        }
                    }
                }
            }

            item {
                SectionCard(title = "Template") {
                    OutlinedTextField(
                        value = settings.template,
                        onValueChange = viewModel::setTemplate,
                        enabled = !viewModel.running,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Output name") },
                        supportingText = {
                            Text("%input_base%  %ext%  %title%  %artist%  ·  %input_dir% is the output folder")
                        },
                    )
                    if (songs.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        songs.take(3).forEach { song ->
                            val planned = OutputTemplate.plan(song.displayName, settings.template)
                            Text(
                                "${song.displayName}  →  ${planned.relativePath}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onPickOutput,
                        enabled = !viewModel.running,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(viewModel.outputLabel ?: "Choose output folder")
                    }
                }
            }

            item {
                Button(
                    onClick = viewModel::start,
                    enabled = songs.isNotEmpty() && !viewModel.running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (viewModel.running) "Working…" else "Start")
                }
                if (viewModel.running) {
                    LinearProgressIndicator(modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp))
                }
            }

            if (viewModel.log.isNotEmpty()) {
                item {
                    SectionCard(title = "Result") {
                        viewModel.log.forEach { line ->
                            val color = when (line.tone) {
                                LogTone.BODY -> MaterialTheme.colorScheme.onSurface
                                LogTone.OK -> MaterialTheme.colorScheme.primary
                                LogTone.WARN -> MaterialTheme.colorScheme.secondary
                                LogTone.BAD -> MaterialTheme.colorScheme.error
                            }
                            Text(line.text, color = color, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SongRow(song: SongFile, template: String) {
    val planned = OutputTemplate.plan(song.displayName, template)
    Card(colors = cardColors()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(song.displayName, fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(song.kind.label, formatSize(song.sizeBytes)).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "→ ${planned.relativePath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(colors = cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            content()
        }
    }
}

@Composable
private fun ReadOnlyValue(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value)
    }
}

@Composable
private fun UsrdirMenu(
    selected: UsrdirMode,
    enabled: Boolean,
    onSelect: (UsrdirMode) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(
            "USRDIR",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { open = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(selected.label)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                UsrdirMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.label) },
                        onClick = {
                            onSelect(mode)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun cardColors() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface,
)

private fun fileCount(count: Int): String = when (count) {
    1 -> "1 file loaded."
    else -> "$count files loaded."
}

private fun formatSize(bytes: Long): String? {
    if (bytes < 0) return null
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    return if (kb < 1024) {
        String.format(Locale.US, "%.1f KB", kb)
    } else {
        String.format(Locale.US, "%.1f MB", kb / 1024.0)
    }
}
