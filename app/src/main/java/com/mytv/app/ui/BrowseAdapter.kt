package com.mytv.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mytv.app.data.Channel
import com.mytv.app.data.Entry

/**
 * IMPlayer-clone rows: rounded channel cards (logo tile, name, time, program,
 * blue progress bar along the card bottom) and poster cards (rating badge,
 * title) for movies / shows.
 */
class BrowseAdapter(
    private val onChannel: (Channel) -> Unit,
    private val onEntry: (Entry, Boolean) -> Unit,
) : ListAdapter<BrowseItem, RecyclerView.ViewHolder>(DIFF) {

    object DIFF : DiffUtil.ItemCallback<BrowseItem>() {
        override fun areItemsTheSame(a: BrowseItem, b: BrowseItem) = a.toString() == b.toString()
        override fun areContentsTheSame(a: BrowseItem, b: BrowseItem) = a == b
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is BrowseItem.ChannelCard -> 1
        is BrowseItem.PosterCard -> 2
        is BrowseItem.SectionTitle -> 5
        is BrowseItem.Message -> 4
        else -> 0
    }

    var accent: Int = Palette.ACCENTS[0]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return object : RecyclerView.ViewHolder(build(parent, viewType)) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is BrowseItem.ChannelCard -> renderChannelCard(holder.itemView, item.ch)
            is BrowseItem.PosterCard -> renderPosterCard(holder.itemView, item.e, item.isMovie)
            is BrowseItem.SectionTitle -> renderSectionTitle(holder.itemView, item)
            is BrowseItem.Message -> (holder.itemView as TextView).apply {
                text = item.text; setTextColor(Palette.MUTED); textSize = 13f; gravity = Gravity.CENTER
            }
            is BrowseItem.GuideShow -> (holder.itemView as TextView).apply {
                text = "${item.ch.name} — ${item.title} · ${item.start}"
                setTextColor(Palette.MUTED)
            }
            is BrowseItem.EntryRow -> (holder.itemView as TextView).apply {
                text = item.e.name; setTextColor(Palette.TEXT)
            }
            is BrowseItem.ChannelRow -> (holder.itemView as TextView).apply {
                text = item.ch.name; setTextColor(Palette.TEXT)
            }
        }
    }

    // ------------------------------------------------------------ cards ---

    @SuppressLint("SetTextI18n")
    private fun renderChannelCard(v: View, ch: Channel) {
        val card = v as FrameLayout
        card.removeAllViews()
        card.setBackground(rounded(Palette.CARD, 12))

        val pad = dp(card, 12)
        val inner = LinearLayout(card.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((pad * 0.6f).toInt(), pad, pad, pad)
        }
        card.addView(inner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // logo tile
        inner.addView(logoTile(inner, initialsOf(ch.name), hueFor(ch), 44))

        // right text block
        val right = LinearLayout(card.context).apply { orientation = LinearLayout.VERTICAL }
        right.addView(TextView(card.context).apply {
            text = ch.name
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Palette.TEXT)
            maxLines = 1
        })
        right.addView(TextView(card.context).apply {
            text = ch.group.replace("_", " ")
            textSize = 11f
            setTextColor(Palette.MUTED)
            maxLines = 1
        })
        inner.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(card,10), 0, dp(card,6), 0)
        })

        // blue progress bar across the card bottom
        val bar = View(card.context).apply { setBackgroundColor(Palette.LINE) }
        card.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(card, 3)).apply {
            gravity = Gravity.BOTTOM
        })
        val fill = View(card.context).apply { setBackgroundColor(accent) }
        card.addView(fill, FrameLayout.LayoutParams((FrameLayout.LayoutParams.MATCH_PARENT * 0.35f).toInt(), dp(card, 3)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        })

        card.setOnClickListener { onChannel(ch) }
    }

    @SuppressLint("SetTextI18n")
    private fun renderPosterCard(v: View, e: Entry, isMovie: Boolean) {
        val card = v as FrameLayout
        card.removeAllViews()
        card.setBackground(rounded(hueFor(e), 10))

        // rating badge top-left
        card.addView(TextView(card.context).apply {
            text = e.group.ifBlank { if (isMovie) "MOVIE" else "SERIES" }.take(2)
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackground(rounded(Color.argb(200, 0, 0, 0), 6))
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(dp(card, 40), dp(card, 22)).apply {
            setMargins(dp(card,8), dp(card,8), 0, 0)
        })

        // title bottom
        card.addView(TextView(card.context).apply {
            text = e.name
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.BOTTOM
            maxLines = 2
            setPadding(dp(card,8), 0, dp(card,8), dp(card,8))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.BOTTOM
        })
        card.setOnClickListener { onEntry(e, isMovie) }
    }

    private fun renderSectionTitle(v: View, item: BrowseItem.SectionTitle) {
        val row = v as LinearLayout
        row.removeAllViews()
        row.orientation = LinearLayout.VERTICAL
        row.addView(TextView(row.context).apply {
            text = item.text
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Palette.TEXT)
        })
        row.addView(TextView(row.context).apply {
            text = item.sub
            textSize = 12f
            setTextColor(Palette.MUTED)
        })
        // blue underline
        val line = View(row.context).apply { setBackgroundColor(accent) }
        row.addView(line, LinearLayout.LayoutParams(dp(row, 44), dp(row, 2)).apply { topMargin = dp(row, 4) })
    }

    // ----------------------------------------------------------- helpers ---

    private fun build(parent: ViewGroup, viewType: Int): View {
        val d = parent.context.resources.displayMetrics.density
        return when (viewType) {
            // channel card
            1 -> FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(parent, 76))
            }
            // poster card: 3 per row ~ 110w x 150h
            2 -> FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(dp(parent, 110), dp(parent, 150))
            }
            // section title
            5 -> LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (18 * d).toInt(), 0, (10 * d).toInt())
            }
            else -> TextView(parent.context).apply {
                setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            }
        }
    }

    private fun logoTile(parent: View, initials: String, color: Int, size: Int): View {
        val tile = TextView(parent.context).apply {
            text = initials
            textSize = size * 0.3f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackground(rounded(color, 8))
        }
        tile.layoutParams = LinearLayout.LayoutParams(dp(parent, size), dp(parent, size))
        return tile
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * 1.2f
    }

    private fun hueFor(something: Any): Int {
        val h = (something.hashCode() % Palette.HUES.size).let { if (it < 0) it + Palette.HUES.size else it }
        return Palette.HUES[h]
    }

    private fun initialsOf(name: String): String =
        name.split(" ", "_", "-").filter { it.isNotBlank() }.take(2)
            .map { it.first().uppercaseChar() }.joinToString("")

    private fun dp(v: View, n: Int): Int = (n * v.resources.displayMetrics.density).toInt()
}
