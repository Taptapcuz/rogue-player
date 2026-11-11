package com.mytv.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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
 * implayer-style rows: colored logo tile with initials, channel number + name +
 * current program + progress bar + star for channels; poster-ish title rows for
 * movies/series; settings rows with blue toggles.
 */
class BrowseAdapter(
    private val onChannel: (Channel) -> Unit,
    private val onEntry: (Entry, Boolean) -> Unit,
) : ListAdapter<BrowseItem, RecyclerView.ViewHolder>(DIFF) {

    object DIFF : DiffUtil.ItemCallback<BrowseItem>() {
        override fun areItemsTheSame(a: BrowseItem, b: BrowseItem) = a == b
        override fun areContentsTheSame(a: BrowseItem, b: BrowseItem) = a == b
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is BrowseItem.ChannelRow -> 0
        is BrowseItem.EntryRow -> 1
        is BrowseItem.GuideHeader -> 2
        is BrowseItem.GuideShow -> 3
        is BrowseItem.Message -> 4
        is BrowseItem.SectionTitle -> 5
        is BrowseItem.Setting -> 6
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val v = Layout(parent).build(viewType)
        return object : RecyclerView.ViewHolder(v) {}
    }

    var accent: Int = Palette.ACCENTS[0]

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is BrowseItem.ChannelRow -> renderChannel(holder.itemView, item.ch)
            is BrowseItem.EntryRow -> renderEntry(holder.itemView, item.e, item.isMovie)
            is BrowseItem.GuideHeader -> (holder.itemView as TextView).apply {
                text = item.time; setTextColor(accent); textSize = 12f
            }
            is BrowseItem.GuideShow -> renderGuideShow(holder.itemView, item)
            is BrowseItem.Message -> (holder.itemView as TextView).apply {
                text = item.text; setTextColor(Palette.MUTED); textSize = 13f
            }
            is BrowseItem.SectionTitle -> renderSectionTitle(holder.itemView, item)
            is BrowseItem.Setting -> renderSetting(holder.itemView, item)
        }
    }

    // -------------------------------------------------------------- rows ---

    @SuppressLint("SetTextI18n")
    private fun renderChannel(v: View, ch: Channel) {
        val row = v as LinearLayout
        row.removeAllViews()

        // number
        row.addView(TextView(row.context).apply {
            text = "%03d".format(ch.number)
            textSize = 10.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Palette.DIM)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(row, 34), LinearLayout.LayoutParams.MATCH_PARENT))

        // logo tile (colored initials block)
        row.addView(logoTile(row, initialsOf(ch.name), hueFor(ch), 38))

        // middle: name + program
        val mid = LinearLayout(row.context).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(row.context).apply {
            text = ch.name; textSize = 13.5f; setTextColor(Palette.TEXT)
        })
        mid.addView(TextView(row.context).apply {
            text = ch.group.replace('_', ' '); textSize = 11.5f; setTextColor(Palette.MUTED)
        })
        row.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(row,10), 0, dp(row,6), 0)
        })

        // progress bar + star column
        val right = LinearLayout(row.context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val bar = View(row.context).apply {
            setBackgroundColor(Palette.LINE)
        }
        bar.layoutParams = LinearLayout.LayoutParams(dp(row, 64), dp(row, 3)).apply {
            setMargins(0, 0, 0, dp(row, 4))
        }
        right.addView(bar)
        right.addView(TextView(row.context).apply {
            text = "☆"
            textSize = 15f
            setTextColor(Palette.DIM)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(row, 40), dp(row, 20)))

        row.addView(right, LinearLayout.LayoutParams(dp(row, 70), LinearLayout.LayoutParams.MATCH_PARENT))
        row.setOnClickListener { onChannel(ch) }
    }

    private fun renderEntry(v: View, e: Entry, isMovie: Boolean) {
        val row = v as LinearLayout
        row.removeAllViews()
        row.addView(logoTile(row, initialsOf(e.name), hueFor(e), 40))
        val mid = LinearLayout(row.context).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(row.context).apply {
            text = e.name; textSize = 13.5f; setTextColor(Palette.TEXT)
        })
        mid.addView(TextView(row.context).apply {
            text = if (isMovie) "MOVIE   ·   ${e.group}" else "SERIES   ·   ${e.group}"
            textSize = 11f; setTextColor(Palette.MUTED)
        })
        row.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(row,10), 0, dp(row,6), 0)
        })
        row.setOnClickListener { onEntry(e, isMovie) }
    }

    private fun renderGuideShow(v: View, item: BrowseItem.GuideShow) {
        val row = v as LinearLayout
        row.removeAllViews()
        row.addView(logoTile(row, initialsOf(item.ch.name), hueFor(item.ch), 34))
        val mid = LinearLayout(row.context).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(row.context).apply {
            text = item.ch.name; textSize = 13f; setTextColor(Palette.TEXT)
        })
        mid.addView(TextView(row.context).apply {
            text = "${item.title}   ·   ${item.start}"; textSize = 11.5f; setTextColor(Palette.MUTED)
        })
        row.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(row,10), 0, 0, 0)
        })
        row.setOnClickListener { onChannel(item.ch) }
    }

    private fun renderSectionTitle(v: View, item: BrowseItem.SectionTitle) {
        val col = v as LinearLayout
        col.removeAllViews()
        col.addView(TextView(col.context).apply {
            text = item.text; textSize = 22f; setTextColor(Palette.TEXT)
        })
        col.addView(TextView(col.context).apply {
            text = item.sub; textSize = 12.5f; setTextColor(Palette.MUTED)
        })
    }

    @SuppressLint("SetTextI18n")
    private fun renderSetting(v: View, item: BrowseItem.Setting) {
        val row = v as LinearLayout
        row.removeAllViews()
        val left = LinearLayout(row.context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(row.context).apply {
            text = item.label; textSize = 13f; setTextColor(Palette.TEXT)
        })
        if (item.detail != null) left.addView(TextView(row.context).apply {
            text = item.detail; textSize = 11f; setTextColor(Palette.DIM)
        })
        row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (item.on != null) {
            val sw = TextView(row.context).apply {
                text = if (item.on) "●" else "○"
                textSize = 18f
                setTextColor(if (item.on) accent else Palette.DIM)
            }
            row.addView(sw, LinearLayout.LayoutParams(dp(row,48), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
            })
        }
        if (item.isSlider) row.addView(TextView(row.context).apply {
            text = "4 s"; textSize = 12f; setTextColor(Palette.MUTED)
        })
    }

    // ----------------------------------------------------------- helpers ---

    private fun logoTile(parent: View, initials: String, color: Int, size: Int): View {
        val tile = TextView(parent.context).apply {
            text = initials
            textSize = (size * 0.32f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackground(roundedTile(color, size))
        }
        tile.layoutParams = LinearLayout.LayoutParams(dp(parent, size), dp(parent, size)).apply {
            setMargins(0, dp(parent, 6), dp(parent, 10), dp(parent, 6))
        }
        return tile
    }

    private fun roundedTile(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * 0.3f
    }

    private fun hueFor(something: Any): Int {
        val h = (something.hashCode() % Palette.HUES.size).let { if (it < 0) it + Palette.HUES.size else it }
        return Palette.HUES[h]
    }

    private fun initialsOf(name: String): String =
        name.split(" ", "_", "-").filter { it.isNotBlank() }.take(2)
            .map { it.first().uppercaseChar() }.joinToString("")

    private fun dp(v: View, n: Int): Int = (n * v.resources.displayMetrics.density).toInt()

    /** Builds a vertical LinearLayout row padded for the given view type. */
    private class Layout(private val parent: ViewGroup) {
        fun build(viewType: Int): View {
            val lp = if (viewType == 5) { // section title: vertical, wrapping
                androidx.recyclerview.widget.RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val pad = if (viewType == 0 || viewType == 1 || viewType == 3) 12
                      else if (viewType == 5) 16 else 14
            val d = parent.context.resources.displayMetrics.density
            val v = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((pad * d).toInt(), ((if (viewType==5) 18 else 10) * d).toInt(),
                           (pad * d).toInt(), (10 * d).toInt())
                setBackgroundColor(if (viewType % 2 == 0) Palette.BG else Palette.BG)
            }
            v.layoutParams = lp
            return v
        }
    }
}
