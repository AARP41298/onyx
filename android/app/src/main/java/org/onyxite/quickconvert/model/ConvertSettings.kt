package org.onyxite.quickconvert.model

import org.onyxite.quickconvert.convert.OutputTemplate

/** PS3 USRDIR layout for one-to-one PKG output. Matches the desktop dropdown. */
enum class UsrdirMode(val label: String) {
    KEEP_ORIGINAL("Keep original USRDIR subfolders (if any)"),
    ONE_FOLDER("Combine into one new USRDIR subfolder per file"),
    SEPARATE_FOLDERS("Each song gets a new USRDIR subfolder"),
}

/**
 * Optional Quick Convert adjustments. They apply to the one-to-one PKG job
 * the same way the desktop checkboxes do.
 */
enum class Adjustment(val label: String, val detail: String) {
    BLACK_VENUE(
        "Black VENUE",
        "Replaces the VENUE track with a near-black background. Works on pre-RB3 songs and RBN2; not RB3 .milo venues.",
    ),
    NO_OVERDRIVE(
        "No Overdrive",
        "Removes overdrive phrases from all instruments.",
    ),
    NO_LANES_GBK(
        "No lanes (G/B/K)",
        "Removes trill and tremolo lanes from guitar, bass, keys, and their pro charts. Leaves pro keys glissandos.",
    ),
    NO_LANES_DRUMS(
        "No lanes (drums)",
        "Removes one-pad and two-pad rolls and swells from drums.",
    ),
    NO_DRUM_FILLS(
        "No drum fills",
        "Removes drum activation fills. Leaves a Big Rock Ending.",
    ),
    FORCE_22_FRET(
        "Force 22-fret protar",
        "Drops the Mustang pro guitar/bass chart so every controller plays the Squier chart.",
    ),
    UNMUTE_OVER_22(
        "Unmute >22-fret protar",
        "Unmutes pro guitar/bass notes above fret 22.",
    ),
    DECOMPRESS_MILO(
        "Decompress .milo_*",
        "Writes .milo files uncompressed.",
    ),
    AUTHOR_TO_DTA(
        "Author to DTA tag",
        "Copies C3 Magma author comments into the RB2/RB3 Deluxe author tag.",
    ),
    STRIP_TITLE_TAGS(
        "Strip title tags",
        "Removes tags such as (2x Bass Pedal) and author signatures like (Z) from titles.",
    ),
    NOTE_SHUFFLE(
        "Note shuffle",
        "Shuffles 5-fret and drum notes, and adds (Shuffle) to the title.",
    ),
    DECRYPT_MOGGS(
        "Decrypt MOGGs",
        "Decrypts MOGG audio. Unencrypted MOGGs need RB3Enhanced on PS3 RB3.",
    ),
}

data class ConvertSettings(
    val encryptMidEdat: Boolean = true,
    val usrdir: UsrdirMode = UsrdirMode.KEEP_ORIGINAL,
    val template: String = OutputTemplate.DEFAULT,
    val adjustments: Set<Adjustment> = emptySet(),
)

data class SongFile(
    val uri: String,
    val displayName: String,
    val kind: SourceKind,
    val sizeBytes: Long,
)
