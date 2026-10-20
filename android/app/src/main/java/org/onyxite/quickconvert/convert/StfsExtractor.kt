package org.onyxite.quickconvert.convert

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Reads the file tree out of an Xbox 360 CON, LIVE, or PIRS package.
 * Block layout matches Onyx `getFileEntries` / `fixBlockNumber`.
 * File bodies are read on demand so a large mogg is not copied into memory.
 */
object StfsExtractor {
    interface Source {
        val size: Long
        fun read(offset: Long, dest: ByteArray, destOff: Int, length: Int)
    }

    class BytesSource(private val data: ByteArray) : Source {
        override val size: Long = data.size.toLong()

        override fun read(offset: Long, dest: ByteArray, destOff: Int, length: Int) {
            val start = offset.toInt()
            if (offset < 0 || length < 0 || start.toLong() != offset || start + length > data.size) {
                throw IllegalArgumentException("Package ended early while reading at ${offset.toString(16)}")
            }
            data.copyInto(dest, destOff, start, start + length)
        }
    }

    class ChannelSource(private val channel: FileChannel) : Source {
        override val size: Long = channel.size()

        override fun read(offset: Long, dest: ByteArray, destOff: Int, length: Int) {
            if (offset < 0 || length < 0 || offset + length > size) {
                throw IllegalArgumentException("Package ended early while reading at ${offset.toString(16)}")
            }
            val buf = ByteBuffer.wrap(dest, destOff, length)
            synchronized(channel) {
                channel.position(offset)
                while (buf.hasRemaining()) {
                    if (channel.read(buf) < 0) {
                        throw IllegalArgumentException("Package ended early while reading at ${offset.toString(16)}")
                    }
                }
            }
        }
    }

    data class File(val path: String, val bytes: ByteArray)

    class StoredFile internal constructor(
        val path: String,
        val size: Long,
        private val blocks: IntArray,
        private val shift: Int,
        private val source: Source,
    ) {
        fun readAt(position: Long, dest: ByteArray, destOff: Int, length: Int): Int {
            if (position < 0 || length <= 0 || position >= size) return 0
            var left = minOf(length.toLong(), size - position).toInt()
            var pos = position
            var written = 0
            while (left > 0) {
                val blockIndex = (pos / 0x1000).toInt()
                if (blockIndex >= blocks.size) break
                val within = (pos % 0x1000).toInt()
                val n = minOf(left, 0x1000 - within)
                val at = 0xC000L + dataBlock(blocks[blockIndex], shift).toLong() * 0x1000L + within
                source.read(at, dest, destOff + written, n)
                pos += n
                written += n
                left -= n
            }
            return written
        }

        fun readAll(): ByteArray {
            if (size > Int.MAX_VALUE) throw IllegalArgumentException("File is too large to load")
            val out = ByteArray(size.toInt())
            var pos = 0
            while (pos < out.size) {
                val n = readAt(pos.toLong(), out, pos, out.size - pos)
                if (n <= 0) throw IllegalArgumentException("Package ended early while reading $path")
                pos += n
            }
            return out
        }
    }

    fun extract(data: ByteArray): List<File> = list(BytesSource(data)).map { File(it.path, it.readAll()) }

    fun list(source: Source): List<StoredFile> {
        if (source.size < 0x3B0) throw IllegalArgumentException("Package is too small to be CON/LIVE/PIRS")
        val magic = String(readExact(source, 0, 4), Charsets.ISO_8859_1)
        if (magic != "CON " && magic != "LIVE" && magic != "PIRS") {
            throw IllegalArgumentException("Not a CON, LIVE, or PIRS package")
        }
        val descriptor = be32(readExact(source, 0x3A9, 4), 0)
        if (descriptor != 0) throw IllegalArgumentException("SVOD packages are not supported")

        val headerSize = be32(readExact(source, 0x340, 4), 0).toLong() and 0xFFFFFFFFL
        val shift = if ((((headerSize + 0xFFF) and 0xF000L) shr 12) == 0xBL) 0 else 1
        val allocated = be32(readExact(source, 0x395, 4), 0)
        val tableCount = le16(readExact(source, 0x37C, 2), 0)
        val tableBlock = le24(readExact(source, 0x37E, 3), 0)
        if (tableCount <= 0) return emptyList()

        val table = readChain(source, tableBlock, tableCount.toLong() * 0x1000, shift, allocated)
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
            val fileSize = entry.size.toLong() and 0xFFFFFFFFL
            val blocks = blockChain(
                source,
                entry.firstBlock,
                fileSize,
                shift,
                allocated,
                consecutive = entry.consecutive,
                blockCount = entry.blocks,
            )
            StoredFile(pathOf(index), fileSize, blocks, shift, source)
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

    private fun readHash(source: Source, fileBlock: Int, shift: Int): Hash {
        fun read(tableOffset: Int): Hash {
            val (table, record) = hashLocation(fileBlock, tableOffset, shift)
            val at = 0xC000L + table.toLong() * 0x1000L + record * 0x18
            val raw = readExact(source, at, 0x18)
            val status = raw[0x14].toInt() and 0xFF
            val next = ((raw[0x15].toInt() and 0xFF) shl 16) or
                ((raw[0x16].toInt() and 0xFF) shl 8) or
                (raw[0x17].toInt() and 0xFF)
            return Hash(status, next)
        }
        val primary = read(0)
        return if (shift == 1 && primary.status < 0x80) read(1) else primary
    }

    private fun blockChain(
        source: Source,
        firstBlock: Int,
        size: Long,
        shift: Int,
        allocated: Int,
        consecutive: Boolean,
        blockCount: Int,
    ): IntArray {
        if (size <= 0) return IntArray(0)
        if (consecutive) {
            val count = if (blockCount > 0) blockCount else ((size + 0xFFF) / 0x1000).toInt()
            return IntArray(count) { firstBlock + it }
        }
        val found = ArrayList<Int>()
        var block = firstBlock
        var left = size
        var status = 0x80
        var first = true
        while (left > 0 && block < allocated && status >= 0x80 && (first || block > 0)) {
            if (block < 0) break
            first = false
            found += block
            val hash = readHash(source, block, shift)
            left -= minOf(0x1000L, left)
            block = hash.next
            status = hash.status
        }
        return found.toIntArray()
    }

    private fun readChain(
        source: Source,
        firstBlock: Int,
        size: Long,
        shift: Int,
        allocated: Int,
    ): ByteArray {
        val blocks = blockChain(source, firstBlock, size, shift, allocated, consecutive = false, blockCount = 0)
        val out = ByteArray(size.toInt())
        var written = 0
        for (block in blocks) {
            if (written >= out.size) break
            val at = 0xC000L + dataBlock(block, shift).toLong() * 0x1000L
            val n = minOf(0x1000, out.size - written)
            source.read(at, out, written, n)
            written += n
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

    private fun readExact(source: Source, offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        source.read(offset, out, 0, length)
        return out
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
