package com.rogueplayer.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
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
import com.rogueplayer.data.Channel
import com.rogueplayer.data.Entry
import com.rogueplayer.data.EpgProgram
import com.rogueplayer.data.Episode

/**
 * Rows and tiles for the browse screens:
 *  - rounded live-TV cards (logo tile, name, EPG now/next, blue progress bar)
 *  - Plex-style poster tiles for movies / shows (grid), plus a compact poster
 *    row for search results
 *  - section headers with a bold count pill
 *  - episode rows in the series screen
 */
class BrowseAdapter(
    private val onChannel: (Channel) -> Unit,
    private val onEntry: (Entry, Boolean) -> Unit,
    private val compactPosters: Boolean = false,
    private val onEpisode: (Episode, String) -> Unit = { _, _ -> },
    private val onChannelLong: (Channel) -> Unit = {},
    private val pillCards: Boolean = false,
    private val onGroup: (String) -> Unit = {},
) : ListAdapter<BrowseItem, RecyclerView.ViewHolder>(DIFF) {

    /** Favourited channel ids — starred rows and long-press handled by the host. */
    var favoriteIds: Set<String> = emptySet()

    object DIFF : DiffUtil.ItemCallback<BrowseItem>() {
        override fun areItemsTheSame(a: BrowseItem, b: BrowseItem): Boolean = when {
            a is BrowseItem.ChannelCard && b is BrowseItem.ChannelCard -> a.ch.id == b.ch.id
            a is BrowseItem.PosterCard && b is BrowseItem.PosterCard ->
                a.e.id == b.e.id && a.isMovie == b.isMovie
            a is BrowseItem.SectionTitle && b is BrowseItem.SectionTitle -> a.text == b.text
            a is BrowseItem.EpisodeItem && b is BrowseItem.EpisodeItem -> a.ep.id == b.ep.id
            else -> a::class == b::class && a.toString() == b.toString()
        }

        override fun areContentsTheSame(a: BrowseItem, b: BrowseItem): Boolean = a == b
    }

    var accent: Int = Palette.ACCENTS[0]

    override fun getItemViewType(position: Int): Int = when (val item = getItem(position)) {
        is BrowseItem.ChannelCard -> TYPE_CHANNEL
        is BrowseItem.PosterCard -> if (compactPosters) TYPE_POSTER_ROW else TYPE_POSTER_TILE
        is BrowseItem.SectionTitle -> TYPE_SECTION
        is BrowseItem.EpisodeItem -> TYPE_EPISODE
        is BrowseItem.GroupRow -> TYPE_GROUP
        is BrowseItem.Message -> TYPE_MESSAGE
        else -> TYPE_MESSAGE
    }

    /** Cached EPG now/next per channel id, populated by the activity. */
    private val nowPlaying = mutableMapOf<String, EpgProgram?>()
    private val nextPlaying = mutableMapOf<String, EpgProgram?>()

    /** Store refreshed EPG and redraw that channel's row. */
    fun updateEpg(channel: Channel, now: EpgProgram?, next: EpgProgram?) {
        nowPlaying[channel.id] = now
        nextPlaying[channel.id] = next
        val pos = currentList.indexOfFirst { (it as? BrowseItem.ChannelCard)?.ch?.id == channel.id }
        if (pos >= 0) notifyItemChanged(pos)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        object : RecyclerView.ViewHolder(build(parent, viewType)) {}

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is BrowseItem.ChannelCard -> renderChannelCard(holder.itemView, item.ch)
            is BrowseItem.PosterCard -> {
                (holder.itemView as PosterCell).bind(item.e, hueFor(item.e))
                holder.itemView.setOnClickListener { onEntry(item.e, item.isMovie) }
            }
            is BrowseItem.SectionTitle -> renderSectionTitle(holder.itemView, item)
            is BrowseItem.EpisodeItem -> renderEpisodeRow(holder.itemView, item)
            is BrowseItem.GroupRow -> renderGroupRow(holder.itemView, item.group, item.count)
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
        if (pillCards) { renderChannelPill(v, ch); return }
        val card = v as FrameLayout
        card.removeAllViews()
        card.setBackground(rounded(card, Palette.CARD, 12))

        val inner = LinearLayout(card.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(card, 10), dp(card, 10), dp(card, 12), dp(card, 10))
        }
        card.addView(inner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // logo tile
        inner.addView(logoTile(inner, initialsOf(ch.name), hueFor(ch), 44))

        // right text block
        val now = nowPlaying[ch.id]
        val next = nextPlaying[ch.id]
        val right = LinearLayout(card.context).apply { orientation = LinearLayout.VERTICAL }
        right.addView(TextView(card.context).apply {
            text = ch.name
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Palette.TEXT)
            maxLines = 1
        })
        right.addView(TextView(card.context).apply {
            text = ch.group.replace("_", " ")
            textSize = 11f
            setTextColor(Palette.MUTED)
            maxLines = 1
        })
        // EPG "now" line: Title · HH:mm - HH:mm
        if (now != null) {
            right.addView(TextView(card.context).apply {
                text = "${now.title} · ${time(now.start)} - ${time(now.end)}"
                textSize = 11.5f
                setTextColor(Palette.DIM)
                maxLines = 1
            })
        }
        // EPG "next" line (muted, smaller)
        if (next != null) {
            right.addView(TextView(card.context).apply {
                text = "Next: ${next.title}"
                textSize = 10.5f
                setTextColor(0xFF6C7294.toInt())
                maxLines = 1
            })
        }
        inner.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(card, 10), 0, dp(card, 6), 0)
        })

        // blue progress bar across the card bottom (EPG-aware when available)
        val bar = View(card.context).apply { setBackgroundColor(Palette.LINE) }
        card.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(card, 3)).apply {
            gravity = Gravity.BOTTOM
        })
        val fillW = if (now != null) {
            (FrameLayout.LayoutParams.MATCH_PARENT * progressOf(now).coerceIn(0f, 1f)).toInt()
        } else {
            (FrameLayout.LayoutParams.MATCH_PARENT * 0.35f).toInt()
        }
        val fill = View(card.context).apply { setBackgroundColor(accent) }
        card.addView(fill, FrameLayout.LayoutParams(fillW, dp(card, 3)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        })

        // A favourited channel gets a star in the card's top-right corner.
        if (ch.id in favoriteIds) {
            card.addView(TextView(card.context).apply {
                text = "★"
                textSize = 13f
                setTextColor(accent)
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(card, 5), dp(card, 9), 0)
            })
        }

        card.setOnClickListener { onChannel(ch) }
        card.setOnLongClickListener { onChannelLong(ch); true }
    }

    /** Pill-capsule variant of [renderChannelCard]: the SAME channel data,
     *  schedule, progress and favourites star — only the container is a capsule
     *  (fully-rounded, circular logo, pill-shaped progress fill) instead of a
     *  rounded rectangle. Interactions are identical: tap plays, long-press
     *  favourites. The pill rows are a bit taller so the guide reads inside. */
    @SuppressLint("SetTextI18n")
    private fun renderChannelPill(v: View, ch: Channel) {
        val card = v as FrameLayout
        card.removeAllViews()
        // capsule container; a thin accent progress bar sits under the guide text.
        card.setBackground(rounded(card, Palette.CARD, 42))

        val col = LinearLayout(card.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(card, 8), dp(card, 10), dp(card, 14), dp(card, 10))
        }
        card.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val row = LinearLayout(card.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(logoTile(col, initialsOf(ch.name), hueFor(ch), 48, circular = true))

        val now = nowPlaying[ch.id]
        val next = nextPlaying[ch.id]
        val right = LinearLayout(card.context).apply { orientation = LinearLayout.VERTICAL }
        right.addView(TextView(card.context).apply {
            text = ch.name
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Palette.TEXT)
            maxLines = 1
        })
        right.addView(TextView(card.context).apply {
            text = ch.group.replace("_", " ")
            textSize = 10.5f
            setTextColor(Palette.MUTED)
            maxLines = 1
        })
        val progTxt = when {
            now != null && next != null -> "${time(now.start)} - ${time(now.end)} · ${now.title}"
            now != null -> "${time(now.start)} - ${time(now.end)}"
            else -> ch.group.replace("_", " ")
        }
        // Karaoke-style fill via a custom view: base text is always drawn white,
        // then the SAME text is drawn in accent and clipped with a canvas rect to
        // the programme-progress fraction. Deterministic (no layout-timing games).
        val guideHost = KaraokeView(card.context).apply {
            text = progTxt
            textSizePx = 11.5f * card.resources.displayMetrics.density
            baseColor = Palette.TEXT
            fillColor = accent
            fraction = (if (now != null) progressOf(now).coerceIn(0f, 1f) else 0f)
        }
        right.addView(guideHost, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(card, 18)).apply {
            topMargin = dp(card, 2)
        })

        row.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(dp(card, 12), 0, dp(card, 6), 0) })
        col.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // favourite star (top-right), same as lock card
        if (ch.id in favoriteIds) {
            card.addView(TextView(card.context).apply {
                text = "★"
                textSize = 13f
                setTextColor(accent)
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(card, 4), dp(card, 12), 0)
            })
        }

        card.setOnClickListener { onChannel(ch) }
        card.setOnLongClickListener { onChannelLong(ch); true }
    }

    /** Draws a rounded capsule and fills it with [fill] from the left by [fraction]
     *  over a [base] underlay, both clipped to the pill shape. When [fraction] is
     *  1.0 the whole pill is [fill]; at 0 it is [base]. */
    private class PillFillDrawable(
        private val base: Int,
        private val fill: Int,
        private val fraction: Float,
        private val radiusPx: Float,
    ) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val r = android.graphics.RectF()

        override fun draw(canvas: android.graphics.Canvas) {
            val f = fraction.coerceIn(0f, 1f)
            r.set(bounds)
            // 1) base underlay, full capsule
            paint.color = base
            canvas.drawRoundRect(r, radiusPx, radiusPx, paint)
            if (f <= 0f) return
            // 2) accent fill grown from the left, clipped to the capsule
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            canvas.save()
            canvas.clipPath(capsulePath())
            canvas.drawRect(0f, 0f, w * f, h, paint.apply { color = fill })
            canvas.restore()
        }
        private fun capsulePath(): android.graphics.Path {
            val rect = android.graphics.RectF(bounds)
            return android.graphics.Path().apply {
                setFillType(android.graphics.Path.FillType.EVEN_ODD)
                addRoundRect(rect, radiusPx, radiusPx, android.graphics.Path.Direction.CW)
            }
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        override fun getOutline(outline: android.graphics.Outline) {
            outline.setRoundRect(bounds, radiusPx)
        }
    }

    private fun density(v: View): Float = v.resources.displayMetrics.density

    /** Draws guide text twice: base colour full-width, then [fillColor] clipped to
     *  [fraction] of the width — the "text fills up as the programme plays" look. */
    private class KaraokeView(context: android.content.Context) : View(context) {
        var text: String = ""
        var textSizePx: Float = 0f
        var baseColor: Int = android.graphics.Color.WHITE
        var fillColor: Int = android.graphics.Color.WHITE
        var fraction: Float = 0f
        private val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val ellipsis = android.text.TextUtils.TruncateAt.END

        override fun onDraw(canvas: android.graphics.Canvas) {
            if (text.isEmpty()) return
            paint.textSize = textSizePx
            paint.typeface = android.graphics.Typeface.DEFAULT
            val avail = width
            val f = fraction.coerceIn(0f, 1f)
            // base: always fully readable
            paint.color = baseColor
            val shown = android.text.TextUtils.ellipsize(text, paint, avail.toFloat(), ellipsis).toString()
            val baseline = paint.textSize + paint.descent() / 2f
            canvas.save()
            canvas.drawText(shown, 0f, baseline, paint)
            // accent fill clipped to progress fraction (the words "fill up")
            if (f > 0f) {
                paint.color = fillColor
                canvas.clipRect(0f, 0f, avail * f, height.toFloat())
                canvas.drawText(shown, 0f, baseline, paint)
            }
            canvas.restore()
            // thin full-width progress underline: keeps advancing after the text ends
            val lineH = 2f * resources.displayMetrics.density
            val lineY = height - lineH
            linePaint.color = withAlphaStatic(baseColor, 0x33)
            canvas.drawRect(0f, lineY, avail.toFloat(), height.toFloat(), linePaint)
            if (f > 0f) {
                linePaint.color = fillColor
                canvas.drawRect(0f, lineY, avail * f, height.toFloat(), linePaint)
            }
        }

        private fun withAlphaStatic(color: Int, alpha: Int): Int =
            android.graphics.Color.argb(alpha, android.graphics.Color.red(color),
                android.graphics.Color.green(color), android.graphics.Color.blue(color))
    }

    /** Tappable category row (Sports tab landing): group name + count + chevron. */
    @SuppressLint("SetTextI18n")
    private fun renderGroupRow(v: View, group: String, count: Int) {
        val row = v as FrameLayout
        row.removeAllViews()
        row.setBackground(rounded(row, Palette.CARD, if (pillCards) 30 else 12))

        val inner = LinearLayout(row.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(row, 16), dp(row, 10), dp(row, 16), dp(row, 10))
        }
        row.addView(inner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        inner.addView(TextView(row.context).apply {
            text = group.replace("_", " ")
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Palette.TEXT)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        inner.addView(TextView(row.context).apply {
            text = "$count"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = rounded(row, withAlpha(accent, 0xE0), 12)
            setPadding(dp(row, 10), dp(row, 3), dp(row, 10), dp(row, 3))
            gravity = Gravity.CENTER
        })

        inner.addView(TextView(row.context).apply {
            text = "›"
            textSize = 20f
            setTextColor(Palette.MUTED)
            setPadding(dp(row, 10), 0, 0, 0)
        })

        row.setOnClickListener { onGroup(group) }
    }

    /** What fraction of [p] has elapsed right now (0..1). */
    private fun progressOf(p: EpgProgram): Float =
        (System.currentTimeMillis() - p.start).toFloat() / (p.end - p.start).toFloat()

    private fun time(epochMs: Long): String = timestampFormat.format(epochMs)

    private val timestampFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)

    // ------------------------------------------------------- episode row ---

    @SuppressLint("SetTextI18n")
    private fun renderEpisodeRow(v: View, item: BrowseItem.EpisodeItem) {
        val row = v as FrameLayout
        row.removeAllViews()
        row.setBackground(rounded(row, Palette.CARD, 10))

        val inner = LinearLayout(row.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(row, 12), dp(row, 10), dp(row, 12), dp(row, 10))
        }
        row.addView(inner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val num = TextView(row.context).apply {
            text = "S%02dE%02d".format(item.ep.season, item.ep.number)
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(row, withAlpha(accent, 0xCC), 7)
            setPadding(dp(row, 7), dp(row, 4), dp(row, 7), dp(row, 4))
        }
        inner.addView(num)

        val title = TextView(row.context).apply {
            text = item.ep.title.ifBlank { "Episode ${item.ep.number}" }
            textSize = 14f
            setTextColor(Palette.TEXT)
            maxLines = 1
            setPadding(dp(row, 11), 0, dp(row, 6), 0)
        }
        inner.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        inner.addView(TextView(row.context).apply {
            text = "▶"
            textSize = 15f
            setTextColor(Palette.MUTED)
        })

        row.setOnClickListener { onEpisode(item.ep, item.seriesTitle) }
    }

    // --------------------------------------------------- section header ---

    /**
     * Bold title + blue underline on the left, item count in a filled accent
     * pill on the right. The count is the point, so it gets its own chip
     * instead of a 12sp muted line.
     */
    private fun renderSectionTitle(v: View, item: BrowseItem.SectionTitle) {
        val row = v as LinearLayout
        row.removeAllViews()
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.BOTTOM

        val left = LinearLayout(row.context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(row.context).apply {
            text = item.text
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Palette.TEXT)
        })
        val line = View(row.context).apply { setBackgroundColor(accent) }
        left.addView(line, LinearLayout.LayoutParams(dp(row, 34), dp(row, 3)).apply {
            topMargin = dp(row, 5)
        })
        row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (item.sub.isNotBlank()) {
            val pill = TextView(row.context).apply {
                text = item.sub
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = rounded(row, withAlpha(accent, 0xE0), 14)
                setPadding(dp(row, 12), dp(row, 5), dp(row, 12), dp(row, 5))
                gravity = Gravity.CENTER
            }
            row.addView(pill, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(row, 3) })
        }
    }

    // ----------------------------------------------------------- helpers ---

    private fun build(parent: ViewGroup, viewType: Int): View {
        val d = parent.context.resources.displayMetrics.density
        return when (viewType) {
            TYPE_CHANNEL -> FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(parent, if (pillCards) 84 else 74)).apply {
                    if (pillCards) { topMargin = dp(parent, 4); bottomMargin = dp(parent, 4) }
                }
            }
            TYPE_POSTER_TILE, TYPE_POSTER_ROW ->
                PosterCell(parent.context, compact = viewType == TYPE_POSTER_ROW).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            TYPE_EPISODE -> FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(parent, 58))
            }
            TYPE_GROUP -> FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(parent, 60))
            }
            TYPE_SECTION -> LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(parent, 4), (18 * d).toInt(), dp(parent, 4), (12 * d).toInt())
                // Without explicit params RecyclerView gives the row WRAP_CONTENT,
                // so the weighted title column collapsed and "Channels" wrapped
                // one letter-group per line in the search results.
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            else -> TextView(parent.context).apply {
                setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun logoTile(parent: View, initials: String, color: Int, size: Int, circular: Boolean = false): View {
        val tile = TextView(parent.context).apply {
            text = initials
            textSize = size * 0.3f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackground(rounded(parent, color, if (circular) size / 2 else 8))
        }
        tile.layoutParams = LinearLayout.LayoutParams(dp(parent, size), dp(parent, size))
        return tile
    }

    private fun rounded(v: View, color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * v.resources.displayMetrics.density
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun hueFor(something: Any): Int {
        val h = (something.hashCode() % Palette.HUES.size).let { if (it < 0) it + Palette.HUES.size else it }
        return Palette.HUES[h]
    }

    private fun initialsOf(name: String): String =
        name.split(" ", "_", "-").filter { it.isNotBlank() }.take(2)
            .map { it.first().uppercaseChar() }.joinToString("")

    private fun dp(v: View, n: Int): Int = (n * v.resources.displayMetrics.density).toInt()

    companion object {
        const val TYPE_CHANNEL = 1
        const val TYPE_POSTER_TILE = 2
        const val TYPE_MESSAGE = 4
        const val TYPE_SECTION = 5
        const val TYPE_POSTER_ROW = 6
        const val TYPE_EPISODE = 7
        const val TYPE_GROUP = 8
    }
}
