package org.onyxite.quickconvert.convert

import java.security.MessageDigest

/**
 * Writes a non-finalized PS3 PKG the way Onyx `makePKG` does.
 * The payload is XOR-encrypted with the SHA-1 keystream from the QA digest.
 */
object PkgWriter {
    class Dir {
        val files = LinkedHashMap<String, ByteArray>()
        val dirs = LinkedHashMap<String, Dir>()

        fun add(path: String, bytes: ByteArray) {
            val parts = path.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return
            var dir = this
            for (part in parts.dropLast(1)) {
                dir = dir.dirs.getOrPut(part) { Dir() }
            }
            dir.files[parts.last()] = bytes
        }
    }

    fun write(root: Dir, contentId: String): ByteArray {
        val records = ArrayList<Rec>()
        walk(root, "", records)
        val names = ByteArrayBuilder()
        val nameAt = ArrayList<Int>(records.size)
        val nameLen = ArrayList<Int>(records.size)
        for (rec in records) {
            val raw = rec.path.toByteArray(Charsets.ISO_8859_1)
            nameAt += names.size
            nameLen += raw.size
            names.append(raw)
            names.append(ByteArray((16 - (raw.size % 16)) % 16))
        }
        val data = ByteArrayBuilder()
        val dataAt = ArrayList<Int>(records.size)
        for (rec in records) {
            dataAt += data.size
            data.append(rec.data)
            data.append(ByteArray((16 - (rec.data.size % 16)) % 16))
        }
        val recordBytes = ByteArray(records.size * 0x20)
        val recordsSize = recordBytes.size
        val nameBank = names.toByteArray()
        for (i in records.indices) {
            val at = i * 0x20
            put32(recordBytes, at, recordsSize + nameAt[i])
            put32(recordBytes, at + 4, nameLen[i])
            put64(recordBytes, at + 8, (recordsSize + nameBank.size + dataAt[i]).toLong())
            put64(recordBytes, at + 16, records[i].data.size.toLong())
            put32(recordBytes, at + 24, records[i].flags)
        }
        val entries = recordBytes + nameBank
        val payload = data.toByteArray()
        val contentsLength = (entries.size + payload.size).toLong()
        val metadata = metadata(contentsLength)
        val metadataOffset = 0xC0
        val metadataAndDigests = metadata.size + 0x40
        val dataOffset = metadataOffset + metadataAndDigests
        val totalSize = dataOffset + contentsLength + 0x60

        val headerForDigest = header(
            itemCount = records.size,
            totalSize = totalSize,
            dataOffset = dataOffset.toLong(),
            dataSize = contentsLength,
            metadataOffset = metadataOffset,
            metadataSize = metadataAndDigests,
            contentId = ByteArray(0x30),
            qa = ByteArray(0x10),
            riv = ByteArray(0x10),
        )
        val sha = MessageDigest.getInstance("SHA-1")
        for (rec in records) sha.update(rec.data)
        sha.update(headerForDigest)
        sha.update(entries)
        val qa = sha.digest().copyOf(16)
        val klicensee = xorStream(qa, -1L, ByteArray(16))
        val idBytes = contentId.toByteArray(Charsets.ISO_8859_1)
        val riv = ByteArray(16)
        klicensee.copyInto(riv, 0, 0, minOf(idBytes.size, klicensee.size))
        val contentField = ByteArray(0x30)
        idBytes.copyInto(contentField, 0, 0, minOf(idBytes.size, 0x30))
        val finalHeader = header(
            itemCount = records.size,
            totalSize = totalSize,
            dataOffset = dataOffset.toLong(),
            dataSize = contentsLength,
            metadataOffset = metadataOffset,
            metadataSize = metadataAndDigests,
            contentId = contentField,
            qa = qa,
            riv = riv,
        )
        val (digest1, digest2) = digests(finalHeader, metadata)
        val encrypted = ByteArray(entries.size + payload.size)
        val prefix = keyPrefix(qa)
        xorInto(prefix, 0L, entries, encrypted, 0)
        xorInto(prefix, entries.size / 16L, payload, encrypted, entries.size)
        val out = ByteArrayBuilder()
        out.append(finalHeader)
        out.append(digest1)
        out.append(metadata)
        out.append(digest2)
        out.append(encrypted)
        out.append(ByteArray(0x60))
        return out.toByteArray()
    }

    private fun walk(dir: Dir, prefix: String, out: MutableList<Rec>) {
        if (prefix.isNotEmpty()) out += Rec(prefix, ByteArray(0), 0x80000004.toInt())
        for (name in dir.files.keys.sorted()) {
            val bytes = dir.files.getValue(name)
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            out += Rec(path, bytes, flags(name, bytes))
        }
        for (name in dir.dirs.keys.sorted()) {
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            walk(dir.dirs.getValue(name), path, out)
        }
    }

    private fun flags(name: String, data: ByteArray): Int {
        val upper = name.uppercase()
        if (upper == "PARAM.SFO" || upper == "ICON0.PNG" || upper == "PIC1.PNG") return 3
        val edat = upper.endsWith(".EDAT") &&
            data.size >= 4 &&
            data[0] == 'N'.code.toByte() &&
            data[1] == 'P'.code.toByte() &&
            data[2] == 'D'.code.toByte() &&
            data[3] == 0.toByte()
        return if (edat) 0x80000002.toInt() else 0x80000003.toInt()
    }

