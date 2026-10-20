package org.onyxite.quickconvert.convert

import org.onyxite.quickconvert.model.Adjustment
import org.onyxite.quickconvert.model.ConvertSettings
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * One CON/LIVE/PIRS file in, one PS3 PKG out.
 * File renames, `songs.dta` tweaks, and the package layout follow Onyx Quick Convert.
 */
object ConToPkg {
    data class Built(
        val bytes: ByteArray,
        val summary: String,
        val notes: List<String>,
    )

    data class Written(val summary: String, val notes: List<String>)

    fun interface MidiEncryptor {
        fun encrypt(fileName: String, midi: ByteArray, contentId: ByteArray, klic: ByteArray): ByteArray
    }

    fun convert(
        input: ByteArray,
        settings: ConvertSettings,
        encryptor: MidiEncryptor?,
        packageFiles: (rb3: Boolean) -> Map<String, ByteArray> = { emptyMap() },
    ): Built {
        val out = ByteArrayOutputStream()
        val written = writeTo(StfsExtractor.BytesSource(input), settings, encryptor, packageFiles, out)
        return Built(out.toByteArray(), written.summary, written.notes)
    }

    fun writeTo(
        input: StfsExtractor.Source,
        settings: ConvertSettings,
        encryptor: MidiEncryptor?,
        packageFiles: (rb3: Boolean) -> Map<String, ByteArray> = { emptyMap() },
        out: OutputStream,
    ): Written {
        val extracted = StfsExtractor.list(input)
        val dtaFile = extracted.firstOrNull { it.path.substringAfterLast('/').equals("songs.dta", ignoreCase = true) }
            ?: throw IllegalArgumentException("No songs.dta inside this package")
        val dta = dtaFile.readAll()
        val rb3 = isRb3(dta)
        val title = songTitle(dta) ?: "song"
        val folder = usrdirName(dta, title)
        val notes = ArrayList<String>()
        val skipped = settings.adjustments.filter { it != Adjustment.DECRYPT_MOGGS }
        if (skipped.isNotEmpty()) {
            notes += "Not applied yet: ${skipped.joinToString(", ") { it.label }}."
        }
        if (Adjustment.DECRYPT_MOGGS in settings.adjustments) {
            notes += "MOGG decryption is not available, so audio stays encrypted."
        }
        val contentId = (if (rb3) "UP8802-BLUS30463_00-" else "UP0006-BLUS30050_00-") + folder
        val klic = md5("Ih38rtW1ng3r$folder" + "10025250")
        val contentIdBytes = ByteArray(0x30)
        contentId.toByteArray(Charsets.ISO_8859_1).copyInto(contentIdBytes, 0, 0, minOf(contentId.length, 0x30))

        val root = PkgWriter.Dir()
        val bundled = packageFiles(rb3)
        if (bundled.isEmpty()) {
            root.add("PARAM.SFO", SfoWriter.rockBand(title, if (rb3) "BLUS30463" else "BLUS30050"))
            root.add("ICON0.PNG", IconPng.bytes())
        } else {
            for ((name, bytes) in bundled) root.add(name, bytes)
        }
        var encryptedMidis = 0
        var plainMidis = 0
        for (file in extracted) {
            val relative = file.path.trimStart('/')
            val placed = place(relative, file, settings.encryptMidEdat, contentIdBytes, klic, encryptor, notes)
            if (placed.name.endsWith(".mid.edat", ignoreCase = true)) {
                if (placed.encrypted) encryptedMidis++ else plainMidis++
            }
            root.add("USRDIR/$folder/${placed.name}", placed.blob)
        }
        if (settings.encryptMidEdat && plainMidis > 0 && encryptedMidis == 0) {
            notes += "MIDI was packed without .mid.edat encryption."
        }
        val summary = buildString {
            append(if (settings.encryptMidEdat) "Encrypt .mid.edat" else "MIDI left unencrypted")
            append(". ")
            append(if (Adjustment.DECRYPT_MOGGS in settings.adjustments) "Decrypt MOGGs" else "MOGGs stay encrypted")
            append(". USRDIR/")
            append(folder)
            append(".")
        }
        PkgWriter.write(root, contentId, out)
        return Written(summary, notes)
    }

    private class Placed(val name: String, val blob: PkgWriter.Blob, val encrypted: Boolean)

