package org.onyxite.quickconvert.convert

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputTemplateTest {
    @Test
    fun conKeepsItsExtensionInTheBaseName() {
        val planned = OutputTemplate.plan("Band.con", OutputTemplate.DEFAULT)
        assertEquals("Band.con_convert.pkg", planned.fileName)
        assertEquals("Band.con_convert.pkg", planned.relativePath)
    }

    @Test
    fun pkgSuffixIsStrippedBeforeConvert() {
        assertEquals(
            "Band_convert.pkg",
            OutputTemplate.plan("Band.pkg", OutputTemplate.DEFAULT).fileName,
        )
        assertEquals(
            "Band_convert.pkg",
            OutputTemplate.plan("Band.PKG", OutputTemplate.DEFAULT).fileName,
        )
    }

    @Test
    fun titleAndArtistTokensAreSanitized() {
        val planned = OutputTemplate.plan(
            inputFileName = "song.con",
            template = "%artist% - %title%%ext%",
            title = "A:B",
            artist = "The Band",
        )
        assertEquals("The Band - A_B.pkg", planned.fileName)
    }

    @Test
    fun extraFolderUnderTheOutputDirIsKept() {
        val planned = OutputTemplate.plan(
            "song.con",
            "%input_dir%/out/%input_base%_convert%ext%",
        )
        assertEquals("out/song.con_convert.pkg", planned.relativePath)
    }

    @Test
    fun blankTemplateFallsBackToAPkgName() {
        assertEquals("song.con_convert.pkg", OutputTemplate.plan("song.con", "   ").fileName)
    }
}
