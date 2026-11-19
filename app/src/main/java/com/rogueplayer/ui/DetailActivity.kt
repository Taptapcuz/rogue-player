package com.rogueplayer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.rogueplayer.MyApplication
import com.rogueplayer.data.Entry
import com.rogueplayer.data.Episode
import com.rogueplayer.data.MediaDetail
import com.rogueplayer.player.PlaybackService
import kotlinx.coroutines.launch

/**
 * Detail page for a movie or a TV series — what a poster tap opens instead of
 * jumping straight into the stream.
 *
 * Shows the provider's metadata (backdrop, poster, plot, year, runtime, rating,
 * genre, cast, director), a Trailer button when the panel sent a
 * `youtube_trailer`, and a Play button. For a series the same screen carries the
 * season chips and episode list, so the episode picker lives here instead of
 * being spliced into the browse list.
 */
class DetailActivity : AppCompatActivity() {

    private val app get() = application as MyApplication

    private lateinit var entry: Entry
    private var isMovie = true

    private var detail: MediaDetail? = null
    private var selectedSeason = 1

    private lateinit var backdrop: ImageView
    private lateinit var poster: ImageView
    private lateinit var titleText: TextView
    private lateinit var metaText: TextView
    private lateinit var playBtn: TextView
    private lateinit var trailerBtn: TextView
    private lateinit var genreRow: HorizontalScrollView
    private lateinit var sectionLabel: TextView
    private lateinit var seasonRow: HorizontalScrollView
    private lateinit var seasonChips: LinearLayout
    private lateinit var episodeList: LinearLayout
    private lateinit var plotText: TextView
    private lateinit var creditsText: TextView
    private lateinit var statusText: TextView
    private lateinit var loading: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entry = Entry(
            id = intent.getStringExtra(EXTRA_ID).orEmpty(),
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            group = intent.getStringExtra(EXTRA_GROUP).orEmpty(),
            url = intent.getStringExtra(EXTRA_URL).orEmpty(),
            logo = intent.getStringExtra(EXTRA_LOGO)?.takeIf { it.isNotBlank() },
            description = intent.getStringExtra(EXTRA_PLOT)?.takeIf { it.isNotBlank() },
        )
        isMovie = intent.getBooleanExtra(EXTRA_IS_MOVIE, true)