    private fun place(
        path: String,
        file: StfsExtractor.StoredFile,
        encryptMidi: Boolean,
        contentId: ByteArray,
        klic: ByteArray,
        encryptor: MidiEncryptor?,
        notes: MutableList<String>,
    ): Placed {
        val slash = path.lastIndexOf('/')
        val dir = if (slash < 0) "" else path.substring(0, slash + 1)
        val name = if (slash < 0) path else path.substring(slash + 1)
        val lower = name.lowercase()
        return when {
            lower == "songs.dta" -> Placed(path, PkgWriter.Blob.of(adjustDta(file.readAll())), false)
            lower.endsWith(".milo_xbox") -> Placed(dir + name.dropLast(10) + ".milo_ps3", blobOf(file), false)
            lower.endsWith(".png_xbox") -> Placed(dir + name.dropLast(9) + ".png_ps3", swapped(file), false)
            lower.endsWith(".mid") -> {
                val bytes = file.readAll()
                val edat = if (encryptMidi) encryptMidi(name + ".edat", bytes, contentId, klic, encryptor, notes) else bytes
                val encrypted = edat.size >= 4 && edat[0] == 'N'.code.toByte()
                Placed("$dir$name.edat", PkgWriter.Blob.of(edat), encrypted)
            }
            lower.endsWith(".mogg") -> Placed(path, patchedMogg(file), false)
            else -> Placed(path, blobOf(file), false)
        }
    }

    private fun blobOf(file: StfsExtractor.StoredFile): PkgWriter.Blob =
        PkgWriter.Blob(file.size) { position, dest, destOff, length ->
            file.readAt(position, dest, destOff, length)
        }

    /** Pair swap of [swapPng], applied while the file is read. Chunks start on even offsets. */
    private fun swapped(file: StfsExtractor.StoredFile): PkgWriter.Blob =
        PkgWriter.Blob(file.size) { position, dest, destOff, length ->
            val n = file.readAt(position, dest, destOff, length)
            var i = 0
            while (i < n) {
                val abs = position + i
                if (abs >= 0x20 && ((abs - 0x20) and 1L) == 0L && i + 1 < n) {
                    val at = destOff + i
                    val tmp = dest[at]
                    dest[at] = dest[at + 1]
                    dest[at + 1] = tmp
                    i += 2
                } else {
                    i++
                }
            }
            n
        }

    /** [fixMogg] applied to the 16-byte mask while the file is read. */
    private fun patchedMogg(file: StfsExtractor.StoredFile): PkgWriter.Blob {
        val patch = moggPatch(file)
        return PkgWriter.Blob(file.size) { position, dest, destOff, length ->
            val n = file.readAt(position, dest, destOff, length)
            if (patch != null && n > 0) {
                val start = position
                val end = position + n
                val at = patch.first
                if (at < end && at + 16 > start) {
                    val srcFrom = maxOf(0L, start - at).toInt()
                    val dstFrom = maxOf(0L, at - start).toInt()
                    val count = minOf(16 - srcFrom, n - dstFrom)
                    if (count > 0) patch.second.copyInto(dest, destOff + dstFrom, srcFrom, srcFrom + count)
                }
            }
            n
        }
    }

    private fun moggPatch(file: StfsExtractor.StoredFile): Pair<Long, ByteArray>? {
        if (file.size < 36) return null
        val head = ByteArray(36)
        if (file.readAt(0, head, 0, 36) < 36) return null
        if (head[0] == 0x0A.toByte()) return null
        val enc = le32(head, 0)
        val entries = le32(head, 16)
        val at = 20L + entries.toLong() * 8L + 32L
        if (at < 0 || at + 16 > file.size) return null
        val mask = ByteArray(16)
        if (file.readAt(at, mask, 0, 16) < 16) return null
        val good = when {
            mask.contentEquals(OLD_C3_MASK) -> NEW_C3_MASK
            enc == 12 && mask.contentEquals(OLD_V12_MASK) -> NEW_V12_MASK
            else -> return null
        }
        return at to good
    }

    private fun encryptMidi(
        fileName: String,
        midi: ByteArray,
        contentId: ByteArray,
        klic: ByteArray,
        encryptor: MidiEncryptor?,
        notes: MutableList<String>,
    ): ByteArray {
        if (encryptor == null) return midi
        return try {
            encryptor.encrypt(fileName, midi, contentId, klic)
        } catch (error: Exception) {
            notes += "Could not encrypt $fileName (${error.message})."
            midi
        }
    }

