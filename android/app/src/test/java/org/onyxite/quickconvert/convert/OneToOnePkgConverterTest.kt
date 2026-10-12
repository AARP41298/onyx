package org.onyxite.quickconvert.convert

import org.junit.Assert.assertTrue
import org.junit.Test

class OneToOnePkgConverterTest {
    @Test
    fun usrdirNameIsAlphanumericAndShort() {
        val name = ConToPkg.usrdirName("dta".toByteArray(), "Happy Dictator!")
        assertTrue(name.length <= 0x1B)
        assertTrue(name.all { it.isLetterOrDigit() })
        assertTrue(name.startsWith("O"))
    }
}
