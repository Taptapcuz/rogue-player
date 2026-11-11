package com.mytv.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mytv.app.data.Channel
import com.mytv.app.data.Entry

/**
 * RecyclerView adapter rendering the implayer browse rows (channels, entries,
 * guide rows) in the MYTV dark palette.
 */
class BrowseAdapter(
    private val onChannel: (Channel) -> Unit,
    private val onEntry: (Entry, Boolean) -> Unit,
) : ListAdapter<BrowseItem, RecyclerView.ViewHolder>(DIFF) {

    object DIFF : DiffUtil.ItemCallback<BrowseItem>() {
        override fun areItemsTheSame(a: BrowseItem, b: BrowseItem) =
            a.toString() == b.toString()
        override fun areContentsTheSame(a: BrowseItem, b: BrowseItem) = a == b
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is BrowseItem.ChannelRow -> 0
        is BrowseItem.EntryRow -> 1
        is BrowseItem.GuideHeader -> 2
        is BrowseItem.GuideShow -> 3
        is BrowseItem.Message -> 4
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val tv = TextView(parent.context).apply {
            setPadding(dp(parent, 16), dp(parent, 14), dp(parent, 16), dp(parent, 14))
            textSize = 14f
        }
        return object : RecyclerView.ViewHolder(tv) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val tv = holder.itemView as TextView
        when (val item = getItem(position)) {
            is BrowseItem.ChannelRow -> {
                val ch = item.ch
                tv.text = "#${ch.number.toString().padStart(3, '0')}   ${ch.name}   ·   ${ch.group}"
                tv.setTextColor(Palette.TEXT)
                holder.itemView.setOnClickListener { onChannel(ch) }
            }
            is BrowseItem.EntryRow -> {
                val e = item.e
                tv.text = (if (item.isMovie) "🎬 " else "📺 ") + e.name + "   ·   " + e.group
                tv.setTextColor(Palette.TEXT)
                holder.itemView.setOnClickListener { onEntry(e, item.isMovie) }
            }
            is BrowseItem.GuideHeader -> {
                tv.text = item.time
                tv.setTextColor(Palette.ACCENTS[0])
                tv.textSize = 12f
            }
            is BrowseItem.GuideShow -> {
                tv.text = "▸ ${item.ch.name} — ${item.title}   ·   ${item.start}"
                tv.setTextColor(Palette.MUTED)
                holder.itemView.setOnClickListener { onChannel(item.ch) }
            }
            is BrowseItem.Message -> {
                tv.text = item.text
                tv.setTextColor(Palette.MUTED)
                tv.textSize = 13f
            }
        }
        (tv.parent as? ViewGroup)?.childCount // no-op keeps reference
    }

    private fun dp(v: View, n: Int): Int = (n * v.resources.displayMetrics.density).toInt()
}
