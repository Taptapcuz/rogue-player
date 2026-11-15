package com.mytv.app.ui

import com.mytv.app.data.Channel
import com.mytv.app.data.Entry
import com.mytv.app.data.Episode

/** Palette: blue-on-black — IMPlayer-clone, no red. */
object Palette {
    const val BG = 0xFF0B0B10.toInt()
    const val PANEL = 0xFF181820.toInt()
    const val CARD = 0xFF1D1D26.toInt()
    const val CARD_HI = 0xFF2A2A37.toInt()
    const val LINE = 0xFF2E2E3C.toInt()
    const val TEXT = 0xFFF3F5FF.toInt()
    const val MUTED = 0xFF9CA2C0.toInt()
    const val DIM = 0xFF6C7294.toInt()

    // Blue accents (default) — no red anywhere
    val ACCENTS = intArrayOf(0xFF3D7BFF.toInt(), 0xFF2BA8E6.toInt(), 0xFF7C4DFF.toInt())

    /** Muted poster hues — cool blues/teals/violets. */
    val HUES = intArrayOf(
        0xFF3B6FD6.toInt(), 0xFF2E86AB.toInt(), 0xFF5A7BE0.toInt(), 0xFF3A8E8E.toInt(),
        0xFF4A67C4.toInt(), 0xFF5978D8.toInt(), 0xFF2F8BD9.toInt(), 0xFF4069B8.toInt(),
        0xFF3EA0B4.toInt(), 0xFF6458C9.toInt(), 0xFF4A8FCF.toInt(), 0xFF3D7BB8.toInt(),
    )
}

/** Item kinds for the IMPlayer-clone browse list. */
sealed interface BrowseItem {
    data class ChannelRow(val ch: Channel) : BrowseItem
    data class EntryRow(val e: Entry, val isMovie: Boolean) : BrowseItem
    data class GuideShow(val ch: Channel, val title: String, val start: String) : BrowseItem
    data class Message(val text: String) : BrowseItem
    data class SectionTitle(val text: String, val sub: String) : BrowseItem
    data class ChannelCard(val ch: Channel) : BrowseItem     // rounded live-TV card
    data class PosterCard(val e: Entry, val isMovie: Boolean) : BrowseItem // poster card
    data class EpisodeItem(val ep: Episode, val seriesTitle: String) : BrowseItem // series episode
}
