package org.onyxite.quickconvert.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.onyxite.quickconvert.convert.ConToPkg
import org.onyxite.quickconvert.convert.EdatPacker
import org.onyxite.quickconvert.convert.OutputTemplate
import org.onyxite.quickconvert.convert.TreeFiles
import org.onyxite.quickconvert.model.Adjustment
import org.onyxite.quickconvert.model.ConvertSettings
import org.onyxite.quickconvert.model.SongFile
import org.onyxite.quickconvert.model.SourceKind
import org.onyxite.quickconvert.model.UsrdirMode

enum class LogTone { BODY, OK, WARN, BAD }

data class LogLine(val text: String, val tone: LogTone = LogTone.BODY)

class QuickConvertViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences(PREFS, Application.MODE_PRIVATE)
    val songs = mutableStateListOf<SongFile>()
    val log = mutableStateListOf<LogLine>()

    var settings by mutableStateOf(loadSettings())
        private set
    var outputTreeUri by mutableStateOf(prefs.getString(KEY_OUTPUT_URI, null)?.let(Uri::parse))
        private set
    var outputLabel by mutableStateOf(prefs.getString(KEY_OUTPUT_LABEL, null))
        private set
    var running by mutableStateOf(false)
        private set
    var scanning by mutableStateOf(false)
        private set

    fun addSongs(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        for (uri in uris) {
            val key = uri.toString()
            if (songs.any { it.uri == key }) continue
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // The picker grant still covers this session.
            }
            val name = queryName(uri)
            val header = readHeader(uri)
            songs.add(
                SongFile(
                    uri = key,
                    displayName = name,
                    kind = SourceKind.identify(name, header),
                    sizeBytes = querySize(uri),
                ),
            )
        }
    }

    fun addFolder(tree: Uri) {
        if (scanning || running) return
        val resolver = getApplication<Application>().contentResolver
        try {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The picker grant still covers this session.
        }
        scanning = true
        viewModelScope.launch(Dispatchers.IO) {
            val found = runCatching {
                TreeFiles.listFiles(resolver, tree).mapNotNull { file ->
                    val header = readHeader(file.uri)
                    val kind = SourceKind.identify(file.name, header)
                    if (kind == SourceKind.UNKNOWN) null
                    else SongFile(file.uri.toString(), file.name, kind, file.size)
                }
            }
            withContext(Dispatchers.Main) {
                scanning = false
                found.fold(
                    onSuccess = { songsInFolder ->
                        var added = 0
                        for (song in songsInFolder) {
                            if (songs.any { it.uri == song.uri }) continue
                            songs.add(song)
                            added++
                        }
                        if (added == 0 && songsInFolder.isEmpty()) {
                            log += LogLine("No Rock Band songs in that folder.", LogTone.WARN)
                        }
                    },
                    onFailure = { error ->
                        log += LogLine(error.message ?: "Could not read that folder.", LogTone.BAD)
                    },
                )
            }
        }
    }

    fun clearSongs() {
        songs.clear()
        log.clear()
    }

    fun setEncryptMidEdat(enabled: Boolean) = update(settings.copy(encryptMidEdat = enabled))

    fun setUsrdir(mode: UsrdirMode) = update(settings.copy(usrdir = mode))

    fun setTemplate(template: String) = update(settings.copy(template = template))

    fun toggleAdjustment(adjustment: Adjustment) {
        val next = settings.adjustments.toMutableSet()
        if (!next.add(adjustment)) next.remove(adjustment)
        update(settings.copy(adjustments = next))
    }

    fun setOutputTree(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
        }
        val label = outputFolderLabel(uri)
        outputTreeUri = uri
        outputLabel = label
        prefs.edit()
            .putString(KEY_OUTPUT_URI, uri.toString())
            .putString(KEY_OUTPUT_LABEL, label)
            .apply()
    }

    fun start() {
        if (running || songs.isEmpty()) return
        val snapshotSongs = songs.toList()
        val snapshotSettings = settings
        val folder = outputLabel
        val tree = outputTreeUri
        val app = getApplication<Application>()
        running = true
        log.clear()
        viewModelScope.launch(Dispatchers.IO) {
            val lines = ArrayList<LogLine>()
            if (tree == null) {
                lines += LogLine("Choose an output folder. Each PKG is saved there.", LogTone.WARN)
            } else {
                lines += LogLine("Output folder: ${folder ?: "selected folder"}")
                val encryptor = if (snapshotSettings.encryptMidEdat && EdatPacker.available) {
                    ConToPkg.MidiEncryptor { name, midi, contentId, klic ->
                        EdatPacker.encrypt(app.cacheDir, name, midi, contentId, klic)
                    }
                } else {
                    null
                }
                for (song in snapshotSongs) {
                    val planned = OutputTemplate.plan(song.displayName, snapshotSettings.template)
                    lines += LogLine("${song.displayName}  ·  ${song.kind.label}")
                    if (song.kind != SourceKind.CON && song.kind != SourceKind.LIVE && song.kind != SourceKind.PIRS) {
                        lines += LogLine("This build converts CON, LIVE, and PIRS files.", LogTone.BAD)
                        continue
                    }
                    try {
                        val input = app.contentResolver.openInputStream(Uri.parse(song.uri))?.use { it.readBytes() }
                            ?: throw IllegalStateException("Could not read ${song.displayName}")
                        val built = ConToPkg.convert(input, snapshotSettings, encryptor) { rb3 ->
                            loadPackageFiles(app, rb3)
                        }
                        TreeFiles.write(app.contentResolver, tree, planned.relativePath, built.bytes)
                        lines += LogLine("Wrote ${planned.relativePath}", LogTone.OK)
                        lines += LogLine(built.summary)
                        built.notes.forEach { lines += LogLine(it, LogTone.WARN) }
                    } catch (error: Exception) {
                        lines += LogLine(error.message ?: "Could not convert ${song.displayName}", LogTone.BAD)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                log.clear()
                log.addAll(lines)
                running = false
            }
        }
    }

    private fun update(next: ConvertSettings) {
        settings = next
        prefs.edit()
            .putBoolean(KEY_ENCRYPT, next.encryptMidEdat)
            .putString(KEY_USRDIR, next.usrdir.name)
            .putString(KEY_TEMPLATE, next.template)
            .putString(KEY_ADJUSTMENTS, next.adjustments.joinToString(",") { it.name })
            .apply()
    }

    private fun loadSettings(): ConvertSettings {
        val adjustments = prefs.getString(KEY_ADJUSTMENTS, "")
            .orEmpty()
            .split(',')
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { Adjustment.valueOf(it) }.getOrNull() }
            .toSet()
        val usrdir = prefs.getString(KEY_USRDIR, null)
            ?.let { runCatching { UsrdirMode.valueOf(it) }.getOrNull() }
            ?: UsrdirMode.KEEP_ORIGINAL
        return ConvertSettings(
            encryptMidEdat = prefs.getBoolean(KEY_ENCRYPT, true),
            usrdir = usrdir,
            template = prefs.getString(KEY_TEMPLATE, OutputTemplate.DEFAULT) ?: OutputTemplate.DEFAULT,
            adjustments = adjustments,
        )
    }

    private fun loadPackageFiles(app: Application, rb3: Boolean): Map<String, ByteArray> {
        val dir = if (rb3) "pkg-contents/rb3" else "pkg-contents/rb2"
        val names = app.assets.list(dir) ?: return emptyMap()
        return names.mapNotNull { name ->
            runCatching {
                name to app.assets.open("$dir/$name").use { it.readBytes() }
            }.getOrNull()
        }.toMap()
    }

    private fun queryName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "song"
    }

    private fun querySize(uri: Uri): Long {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return -1L
    }

    private fun readHeader(uri: Uri): ByteArray {
        val resolver = getApplication<Application>().contentResolver
        return resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(4)
            val read = input.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        } ?: ByteArray(0)
    }

    private fun outputFolderLabel(uri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val leaf = docId
            ?.substringAfterLast(':')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        return leaf ?: "Selected folder"
    }

    private companion object {
        const val PREFS = "quick_convert"
        const val KEY_ENCRYPT = "encrypt_mid_edat"
        const val KEY_USRDIR = "usrdir"
        const val KEY_TEMPLATE = "template"
        const val KEY_ADJUSTMENTS = "adjustments"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_OUTPUT_LABEL = "output_label"
    }
}
