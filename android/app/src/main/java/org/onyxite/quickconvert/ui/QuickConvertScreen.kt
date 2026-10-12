package org.onyxite.quickconvert.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                Spacer(Modifier.height(8.dp))
                ConsolePane(songListing(songs, settings.template))
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
                var adjustmentsOpen by rememberSaveable { mutableStateOf(false) }
                val enabledCount = settings.adjustments.size
                Card(colors = cardColors()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { adjustmentsOpen = !adjustmentsOpen }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Adjustments",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (!adjustmentsOpen && enabledCount > 0) {
                                Text(
                                    if (enabledCount == 1) "1 on" else "$enabledCount on",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                if (adjustmentsOpen) "Hide" else "Show",
                                modifier = Modifier.padding(start = 12.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (adjustmentsOpen) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
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
                    Text("Result", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    ConsolePane(
                        text = viewModel.log.joinToString("\n") { it.text },
                        followEnd = true,
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ConsolePane(text: String, followEnd: Boolean = false) {
    val scroll = rememberScrollState()
    LaunchedEffect(text, followEnd, scroll.maxValue) {
        if (followEnd) scroll.scrollTo(scroll.maxValue)
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .verticalScroll(scroll)
            .padding(12.dp),
    ) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun songListing(songs: List<SongFile>, template: String): String {
    if (songs.isEmpty()) return "No Rock Band songs yet."
    return songs.joinToString("\n\n") { song ->
        val planned = OutputTemplate.plan(song.displayName, template)
        val meta = listOfNotNull(song.kind.label, formatSize(song.sizeBytes)).joinToString("  ")
        "${song.displayName}\n$meta\n-> ${planned.relativePath}"
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
