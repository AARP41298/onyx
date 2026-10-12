package org.onyxite.quickconvert.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceKindTest {
    @Test
    fun stfsMagicBeatsAMissingExtension() {
        assertEquals(SourceKind.CON, SourceKind.identify("customs", "CON ".toByteArray()))
        assertEquals(SourceKind.LIVE, SourceKind.identify("customs", "LIVE".toByteArray()))
        assertEquals(SourceKind.PIRS, SourceKind.identify("customs", "PIRS".toByteArray()))
    }

    @Test
    fun pkgExtensionWinsEvenWithoutAHeader() {
        assertEquals(SourceKind.PKG, SourceKind.identify("song.pkg", ByteArray(0)))
    }

    @Test
    fun pkgMagicIsRecognized() {
        val header = byteArrayOf(0x7F, 0x50, 0x4B, 0x47)
        assertEquals(SourceKind.PKG, SourceKind.identify("nodots", header))
    }

    @Test
    fun rbaAndSongsDtaFollowOnyx() {
        assertEquals(SourceKind.RBA, SourceKind.identify("track.rba", ByteArray(0)))
        assertEquals(SourceKind.SONGS_DTA, SourceKind.identify("songs.dta", ByteArray(0)))
        assertEquals(SourceKind.UNKNOWN, SourceKind.identify("notes.txt", ByteArray(0)))
    }
}
