package org.onyxite.quickconvert.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConToPkgTest {
    @Test
    fun ratingAndSongIdAreAdjustedForPs3() {
        val dta = "(song (rating 4) (song_id my_song))".toByteArray(Charsets.ISO_8859_1)
        val text = String(ConToPkg.adjustDta(dta), Charsets.ISO_8859_1)
        assertTrue(text.contains("(rating 2)"))
        assertTrue(text.contains("(song_id ${ConToPkg.songIdNumber("my_song")})"))
    }

    @Test
    fun pngBytesAfterTheHeaderAreSwappedInPairs() {
        val input = ByteArray(0x24) { it.toByte() }
        val swapped = ConToPkg.swapPng(input)
        assertEquals(input[0x1F], swapped[0x1F])
        assertEquals(input[0x20], swapped[0x21])
        assertEquals(input[0x21], swapped[0x20])
    }

    @Test
    fun quotedDtaKeysSelectRb3AndTheSongTitle() {
        val dta = """
            (
               'thehappydictator_g'
               (
                  'name'
                  "The Happy Dictator"
               )
               (
                  'song'
                  (
                     'name'
                     "songs/thehappydictator_g/thehappydictator_g"
                  )
               )
               ('format' 10)
               ('song_id' 1780954167)
               ('rating' 1)
            )
        """.trimIndent().toByteArray(Charsets.ISO_8859_1)
        assertEquals("The Happy Dictator", ConToPkg.songTitle(dta))
        assertTrue(ConToPkg.isRb3(dta))
        val folder = ConToPkg.usrdirName(dta, ConToPkg.songTitle(dta)!!)
        assertTrue(folder.contains("TheHappyDictator"))
        assertTrue(folder.length <= 0x1B)
    }

    @Test
    fun quotedRatingAndSongIdAreAdjusted() {
        val dta = "('rating' 4) ('song_id' my_song)".toByteArray(Charsets.ISO_8859_1)
        val text = String(ConToPkg.adjustDta(dta), Charsets.ISO_8859_1)
        assertTrue(text.contains("'rating' 2"))
        assertTrue(text.contains("'song_id' ${ConToPkg.songIdNumber("my_song")}"))
        assertTrue(!text.contains("my_song"))
    }

    @Test
    fun pkgStartsWithMagicAndMatchesItsTotalSize() {
        val root = PkgWriter.Dir()
        root.add("USRDIR/song/songs/songs.dta", "()".toByteArray())
        val pkg = PkgWriter.write(root, "UP8802-BLUS30463_00-Osong")
        assertEquals(0x7F, pkg[0].toInt() and 0xFF)
        assertEquals(0x50, pkg[1].toInt() and 0xFF)
        assertEquals(0x4B, pkg[2].toInt() and 0xFF)
        assertEquals(0x47, pkg[3].toInt() and 0xFF)
        var total = 0L
        for (i in 0 until 8) total = (total shl 8) or (pkg[24 + i].toLong() and 0xFF)
        assertEquals(total, pkg.size.toLong())
        val names = decryptNames(pkg)
        assertTrue(names.any { it.endsWith("songs.dta") })
        assertTrue(names.all { it.isNotEmpty() })
    }

    @Test
    fun largeFileSurvivesParallelKeystream() {
        val payload = ByteArray(0x10000 + 100) { (it * 17).toByte() }
        val root = PkgWriter.Dir()
        root.add("USRDIR/song/songs/audio.mogg", payload)
        val pkg = PkgWriter.write(root, "UP8802-BLUS30463_00-Osong")
        val count = be32(pkg, 0x14)
        val dataOff = be64(pkg, 0x20).toInt()
        val qa = pkg.copyOfRange(0x60, 0x70)
        val records = decrypt(pkg, qa, dataOff, 0, count * 0x20)
        var found = false
        for (i in 0 until count) {
            val at = i * 0x20
            val nameOff = be32(records, at)
            val nameLen = be32(records, at + 4)
            val fileOff = be64(records, at + 8)
            val fileLen = be64(records, at + 16)
            val name = String(decrypt(pkg, qa, dataOff, nameOff, nameLen), Charsets.ISO_8859_1)
            if (!name.endsWith("audio.mogg")) continue
            found = true
            assertEquals(payload.size.toLong(), fileLen)
            val decoded = decrypt(pkg, qa, dataOff, fileOff.toInt(), payload.size)
            assertTrue(payload.contentEquals(decoded))
        }
        assertTrue(found)
    }

    @Test
    fun nonFinalizedKeystreamMatchesOnyx() {
        val qa = ByteArray(16) { it.toByte() }
        val root = PkgWriter.Dir()
        root.add("A", byteArrayOf(1, 2, 3, 4))
        val pkg = PkgWriter.write(root, "UP8802-BLUS30463_00-Osong")
        assertEquals(listOf("A"), decryptNames(pkg))
        assertEquals(
            "b886318ba10b52de6c7b57179a1e4831",
            keystream(qa, 0).joinToString("") { "%02x".format(it) },
        )
        assertEquals(
            "ce531c3f9a931ca1364638b2acaa77aa",
            keystream(qa, -1L).joinToString("") { "%02x".format(it) },
        )
    }

    private fun decryptNames(pkg: ByteArray): List<String> {
        val count = be32(pkg, 0x14)
        val dataOff = be64(pkg, 0x20).toInt()
        val qa = pkg.copyOfRange(0x60, 0x70)
        val records = decrypt(pkg, qa, dataOff, 0, count * 0x20)
        val names = ArrayList<String>(count)
        for (i in 0 until count) {
            val at = i * 0x20
            val nameOff = be32(records, at)
            val nameLen = be32(records, at + 4)
            val fileOff = be64(records, at + 8)
            assertEquals(0, nameOff % 16)
            assertEquals(0L, fileOff % 16)
            val raw = decrypt(pkg, qa, dataOff, nameOff, nameLen)
            names += String(raw, Charsets.ISO_8859_1)
        }
        return names
    }

    private fun decrypt(pkg: ByteArray, qa: ByteArray, dataOff: Int, offset: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val abs = offset + done
            val block = keystream(qa, abs / 16L)
            val n = minOf(16 - (abs % 16), length - done)
            for (i in 0 until n) out[done + i] = (pkg[dataOff + abs + i].toInt() xor block[abs % 16 + i].toInt()).toByte()
            done += n
        }
        return out
    }

    private fun keystream(qa: ByteArray, index: Long): ByteArray {
        val indexBytes = ByteArray(8)
        for (i in 0 until 8) indexBytes[i] = (index ushr (56 - 8 * i)).toByte()
        return sha1(prefix(qa) + indexBytes).copyOf(16)
    }

    private fun prefix(qa: ByteArray): ByteArray {
        val prefix = ByteArray(0x38)
        qa.copyInto(prefix, 0, 0, 8)
        qa.copyInto(prefix, 8, 0, 8)
        qa.copyInto(prefix, 16, 8, 16)
        qa.copyInto(prefix, 24, 8, 16)
        return prefix
    }

    private fun sha1(data: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-1").digest(data)

    private fun be32(data: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until 4) value = (value shl 8) or (data[offset + i].toInt() and 0xFF)
        return value
    }

    private fun be64(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        return value
    }

    @Test
    fun shift0PackageReadsAConsecutiveFile() {
        val data = ByteArray(0xE000)
        data[0] = 'C'.code.toByte()
        data[1] = 'O'.code.toByte()
        data[2] = 'N'.code.toByte()
        data[3] = ' '.code.toByte()
        putBe32(data, 0x340, 0xB000)
        data[0x37C] = 1
        putBe32(data, 0x395, 2)
        val name = "hello.txt".toByteArray(Charsets.US_ASCII)
        name.copyInto(data, 0xC000)
        data[0xC000 + 0x28] = (name.size or 0x40).toByte()
        data[0xC000 + 0x29] = 1
        data[0xC000 + 0x2F] = 1
        data[0xC000 + 0x32] = 0xFF.toByte()
        data[0xC000 + 0x33] = 0xFF.toByte()
        putBe32(data, 0xC000 + 0x34, 2)
        data[0xB000 + 0x14] = 0x80.toByte()
        data[0xD000] = 'h'.code.toByte()
        data[0xD000 + 1] = 'i'.code.toByte()

        val files = StfsExtractor.extract(data)
        assertEquals(1, files.size)
        assertEquals("hello.txt", files[0].path)
        assertEquals("hi", String(files[0].bytes, Charsets.US_ASCII))
    }

    private fun putBe32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }
}
