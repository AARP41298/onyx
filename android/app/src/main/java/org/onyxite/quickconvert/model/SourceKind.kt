package org.onyxite.quickconvert.model

/**
 * Container kinds Quick Convert accepts. Detection follows Onyx
 * [loadQuickInput]: `.pkg` and `.rba` by name, then an STFS header, then `songs.dta`.
 */
enum class SourceKind(val label: String) {
    CON("CON (360)"),
    LIVE("LIVE (360)"),
    PIRS("PIRS (360)"),
    PKG("PKG (PS3)"),
    RBA("RBA"),
    SONGS_DTA("songs.dta"),
    UNKNOWN("Unrecognized"),
    ;

    companion object {
        private const val PKG_MAGIC = 0x7F504B47

        fun identify(fileName: String, header: ByteArray): SourceKind {
            val lower = fileName.lowercase()
            if (lower.endsWith(".pkg")) return PKG
            if (lower.endsWith(".rba")) return RBA
            if (header.size >= 4) {
                val tag = String(header, 0, 4, Charsets.ISO_8859_1)
                when (tag) {
                    "CON " -> return CON
                    "LIVE" -> return LIVE
                    "PIRS" -> return PIRS
                }
                val magic = ((header[0].toInt() and 0xFF) shl 24) or
                    ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
                if (magic == PKG_MAGIC) return PKG
            }
            if (lower == "songs.dta") return SONGS_DTA
            return UNKNOWN
        }
    }
}