    private fun metadata(contentsLength: Long): ByteArray {
        val out = ByteArrayBuilder()
        fun entry(id: Int, payload: ByteArray) {
            val raw = ByteArray(8 + payload.size)
            put32(raw, 0, id)
            put32(raw, 4, payload.size)
            payload.copyInto(raw, 8)
            out.append(raw)
        }
        entry(1, byteArrayOf(0, 0, 0, 3))
        entry(2, byteArrayOf(0, 0, 0, 5))
        entry(3, byteArrayOf(0, 0, 0, 0xE))
        val length = ByteArray(8)
        put64(length, 0, contentsLength)
        entry(4, length)
        entry(5, byteArrayOf(0x10, 0x61, 0x00, 0x00))
        return out.toByteArray()
    }

    private fun header(
        itemCount: Int,
        totalSize: Long,
        dataOffset: Long,
        dataSize: Long,
        metadataOffset: Int,
        metadataSize: Int,
        contentId: ByteArray,
        qa: ByteArray,
        riv: ByteArray,
    ): ByteArray {
        val raw = ByteArray(0x80)
        put32(raw, 0, 0x7F504B47)
        put16(raw, 4, 0)
        put16(raw, 6, 1)
        put32(raw, 8, metadataOffset)
        put32(raw, 12, 5)
        put32(raw, 16, metadataSize)
        put32(raw, 20, itemCount)
        put64(raw, 24, totalSize)
        put64(raw, 32, dataOffset)
        put64(raw, 40, dataSize)
        contentId.copyInto(raw, 0x30, 0, minOf(contentId.size, 0x30))
        qa.copyInto(raw, 0x60, 0, minOf(qa.size, 0x10))
        riv.copyInto(raw, 0x70, 0, minOf(riv.size, 0x10))
        return raw
    }

    private fun digests(header: ByteArray, metadata: ByteArray): Pair<ByteArray, ByteArray> {
        val headerSha = trimmedSha1(header)
        val metaSha = trimmedSha1(metadata)
        val pad = ByteArray(0x30)
        val enc1 = xorStream(metaSha, 0L, pad)
        val enc2 = xorStream(headerSha, 0L, enc1)
        return headerSha + enc2 to metaSha + enc1
    }

    private fun trimmedSha1(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1").digest(data)
        return digest.copyOfRange(3, 19)
    }

    /**
     * Onyx `nonFinalizedCryptParallel`: each 16-byte block is the first 16 bytes of
     * SHA-1(prefix || big-endian block index). Pieces of 64 KB are independent, so
     * large files are encrypted in parallel. The prefix is hashed again for every
     * block; cloning a [MessageDigest] drops that prefix on some Android versions.
     */
    private fun xorStream(keySource: ByteArray, startIndex: Long, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        xorInto(keyPrefix(keySource), startIndex, data, out, 0)
        return out
    }

    private fun keyPrefix(keySource: ByteArray): ByteArray {
        // 8+8+8+8 bytes of the key, then 0x18 zero bytes.
        val prefix = ByteArray(0x38)
        keySource.copyInto(prefix, 0, 0, minOf(8, keySource.size))
        keySource.copyInto(prefix, 8, 0, minOf(8, keySource.size))
        if (keySource.size > 8) {
            val n = minOf(8, keySource.size - 8)
            keySource.copyInto(prefix, 16, 8, 8 + n)
            keySource.copyInto(prefix, 24, 8, 8 + n)
        }
        return prefix
    }

    private fun xorInto(
        prefix: ByteArray,
        startIndex: Long,
        data: ByteArray,
        out: ByteArray,
        outOffset: Int,
    ) {
        if (data.isEmpty()) return
        val pieces = (data.size + PIECE_SIZE - 1) / PIECE_SIZE
        if (pieces == 1) {
            cryptPiece(prefix, startIndex, data, 0, data.size, out, outOffset)
            return
        }
        java.util.stream.IntStream.range(0, pieces).parallel().forEach { piece ->
            val from = piece * PIECE_SIZE
            val until = minOf(data.size, from + PIECE_SIZE)
            cryptPiece(prefix, startIndex + from / 16L, data, from, until, out, outOffset + from)
        }
    }

    private fun cryptPiece(
        prefix: ByteArray,
        startIndex: Long,
        data: ByteArray,
        from: Int,
        until: Int,
        out: ByteArray,
        outOffset: Int,
    ) {
        val md = MessageDigest.getInstance("SHA-1")
        val indexBytes = ByteArray(8)
        var pos = from
        var index = startIndex
        while (pos < until) {
            put64(indexBytes, 0, index)
            md.update(prefix)
            md.update(indexBytes)
            val block = md.digest()
            val n = minOf(16, until - pos)
            val dest = outOffset + (pos - from)
            for (i in 0 until n) {
                out[dest + i] = (data[pos + i].toInt() xor (block[i].toInt() and 0xFF)).toByte()
            }
            pos += n
            index += 1
        }
    }

    private const val PIECE_SIZE = 0x10000

    private class Rec(val path: String, val data: ByteArray, val flags: Int)

    private class ByteArrayBuilder {
        private var buf = ByteArray(256)
        var size = 0
            private set

        fun append(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            ensure(size + bytes.size)
            bytes.copyInto(buf, size)
            size += bytes.size
        }

        fun toByteArray() = buf.copyOf(size)

        private fun ensure(needed: Int) {
            if (needed <= buf.size) return
            var cap = buf.size
            while (cap < needed) cap *= 2
            buf = buf.copyOf(cap)
        }
    }

    private fun put16(raw: ByteArray, offset: Int, value: Int) {
        raw[offset] = (value shr 8).toByte()
        raw[offset + 1] = value.toByte()
    }

    private fun put32(raw: ByteArray, offset: Int, value: Int) {
        raw[offset] = (value ushr 24).toByte()
        raw[offset + 1] = (value ushr 16).toByte()
        raw[offset + 2] = (value ushr 8).toByte()
        raw[offset + 3] = value.toByte()
    }

    private fun put64(raw: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            raw[offset + i] = (value ushr (56 - 8 * i)).toByte()
        }
    }
}