    fun usrdirName(dta: ByteArray, title: String): String {
        val hex = md5(dta).joinToString("") { "%02x".format(it) }.take(6)
        val filtered = ("O$hex$title").filter { it.isLetterOrDigit() && it.code < 128 }
        return filtered.take(0x1B).ifEmpty { "OnyxSong" }
    }

    fun adjustDta(dta: ByteArray): ByteArray {
        val text = String(stripBom(dta), Charsets.ISO_8859_1)
        val rating = Regex("""(\(\s*rating\b|'rating')(\s*)4\b""")
            .replace(text) { "${it.groupValues[1]}${it.groupValues[2]}2" }
        val songId = Regex("""(\(\s*song_id\b|'song_id')(\s*)([A-Za-z_][A-Za-z0-9_]*)""")
        val replaced = songId.replace(rating) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}${songIdNumber(match.groupValues[3])}"
        }
        return replaced.toByteArray(Charsets.ISO_8859_1)
    }

    fun songIdNumber(symbol: String): Int {
        var hash = 0
        for (b in symbol.toByteArray(Charsets.ISO_8859_1)) {
            hash = hash * 31 + (b.toInt() and 0xFF)
        }
        var n = Math.floorMod(hash, 1_000_000_000)
        if (n < 10_000_000) n += 10_000_000
        return n
    }

    fun swapPng(bytes: ByteArray): ByteArray {
        if (bytes.size <= 0x20) return bytes
        val out = bytes.copyOf()
        var i = 0x20
        while (i + 1 < out.size) {
            val tmp = out[i]
            out[i] = out[i + 1]
            out[i + 1] = tmp
            i += 2
        }
        return out
    }

    fun fixMogg(data: ByteArray): ByteArray {
        if (data.isEmpty() || data[0] == 0x0A.toByte() || data.size < 36) return data
        val enc = le32(data, 0)
        val entries = le32(data, 16)
        val at = 20 + entries * 8 + 32
        if (at < 0 || at + 16 > data.size) return data
        val mask = data.copyOfRange(at, at + 16)
        val good = when {
            mask.contentEquals(OLD_C3_MASK) -> NEW_C3_MASK
            enc == 12 && mask.contentEquals(OLD_V12_MASK) -> NEW_V12_MASK
            else -> return data
        }
        val out = data.copyOf()
        good.copyInto(out, at)
        return out
    }

    internal fun songTitle(dta: ByteArray): String? {
        val text = String(stripBom(dta), Charsets.ISO_8859_1)
        return Regex("""(?:\(\s*name\b|'name')\s*"([^"]*)"""")
            .findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { '/' !in it }
    }

    internal fun isRb3(dta: ByteArray): Boolean = formats(dta).any { it >= 10 }

    private fun formats(dta: ByteArray): List<Int> {
        val text = String(stripBom(dta), Charsets.ISO_8859_1)
        return Regex("""(?:\(\s*format\b|'format')\s*(\d+)""")
            .findAll(text)
            .map { it.groupValues[1].toInt() }
            .toList()
    }

    private fun stripBom(data: ByteArray): ByteArray {
        if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) {
            return data.copyOfRange(3, data.size)
        }
        return data
    }

    private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    private fun md5(text: String): ByteArray = md5(text.toByteArray(Charsets.ISO_8859_1))

    private fun le32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    private val OLD_C3_MASK = byteArrayOf(
        0xC3.toByte(), 0xC3.toByte(), 0xC3.toByte(), 0xC3.toByte(),
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
    )
    private val NEW_C3_MASK = byteArrayOf(
        0xA5.toByte(), 0xCE.toByte(), 0xFD.toByte(), 0x06,
        0x11, 0x93.toByte(), 0x23, 0x21, 0xF8.toByte(), 0x87.toByte(), 0x85.toByte(), 0xEA.toByte(),
        0x95.toByte(), 0xE4.toByte(), 0x94.toByte(), 0xD4.toByte(),
    )
    private val OLD_V12_MASK = "llective-tools-b".toByteArray(Charsets.US_ASCII)
    private val NEW_V12_MASK = byteArrayOf(
        0xF1.toByte(), 0xB4.toByte(), 0xB8.toByte(), 0xB0.toByte(),
        0x48, 0xAF.toByte(), 0xCB.toByte(), 0x9B.toByte(),
        0x4B, 0x53, 0xE0.toByte(), 0x56, 0x64, 0x57, 0x68, 0x39,
    )
}
