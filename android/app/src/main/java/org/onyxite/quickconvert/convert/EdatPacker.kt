package org.onyxite.quickconvert.convert

import java.io.File

/** Packs a MIDI file into `.mid.edat` with the Onyx Rock Band custom license. */
object EdatPacker {
    private var loaded = false
    private var unavailable = false

    val available: Boolean
        get() {
            load()
            return loaded
        }

    fun encrypt(
        cacheDir: File,
        fileName: String,
        midi: ByteArray,
        contentId: ByteArray,
        klic: ByteArray,
    ): ByteArray {
        load()
        if (!loaded) throw IllegalStateException("EDAT library is not loaded")
        val dir = File(cacheDir, "edat").apply { mkdirs() }
        val stamp = System.nanoTime().toString()
        val input = File(dir, "$stamp.mid")
        val output = File(dir, "$stamp.edat")
        input.writeBytes(midi)
        val ok = packNative(input.absolutePath, output.absolutePath, fileName, contentId, klic)
        input.delete()
        if (!ok || !output.exists() || output.length() == 0L) {
            output.delete()
            throw IllegalStateException("EDAT encryption failed")
        }
        val packed = output.readBytes()
        output.delete()
        return packed
    }

    private fun load() {
        if (loaded || unavailable) return
        try {
            System.loadLibrary("edat")
            loaded = true
        } catch (_: UnsatisfiedLinkError) {
            unavailable = true
        }
    }

    private external fun packNative(
        inputPath: String,
        outputPath: String,
        hashName: String,
        contentId: ByteArray,
        klic: ByteArray,
    ): Boolean
}
