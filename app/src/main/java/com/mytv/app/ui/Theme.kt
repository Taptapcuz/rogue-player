package com.mytv.app.ui

import com.mytv.app.data.Channel
import com.mytv.app.data.Entry

/** Palette: blue-dominant (royal/electric blue) on near-black — implayer layout, no red. */
object Palette {
    const val BG = 0xFF0A0A0F.toInt()      // near-black (slight cool blue)
    const val PANEL = 0xFF14141C.toInt()   // sidebar / panels
    const val CARD = 0xFF1C1C26.toInt()    // cards
    const val CARD_HI = 0xFF262635.toInt() // hover / selected
    const val LINE = 0xFF2E2E40.toInt()    // dividers / track
    const val TEXT = 0xFFF2F4FF.toInt()    // white with a touch of blue
    const val MUTED = 0xFFA0A6C0.toInt()
    const val DIM = 0xFF6E7499.toInt()
    const val LIVE = 0xFF3FA2FF.toInt()    // LIVE indicator — blue, not red

    // Brand accents — a Crips-blue family, blue is default
    val ACCENTS = intArrayOf(0xFF3B7BFF.toInt(), 0xFF2BA8E6.toInt(), 0xFF7C4DFF.toInt())

    /** Muted placeholder artwork colors — cool blues/violets/teals only. */
    val HUES = intArrayOf(
        0xFF3B6FD6.toInt(), 0xFF2E86AB.toInt(), 0xFF5A7BE0.toInt(), 0xFF3A8E8E.toInt(),
        0xFF4A67C4.toInt(), 0xFF5978D8.toInt(), 0xFF2F8BD9.toInt(), 0xFF4069B8.toInt(),
        0xFF3EA0B4.toInt(), 0xFF6458C9.toInt(), 0xFF4A8FCF.toInt(), 0xFF3D7BB8.toInt(),
    )
}

/** Item kind for a browse row. */
sealed interface BrowseItem {
    data class ChannelRow(val ch: Channel) : BrowseItem
    data class EntryRow(val e: Entry, val isMovie: Boolean) : BrowseItem
    data class GuideHeader(val time: String) : BrowseItem
    data class GuideShow(val ch: Channel, val title: String, val start: String, val progress: Float) : BrowseItem
    data class Message(val text: String) : BrowseItem
    data class SectionTitle(val text: String, val sub: String) : BrowseItem
    data class Setting(val label: String, val detail: String?, val on: Boolean?, val isSlider: Boolean) : BrowseItem
}
