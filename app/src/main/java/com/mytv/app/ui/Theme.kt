package com.mytv.app.ui

import com.mytv.app.data.Channel
import com.mytv.app.data.Entry

/** Palette carried over from the egui implayer build. */
object Palette {
    const val BG = 0xFF090B0F.toInt()
    const val PANEL = 0xFF0F1218.toInt()
    const val CARD = 0xFF161A21.toInt()
    const val CARD_HI = 0xFF1E232C.toInt()
    const val LINE = 0xFF242A35.toInt()
    const val TEXT = 0xFFE9EEF6.toInt()
    const val MUTED = 0xFF8A94A6.toInt()
    const val DIM = 0xFF60697A.toInt()
    const val LIVE = 0xFFFF4D5E.toInt()

    val ACCENTS = intArrayOf(0xFF6C5CE7.toInt(), 0xFF00C2A8.toInt(), 0xFFFF7A45.toInt())

    /** Fixed, deliberate-looking palette for placeholder artwork (matches egui hue table). */
    val HUES = intArrayOf(
        0xFF3A4A7A.toInt(), 0xFF22606C.toInt(), 0xFF783E5C.toInt(), 0xFF3E5C4A.toInt(),
        0xFF705236.toInt(), 0xFF484282.toInt(), 0xFF2C4E64.toInt(), 0xFF683A42.toInt(),
        0xFF36585C.toInt(), 0xFF5C4A76.toInt(), 0xFF42543A.toInt(), 0xFF62423A.toInt(),
    )
}

/** Item kind for a browse row. */
sealed interface BrowseItem {
    data class ChannelRow(val ch: Channel) : BrowseItem
    data class EntryRow(val e: Entry, val isMovie: Boolean) : BrowseItem
    data class GuideHeader(val time: String) : BrowseItem
    data class GuideShow(val ch: Channel, val title: String, val start: String, val progress: Float) : BrowseItem
    data class Message(val text: String) : BrowseItem
}