        window.statusBarColor = Palette.BG
        setContentView(buildUi())
        // Paint what we already know from the list row before the request lands, so
        // the screen is never blank while the provider answers.
        render(MediaDetail(entry.id, entry.name, plot = entry.description, poster = entry.logo))
        loadDetail()
    }

    // ------------------------------------------------------------- UI ---

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Hero: backdrop with a bottom scrim so the poster + title stay legible.
        val hero = FrameLayout(this)
        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Palette.PANEL)
        }
        hero.addView(backdrop, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        hero.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0xB30B0B10.toInt(), Palette.BG),
            )
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Palette.CARD, 10)
            clipToOutline = true
        }
        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }
        heroRow.addView(poster, LinearLayout.LayoutParams(dp(102), dp(153)).apply {
            rightMargin = dp(14); bottomMargin = dp(2)
        })
        titleText = TextView(this).apply {
            setTextColor(Palette.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        metaText = TextView(this).apply {
            setTextColor(Palette.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(6), 0, 0)
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }
        titleCol.addView(titleText)
        titleCol.addView(metaText)
        heroRow.addView(titleCol, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(heroRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(230), Gravity.BOTTOM))
        col.addView(hero, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))

        // Play / Trailer.
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(4))
        }
        playBtn = actionButton("▶  Play", filled = true).apply { setOnClickListener { play() } }
        trailerBtn = actionButton("▶  Trailer", filled = false).apply {
            visibility = View.GONE
            setOnClickListener { openTrailer() }
        }
        actions.addView(playBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(trailerBtn, LinearLayout.LayoutParams(dp(150), dp(48)).apply {
            leftMargin = dp(10)
        })
        col.addView(actions)

        genreRow = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(16), dp(10), dp(16), 0)
            visibility = View.GONE
        }
        genreRow.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL })
        col.addView(genreRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        plotText = TextView(this).apply {
            setTextColor(Palette.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(16), dp(14), dp(16), 0)
        }
        col.addView(plotText, wrap())

        creditsText = TextView(this).apply {
            setTextColor(Palette.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        col.addView(creditsText, wrap())

        // Series: seasons + episodes.
        sectionLabel = TextView(this).apply {
            setTextColor(Palette.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(22), dp(16), dp(6))
            visibility = View.GONE
        }
        col.addView(sectionLabel, wrap())

        seasonRow = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(16), dp(4), dp(16), dp(6))
            visibility = View.GONE
        }
        seasonChips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        seasonRow.addView(seasonChips)
        col.addView(seasonRow, wrap())

        episodeList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(episodeList, wrap())

        statusText = TextView(this).apply {
            setTextColor(Palette.DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(dp(16), dp(16), dp(16), dp(30))
        }
        col.addView(statusText, wrap())

        scroll.addView(col)
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Floating back arrow, above the hero.
        val back = TextView(this).apply {
            text = "←"
            setTextColor(Palette.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            background = rounded(0x99000000.toInt(), 22)
            contentDescription = "Back"
            setOnClickListener { finish() }
        }
        root.addView(back, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(12)
            topMargin = dp(10)
        })

        loading = ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Palette.ACCENTS[0]) }
        root.addView(loading, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))

        // Keep the hero clear of the status bar; the back arrow moves down with it.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            (back.layoutParams as FrameLayout.LayoutParams).topMargin = top + dp(10)
            scroll.setPadding(0, top, 0, 0)
            insets
        }
        return root
    }

    private fun wrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun actionButton(label: String, filled: Boolean): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        if (filled) {
            setTextColor(Color.WHITE)
            background = rounded(Palette.ACCENTS[0], 12)
        } else {
            setTextColor(Palette.TEXT)
            background = rounded(Palette.CARD_HI, 12)
        }
        isClickable = true
        isFocusable = true
    }

    // ---------------------------------------------------------- loading ---

    private fun loadDetail() {
        lifecycleScope.launch {
            val loaded = app.repository.detailFor(entry, isMovie)
            detail = loaded
            selectedSeason = loaded.seasons.firstOrNull() ?: 1
            loading.visibility = View.GONE
            render(loaded)
        }
    }

    private fun render(d: MediaDetail) {
        val displayTitle = Titles.clean(d.title.ifBlank { entry.name })
        titleText.text = displayTitle
        metaText.text = metaLine(d)
        playBtn.text = when {
            isMovie -> "▶  Play"
            d.episodes.isNotEmpty() -> "▶  Play S$selectedSeason E${d.episodesOf(selectedSeason).firstOrNull()?.number ?: 1}"
            else -> "▶  Play"
        }

        val heroArt = d.backdrop ?: d.poster
        if (heroArt != null) {
            backdrop.load(heroArt) { crossfade(true) }
        } else {
            backdrop.setBackgroundColor(Palette.HUES[Math.floorMod(displayTitle.hashCode(), Palette.HUES.size)])
        }
        if (d.poster != null) {
            poster.visibility = View.VISIBLE
            poster.load(d.poster) { crossfade(true) }
        } else {
            // No art: draw the title's initial on a hue tile rather than an empty box.
            poster.visibility = View.VISIBLE
            poster.setBackgroundColor(Palette.HUES[Math.floorMod(displayTitle.hashCode(), Palette.HUES.size)])
            poster.load(null)
            poster.setImageDrawable(null)
        }

        trailerBtn.visibility = if (d.trailer != null) View.VISIBLE else View.GONE

        renderGenres(d.genre)

        plotText.text = d.plot?.takeIf { it.isNotBlank() } ?: ""
        plotText.visibility = if (plotText.text.isNullOrBlank()) View.GONE else View.VISIBLE

        val credits = buildList {
            d.director?.let { add("Director   $it") }
            d.cast?.let { add("Cast   $it") }
            d.country?.let { add("Country   $it") }
        }
        creditsText.text = credits.joinToString("\n")
        creditsText.visibility = if (credits.isEmpty()) View.GONE else View.VISIBLE

        if (isMovie) {
            sectionLabel.visibility = View.GONE
            seasonRow.visibility = View.GONE
            episodeList.visibility = View.GONE
            statusText.text = if (d.plot == null && d.genre == null && d.rating == null) {
                "This provider sent no extra details for this title — it will still play."
            } else ""
        } else {
            renderEpisodes(d)
        }
    }

    /** "2021 · 2h 14m · ★ 7.4 · 12 episodes" — only the parts we actually have. */
    private fun metaLine(d: MediaDetail): String {
        val bits = mutableListOf<String>()
        d.year?.let { bits.add(it) }
        d.duration?.let { bits.add(it) }
        d.rating?.let { r ->
            val n = r.toDoubleOrNull()
            bits.add("★ " + if (n != null && n > 10) String.format("%.1f", n / 2) else r)
        }
        if (!isMovie && d.episodes.isNotEmpty()) {
            bits.add("${d.episodes.size} episodes")
        }
        return bits.joinToString("  ·  ")
    }

    private fun renderGenres(genre: String?) {
        val parts = genre?.split(',', '/')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        (genreRow.getChildAt(0) as LinearLayout).removeAllViews()
        if (parts.isEmpty()) {
            genreRow.visibility = View.GONE
            return
        }
        genreRow.visibility = View.VISIBLE
        parts.forEach { g ->
            (genreRow.getChildAt(0) as LinearLayout).addView(TextView(this).apply {
                text = g
                setTextColor(Palette.MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = rounded(Palette.CARD, 9)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(7) })
        }
    }

    private fun renderEpisodes(d: MediaDetail) {
        sectionLabel.visibility = View.VISIBLE
        sectionLabel.text = "Episodes"
        val seasons = d.seasons
        if (seasons.isEmpty()) {
            seasonRow.visibility = View.GONE
            episodeList.removeAllViews()
            statusText.text = "The provider returned no episode list for this show."
            return
        }
        statusText.text = ""

        // Season chips.
        seasonChips.removeAllViews()
        seasonRow.visibility = if (seasons.size > 1) View.VISIBLE else View.GONE
        seasons.forEach { season ->
            val active = season == selectedSeason
            seasonChips.addView(TextView(this).apply {
                text = "Season $season"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(if (active) Color.WHITE else Palette.MUTED)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = rounded(if (active) Palette.ACCENTS[0] else Palette.CARD, 16)
                setOnClickListener {
                    selectedSeason = season
                    render(d)
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) })
        }

        episodeList.removeAllViews()
        d.episodesOf(selectedSeason).forEach { ep -> episodeList.addView(episodeRow(ep)) }
        playBtn.text = "▶  Play S$selectedSeason E" +
            (d.episodesOf(selectedSeason).firstOrNull()?.number ?: 1)
    }

    private fun episodeRow(ep: Episode): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { playEpisode(ep) }
        }
        val idx = TextView(this).apply {
            text = "E${ep.number}"
            setTextColor(Palette.ACCENTS[0])
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = dp(38)
        }
        val name = TextView(this).apply {
            text = ep.title.ifBlank { "Episode ${ep.number}" }
            setTextColor(Palette.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
        }
        val dur = TextView(this).apply {
            text = ep.durationSec?.takeIf { it > 0 }?.let { s ->
                if (s >= 3600) "${s / 3600}h ${(s % 3600) / 60}m" else "${s / 60}m"
            } ?: ""
            setTextColor(Palette.DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }
        row.addView(idx, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(dur)
        return row
    }

    // --------------------------------------------------------- actions ---

    private fun play() {
        if (isMovie) {
            startPlayback(entry.url, Titles.clean(detail?.title ?: entry.name))
            return
        }
        val eps = detail?.episodesOf(selectedSeason).orEmpty()
        val first = eps.firstOrNull()
        if (first == null) {
            // No episode list: the series entry has no playable stream of its own.
            if (entry.url.isNotBlank()) startPlayback(entry.url, Titles.clean(entry.name))
            else toast("This show has no playable episodes on this provider")
            return
        }
        playEpisode(first)
    }

    private fun playEpisode(ep: Episode) {
        val show = Titles.clean(detail?.title?.ifBlank { entry.name } ?: entry.name)
        val label = "$show · S${ep.season}E${ep.number}" +
            (ep.title.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        startPlayback(ep.url, label)
    }

    private fun startPlayback(url: String, title: String) {
        if (url.isBlank()) { toast("No stream URL"); return }
        startService(
            Intent(this, PlaybackService::class.java)
                .setAction(PlaybackService.ACTION_PLAY)
                .putExtra(PlaybackService.EXTRA_URL, url)
                .putExtra(PlaybackService.EXTRA_TITLE, title)
                .putExtra(PlaybackService.EXTRA_POSITION, 0L)
        )
        startActivity(PlayerActivity.intent(this, url, title))
    }

    /**
     * Trailers open in the YouTube app when it is installed and in the browser
     * otherwise — no embedded player, so a missing/geo-blocked video can't wedge
     * this screen.
     */
    private fun openTrailer() {
        val id = detail?.trailer ?: return
        val web = Uri.parse("https://www.youtube.com/watch?v=$id")
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id"))
        try {
            startActivity(appIntent)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, web))
            } catch (e2: ActivityNotFoundException) {
                toast("No app to open the trailer")
            }
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_GROUP = "group"
        private const val EXTRA_URL = "url"
        private const val EXTRA_LOGO = "logo"
        private const val EXTRA_PLOT = "plot"
        private const val EXTRA_IS_MOVIE = "is_movie"

        fun intent(context: Context, e: Entry, isMovie: Boolean): Intent =
            Intent(context, DetailActivity::class.java)
                .putExtra(EXTRA_ID, e.id)
                .putExtra(EXTRA_NAME, e.name)
                .putExtra(EXTRA_GROUP, e.group)
                .putExtra(EXTRA_URL, e.url)
                .putExtra(EXTRA_LOGO, e.logo)
                .putExtra(EXTRA_PLOT, e.description)
                .putExtra(EXTRA_IS_MOVIE, isMovie)
    }
}
