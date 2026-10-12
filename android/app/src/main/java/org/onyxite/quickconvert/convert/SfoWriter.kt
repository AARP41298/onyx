package org.onyxite.quickconvert.convert

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.Deflater

/** PARAM.SFO writer, matching Onyx `makeSFO`. */
object SfoWriter {
    fun rockBand(title: String, titleId: String): ByteArray {
        val entries = listOf(
            "APP_VER" to Text("01.00", 8),
            "ATTRIBUTE" to Word(0),
            "BOOTABLE" to Word(1),
            "CATEGORY" to Text("HG", 4),
            "PARENTAL_LEVEL" to Word(0),
            "PS3_SYSTEM_VER" to Text("01.0000", 8),
            "RESOLUTION" to Word(1),
            "SOUND_FORMAT" to Word(1),
            "TITLE" to Text(title.ifBlank { "Quick Convert" }, 128),
            "TITLE_ID" to Text(titleId, 16),
            "VERSION" to Text("01.00", 8),
        ).sortedBy { it.first }
        return make(entries)
    }

    private fun make(entries: List<Pair<String, Value>>): ByteArray {
        val index = ByteArrayOutputStream()
        val keys = ByteArrayOutputStream()
        val data = ByteArrayOutputStream()
        var keyPos = 0
        var dataPos = 0
        for ((key, value) in entries) {
            val keyBytes = key.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0)
            val (format, used, total, payload) = value.encode()
            put16(index, keyPos)
            put16be(index, format)
            put32(index, used)
            put32(index, total)
            put32(index, dataPos)
            keys.write(keyBytes)
            data.write(payload)
            keyPos += keyBytes.size
            dataPos += total
        }
        val indexBytes = index.toByteArray()
        val keyBytes = pad4(keys.toByteArray())
        val keysStart = 20 + indexBytes.size
        val dataStart = keysStart + keyBytes.size
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte()))
        put32(out, 0x00000101)
        put32(out, keysStart)
        put32(out, dataStart)
        put32(out, entries.size)
        out.write(indexBytes)
        out.write(keyBytes)
        out.write(data.toByteArray())
        return out.toByteArray()
    }

    private sealed class Value {
        abstract fun encode(): Encoded
    }

    private class Text(val text: String, val allocated: Int) : Value() {
        override fun encode(): Encoded {
            val raw = text.toByteArray(StandardCharsets.UTF_8)
            val usedRoom = allocated - 1
            val kept = raw.copyOf(minOf(raw.size, usedRoom.coerceAtLeast(0)))
            val payload = ByteArray(allocated)
            kept.copyInto(payload)
            return Encoded(0x0402, kept.size + 1, allocated, payload)
        }
    }

    private class Word(val value: Int) : Value() {
        override fun encode(): Encoded {
            val payload = ByteArray(4)
            payload[0] = value.toByte()
            payload[1] = (value ushr 8).toByte()
            payload[2] = (value ushr 16).toByte()
            payload[3] = (value ushr 24).toByte()
            return Encoded(0x0404, 4, 4, payload)
        }
    }

    private data class Encoded(val format: Int, val used: Int, val total: Int, val payload: ByteArray)

    private fun pad4(bytes: ByteArray): ByteArray {
        val extra = (4 - (bytes.size % 4)) % 4
        return if (extra == 0) bytes else bytes + ByteArray(extra)
    }

    private fun put16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun put16be(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun put32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}

/** Small ICON0.PNG so the package has the icon PS3 packages expect. */
object IconPng {
    fun bytes(): ByteArray {
        val width = 64
        val height = 64
        val raw = ByteArray(height * (1 + width * 3))
        var i = 0
        for (y in 0 until height) {
            raw[i++] = 0
            for (x in 0 until width) {
                raw[i++] = 0x2F
                raw[i++] = 0x7D.toByte()
                raw[i++] = 0x46
            }
        }
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            compressed.write(buf, 0, n)
        }
        deflater.end()
        val zlib = compressed.toByteArray()
        val out = ByteArrayOutputStream()
        fun chunk(type: String, data: ByteArray) {
            put32be(out, data.size)
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            out.write(typeBytes)
            out.write(data)
            val crc = CRC32()
            crc.update(typeBytes)
            crc.update(data)
            put32be(out, crc.value.toInt())
        }
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        put32be(ihdr, 0, width)
        put32be(ihdr, 4, height)
        ihdr[8] = 8
        ihdr[9] = 2
        chunk("IHDR", ihdr)
        chunk("IDAT", zlib)
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun put32be(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun put32be(raw: ByteArray, offset: Int, value: Int) {
        raw[offset] = (value ushr 24).toByte()
        raw[offset + 1] = (value ushr 16).toByte()
        raw[offset + 2] = (value ushr 8).toByte()
        raw[offset + 3] = value.toByte()
    }
}
