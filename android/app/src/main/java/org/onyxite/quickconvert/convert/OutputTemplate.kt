package org.onyxite.quickconvert.convert

/**
 * One-to-one output names, matching Onyx `quickTemplate` for PKG.
 *
 * `%ext%` is `.pkg`. `%input_base%` is the file name, with a trailing `.pkg`
 * removed. `%input_dir%` is omitted here because Android saves into the
 * folder the user picks, which stands in for the source directory.
 */
object OutputTemplate {
    const val DEFAULT = "%input_dir%/%input_base%_convert%ext%"
    const val PKG_EXT = ".pkg"

    data class Planned(val relativePath: String) {
        val fileName: String get() = relativePath.substringAfterLast('/')
    }

    fun plan(
        inputFileName: String,
        template: String,
        title: String = "",
        artist: String = "",
    ): Planned {
        val base = inputBase(inputFileName)
        val expanded = template
            .replace("%input_dir%", "")
            .replace("%input_base%", sanitizePiece(base))
            .replace("%ext%", PKG_EXT)
            .replace("%title%", sanitizePiece(title))
            .replace("%artist%", sanitizePiece(artist))
        val parts = expanded
            .replace('\\', '/')
            .split('/')
            .map { sanitizePiece(it) }
            .filter { it.isNotEmpty() }
        val relative = parts.joinToString("/").ifEmpty {
            "${sanitizePiece(base).ifEmpty { "song" }}_convert$PKG_EXT"
        }
        return Planned(relative)
    }

    /** Onyx only strips a `.pkg` suffix from the base name. Other extensions stay. */
    fun inputBase(fileName: String): String {
        val slash = maxOf(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'))
        val name = if (slash >= 0) fileName.substring(slash + 1) else fileName
        return if (name.endsWith(".pkg", ignoreCase = true)) name.dropLast(4) else name
    }

    fun sanitizePiece(raw: String): String {
        if (raw.isEmpty()) return ""
        val mapped = buildString(raw.length) {
            for (c in raw) {
                val illegal = c.code < 0x20 || c.code > 0x7E || c in INVALID
                append(if (illegal) '_' else c)
            }
        }
        val trimmed = mapped.trim().trimEnd('.')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.uppercase() in RESERVED) "${trimmed}_" else trimmed
    }

    private const val INVALID = "<>:\"/\\|?*"

    private val RESERVED = setOf(
        "",
        "CON", "PRN", "AUX", "NUL",
        "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )
}
