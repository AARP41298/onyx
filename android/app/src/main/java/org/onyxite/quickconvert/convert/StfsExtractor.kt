package org.onyxite.quickconvert.convert

/**
 * Reads the file tree out of an Xbox 360 CON, LIVE, or PIRS package.
 * Block layout matches Onyx `getFileEntries` / `fixBlockNumber`.
 */
object StfsExtractor {
    data class File(val path: String, val bytes: ByteArray)

    fun extract(data: ByteArray): List<File> {
        if (data.size < 0x3B0) throw IllegalArgumentException("Package is too small to be CON/LIVE/PIRS")
        val magic = String(data, 0, 4, Charsets.ISO_8859_1)
        if (magic != "CON " && magic != "LIVE" && magic != "PIRS") {
            throw IllegalArgumentException("Not a CON, LIVE, or PIRS package")
        }
        val descriptor = be32(data, 0x3A9)
        if (descriptor != 0) throw IllegalArgumentException("SVOD packages are not supported")

        val headerSize = be32(data, 0x340).toLong() and 0xFFFFFFFFL
        val shift = if ((((headerSize + 0xFFF) and 0xF000L) shr 12) == 0xBL) 0 else 1
        val allocated = be32(data, 0x395)
        val tableCount = le16(data, 0x37C)
        val tableBlock = le24(data, 0x37E)
        if (tableCount <= 0) return emptyList()

        val table = readChain(data, tableBlock, tableCount.toLong() * 0x1000, shift, allocated)
        val entries = ArrayList<Entry>()
        var offset = 0
        while (offset + 0x40 <= table.size) {
            val entry = readEntry(table, offset) ?: break
            entries += entry
            offset += 0x40
        }

        fun pathOf(index: Int): String {
            if (index < 0) return ""
            val entry = entries[index]
            val parent = pathOf(entry.pathIndex)
            return if (parent.isEmpty()) entry.name else "$parent/${entry.name}"
        }
        return entries.mapIndexedNotNull { index, entry ->
            if (entry.directory) return@mapIndexedNotNull null
            val bytes = readChain(
                data,
                entry.firstBlock,
                entry.size.toLong() and 0xFFFFFFFFL,
                shift,
                allocated,
                consecutive = entry.consecutive,
                blockCount = entry.blocks,
            )
            File(pathOf(index), bytes)
        }
    }

    /** Real block index for a file-data block. Hash-table blocks are not passed through this. */
    fun dataBlock(fileBlock: Int, shift: Int): Int {
        var real = fileBlock
        if (fileBlock >= 0xAA) real += ((fileBlock / 0xAA) + 1) shl shift
        if (fileBlock >= 0x70E4) real += ((fileBlock / 0x70E4) + 1) shl shift
        return real
    }

    private fun hashLocation(fileBlock: Int, tableOffset: Int, shift: Int): Pair<Int, Int> {
        val spacing = if (shift == 0) 0xAB else 0xAC
        val record = fileBlock % 0xAA
        var table = (fileBlock / 0xAA) * spacing
        if (fileBlock >= 0xAA) table += ((fileBlock / 0x70E4) + 1) shl shift
        if (fileBlock >= 0x70E4) table += 1 shl shift
        table += tableOffset - (1 shl shift)
        return table to record
    }

    private fun readHash(data: ByteArray, fileBlock: Int, shift: Int): Hash {
        fun read(tableOffset: Int): Hash {
            val (table, record) = hashLocation(fileBlock, tableOffset, shift)
            val at = 0xC000 + table * 0x1000 + record * 0x18
            val raw = readExact(data, at, 0x18)
            val status = raw[0x14].toInt() and 0xFF
            val next = ((raw[0x15].toInt() and 0xFF) shl 16) or
                ((raw[0x16].toInt() and 0xFF) shl 8) or
                (raw[0x17].toInt() and 0xFF)
            return Hash(status, next)
        }
        val primary = read(0)
        return if (shift == 1 && primary.status < 0x80) read(1) else primary
    }

    private fun readChain(
        data: ByteArray,
        firstBlock: Int,
        size: Long,
        shift: Int,
        allocated: Int,
        consecutive: Boolean = false,
        blockCount: Int = 0,
    ): ByteArray {
        if (size <= 0) return ByteArray(0)
        val blocks = if (consecutive) {
            (0 until blockCount).map { firstBlock + it }
        } else {
            val found = ArrayList<Int>()
            var block = firstBlock
            var left = size
            var status = 0x80
            var first = true
            while (left > 0 && block < allocated && status >= 0x80 && (first || block > 0)) {
                if (block < 0) break
                first = false
                found += block
                val hash = readHash(data, block, shift)
                val chunk = minOf(0x1000L, left)
                left -= chunk
                block = hash.next
                status = hash.status
            }
            found
        }
        val out = ByteArray(size.toInt())
        var written = 0
        for (block in blocks) {
            if (written >= out.size) break
            val at = 0xC000 + dataBlock(block, shift) * 0x1000
            val n = minOf(0x1000, out.size - written)
            val chunk = readExact(data, at, n)
            chunk.copyInto(out, written, 0, chunk.size)
            written += chunk.size
        }
        return out
    }

    private fun readEntry(table: ByteArray, offset: Int): Entry? {
        val lengthByte = table[offset + 0x28].toInt() and 0xFF
        val nameLength = lengthByte and 0x3F
        if (nameLength == 0) return null
        val name = String(table, offset, nameLength, Charsets.ISO_8859_1)
        if (name.isEmpty()) return null
        return Entry(
            name = name,
            consecutive = lengthByte and 0x40 != 0,
            directory = lengthByte and 0x80 != 0,
            blocks = le24(table, offset + 0x29),
            firstBlock = le24(table, offset + 0x2F),
            pathIndex = be16(table, offset + 0x32).toInt(),
            size = be32(table, offset + 0x34),
        )
    }

    private data class Entry(
        val name: String,
        val consecutive: Boolean,
        val directory: Boolean,
        val blocks: Int,
        val firstBlock: Int,
        val pathIndex: Int,
        val size: Int,
    )

    private data class Hash(val status: Int, val next: Int)

    private fun readExact(data: ByteArray, offset: Int, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset.toLong() + length > data.size) {
            throw IllegalArgumentException("Package ended early while reading at ${offset.toString(16)}")
        }
        return data.copyOfRange(offset, offset + length)
    }

    private fun be32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun be16(data: ByteArray, offset: Int): Short =
        (((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)).toShort()

    private fun le16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun le24(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)
}
