package com.mytv.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytv.app.MyApplication
import com.mytv.app.R
import com.mytv.app.data.Channel
import com.mytv.app.data.Entry
import com.mytv.app.data.PlaylistKind
import com.mytv.app.data.PlaylistParser
import com.mytv.app.player.PlaybackService
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * MYTV phone UI — implayer layout (top bar + left sidebar + content),
 * blue-on-black palette. Car side projects the player Fermata-style.
 */
class MainActivity : AppCompatActivity() {

    private val app by lazy { application as MyApplication }
    private lateinit var adapter: BrowseAdapter
    private lateinit var sidebar: LinearLayout
    private lateinit var contentPane: FrameLayout
    private lateinit var tvPlaceholder: TextView

    private var currentSection = Section.LIVE
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val onClickChannel: (Channel) -> Unit = { ch -> startPlayback(ch.url, ch.name, 0) }
    private val onClickEntry: (Entry, Boolean) -> Unit = { e, _ -> startPlayback(e.url, e.name, 0) }

    enum class Section(val label: String, val badge: String) {
        LIVE("Live TV", "TV"), GUIDE("TV guide", "≡"),
        MOVIES("Movies", "▣"), SERIES("Series", "▦"),
        FAVOURITES("Favourite", "★"), PLAYLISTS("Playlists", "+"),
        SETTINGS("Settings", "⚙")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.BG
        buildChrome()
    }

    // ------------------------------------------------------------- chrome ---

    @SuppressLint("SetTextI18n")
    private fun buildChrome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }
        root.addView(topBar())

        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sidebar = LinearLayout(this).apply { setBackgroundColor(Palette.PANEL) }
        mid.addView(sidebar, LinearLayout.LayoutParams(dp(192), LinearLayout.LayoutParams.MATCH_PARENT))

        contentPane = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        mid.addView(contentPane, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        root.addView(mid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(footerBar())
        setContentView(root)
        renderSidebar()
        renderContent()
    }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(8))
        }
        // logo tile (blue)
        bar.addView(logo("M", 30))
        bar.addView(TextView(this).apply {
            text = "MYTV"
            textSize = 19f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Palette.ACCENTS[0])
        })
        bar.addView(TextView(this).apply {
            text = "1.0"
            textSize = 11f
            setTextColor(Palette.DIM)
            setPadding(0, 0, dp(12), 0)
        })
        // search pill
        bar.addView(TextView(this).apply {
            text = "  Search channels, movies, series  "
            textSize = 13f
            setTextColor(Palette.DIM)
            setBackground(rounded(Palette.CARD, 9))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // channel count + avatar
        val count = app.repository.activePlaylist?.channels?.size ?: 0
        bar.addView(TextView(this).apply {
            text = "$count channels loaded"
            textSize = 11f
            setTextColor(Palette.MUTED)
            setPadding(dp(6), 0, dp(8), 0)
        })
        bar.addView(avatar("B", 30))
        bar.addView(capsule("LIVE", Palette.LIVE))
        return bar
    }

    private fun renderSidebar() {
        sidebar.removeAllViews()
        sidebar.setPadding(0, dp(8), dp(8), 0)

        sidebar.addView(sideHead("BROWSE"))
        sidebar.addView(nav(Section.LIVE))
        sidebar.addView(nav(Section.GUIDE))
        sidebar.addView(nav(Section.MOVIES))
        sidebar.addView(nav(Section.SERIES))
        sidebar.addView(nav(Section.FAVOURITES))

        sidebar.addView(sideHead("PLAYLISTS"))
        app.repository.playlists.value.take(3).forEach { pl ->
            sidebar.addView(sidePlaylist(pl.name, pl.channels.size, pl.active))
        }
        sidebar.addView(TextView(this).apply {
            text = "+   Add playlist"
            textSize = 12.5f
            setTextColor(Palette.ACCENTS[0])
            setPadding(dp(22), dp(8), dp(10), dp(8))
            setOnClickListener { showAddDialog() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })

        sidebar.addView(nav(Section.SETTINGS))

        // demo-mode footer card
        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setBackground(rounded(Palette.CARD, 10))
        }
        foot.addView(TextView(this).apply {
            text = "●  Demo mode"
            textSize = 12f
            setTextColor(Palette.ACCENTS[0])
        })
        foot.addView(TextView(this).apply {
            text = "Sample channels only"
            textSize = 11f
            setTextColor(Palette.DIM)
        })
        sidebar.addView(foot, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    private fun footerBar(): View {
        val f = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        f.addView(TextView(this).apply {
            text = "MYTV · personal build"
            textSize = 10.5f
            setTextColor(Palette.DIM)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        f.addView(TextView(this).apply {
            text = "car projection: Android Auto"
            textSize = 10.5f
            setTextColor(Palette.DIM)
        })
        return f
    }

    private fun renderContent() {
        contentPane.removeAllViews()
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
        contentPane.addView(list, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        adapter = BrowseAdapter(onClickChannel, onClickEntry)
        list.adapter = adapter

        tvPlaceholder = TextView(this).apply {
            setTextColor(Palette.MUTED)
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        contentPane.addView(tvPlaceholder, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(100)))
        reload()
    }

    // ------------------------------------------------------------- nav ---

    private fun nav(sec: Section): View {
        val active = sec == currentSection
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(9), dp(8), dp(9))
            if (active) setBackground(rounded(withAlpha(Palette.ACCENTS[0], 26), 8))
            setOnClickListener {
                if (sec == Section.PLAYLISTS) { showAddDialog(); return@setOnClickListener }
                currentSection = sec
                renderSidebar(); renderContent()
            }
        }
        row.addView(View(this).apply {
            setBackgroundColor(if (active) Palette.ACCENTS[0] else Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(dp(3), dp(18)))
        row.addView(TextView(this).apply {
            text = "   ${sec.badge}   ${sec.label}"
            textSize = 13.5f
            setTextColor(if (active) Palette.TEXT else Palette.MUTED)
        })
        return row
    }

    private fun sideHead(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 10f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(Palette.DIM)
        setPadding(dp(12), dp(10), dp(10), dp(4))
    }

    private fun sidePlaylist(name: String, count: Int, active: Boolean): View =
        TextView(this).apply {
            text = "$name   $count"
            textSize = 12.5f
            setTextColor(if (active) Palette.TEXT else Palette.MUTED)
            setPadding(dp(22), dp(7), dp(10), dp(7))
        }

    // ------------------------------------------------------------- body ---

    private fun reload() {
        val pl = app.repository.activePlaylist
        if (pl == null) {
            adapter.submitList(emptyList())
            tvPlaceholder.visibility = View.VISIBLE
            tvPlaceholder.text = "No playlist yet.\nTap “+ Add playlist” for an M3U link or Xtream login."
            return
        }
        tvPlaceholder.visibility = View.GONE
        val items = when (currentSection) {
            Section.LIVE -> listOf(
                BrowseItem.SectionTitle("Live TV", "${pl.channels.size} channels from ${pl.name}") as BrowseItem) +
                pl.channels.map { BrowseItem.ChannelRow(it) as BrowseItem }
            Section.MOVIES -> listOf(
                BrowseItem.SectionTitle("Movies", "${pl.movies.size} titles on demand") as BrowseItem) +
                pl.movies.map { BrowseItem.EntryRow(it, true) as BrowseItem }
            Section.SERIES -> listOf(
                BrowseItem.SectionTitle("Series", "${pl.series.size} shows") as BrowseItem) +
                pl.series.map { BrowseItem.EntryRow(it, false) as BrowseItem }
            Section.GUIDE -> guideItems(pl.channels)
            Section.FAVOURITES -> pl.channels.take(6).map { BrowseItem.ChannelRow(it) as BrowseItem } +
                pl.movies.take(4).map { BrowseItem.EntryRow(it, true) as BrowseItem }
            Section.PLAYLISTS -> app.repository.playlists.value.flatMap { p ->
                listOf(BrowseItem.Message("•  ${p.name}  (${p.kind.name})  ${p.channels.size} ch") as BrowseItem)
            }
            Section.SETTINGS -> listOf(
                BrowseItem.SectionTitle("Settings", "How MYTV behaves") as BrowseItem,
                BrowseItem.Setting("Autoplay the next episode", null, true, false) as BrowseItem,
                BrowseItem.Setting("Remember where I stopped", null, true, false) as BrowseItem,
                BrowseItem.Setting("Buffer before playing", null, null, true) as BrowseItem,
                BrowseItem.Setting("Show channel numbers", null, true, false) as BrowseItem,
                BrowseItem.Setting("Accent colour", "blue / cyan / violet", null, false) as BrowseItem,
                BrowseItem.Message("MYTV 1.0 — personal build"),
            )
        }
        adapter.submitList(items)
    }

    @SuppressLint("SimpleDateFormat")
    private fun guideItems(channels: List<Channel>): List<BrowseItem> {
        val sdf = SimpleDateFormat("HH:mm", Locale.US)
        val now = System.currentTimeMillis()
        val out = mutableListOf<BrowseItem>()
        out.add(BrowseItem.SectionTitle("TV guide", "Tonight · ${sdf.format(now)}") as BrowseItem)
        channels.take(30).forEachIndexed { i, ch ->
            val start = (i % 5) * 15
            out.add(BrowseItem.GuideShow(
                ch, "Programme ${(i % 8) + 1}",
                "${sdf.format(now)} · +${start}min", (i % 10) / 10f
            ) as BrowseItem)
        }
        return out
    }

    // -------------------------------------------------------- playback ---

    private fun startPlayback(url: String, title: String, posMs: Long) {
        if (url.isBlank()) { toast("No stream URL"); return }
        startService(Intent(this, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_PLAY)
            .putExtra(PlaybackService.EXTRA_URL, url)
            .putExtra(PlaybackService.EXTRA_TITLE, title)
            .putExtra(PlaybackService.EXTRA_POSITION, posMs))
        toast("▶ $title")
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Show the add-playlist dialog (M3U or Xtream). */
    @SuppressLint("SetTextI18n")
    fun showAddDialog() {
        val fields = LayoutInflater.from(this).inflate(R.layout.dialog_add_playlist, null)
        val urlEdit = fields.findViewById<EditText>(R.id.edit_url)
        val hostEdit = fields.findViewById<EditText>(R.id.edit_host)
        val userEdit = fields.findViewById<EditText>(R.id.edit_user)
        val passEdit = fields.findViewById<EditText>(R.id.edit_pass)
        val nameEdit = fields.findViewById<EditText>(R.id.edit_name)
        val tabM3u = fields.findViewById<TextView>(R.id.tab_m3u)
        val tabXtream = fields.findViewById<TextView>(R.id.tab_xtream)

        var isXtream = false
        fun refresh() {
            urlEdit.visibility = if (isXtream) View.GONE else View.VISIBLE
            val xVis = if (isXtream) View.VISIBLE else View.GONE
            hostEdit.visibility = xVis; userEdit.visibility = xVis; passEdit.visibility = xVis
            tabM3u.setBackgroundColor(if (!isXtream) Palette.CARD_HI else Palette.CARD)
            tabM3u.setTextColor(if (!isXtream) Palette.TEXT else Palette.MUTED)
            tabXtream.setBackgroundColor(if (isXtream) Palette.CARD_HI else Palette.CARD)
            tabXtream.setTextColor(if (isXtream) Palette.TEXT else Palette.MUTED)
        }
        tabM3u.setOnClickListener { isXtream = false; refresh() }
        tabXtream.setOnClickListener { isXtream = true; refresh() }
        refresh()

        AlertDialog.Builder(this)
            .setTitle("Add a playlist")
            .setView(fields)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add", null)
            .show()
            .also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    scope.launch {
                        try {
                            val name = nameEdit.text.toString().trim()
                            val src = if (isXtream) {
                                PlaylistParser.load(PlaylistKind.XTREAM,
                                    hostEdit.text.toString().trim(),
                                    userEdit.text.toString().trim(),
                                    passEdit.text.toString().trim())
                            } else {
                                PlaylistParser.load(PlaylistKind.M3U,
                                    urlEdit.text.toString().trim(), null, null)
                            }
                            app.repository.add(if (name.isNotBlank()) src.copy(name = name) else src)
                            renderSidebar(); renderContent()
                            dialog.dismiss()
                        } catch (t: Throwable) {
                            Log.e("MYTV", "add failed", t)
                            toast("Load failed: ${t.message}")
                        }
                    }
                }
            }
    }

    // -------------------------------------------------------- palette ---

    private fun logo(ch: String, size: Int): View {
        val tile = TextView(this).apply {
            text = ch
            textSize = size * 0.5f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackground(rounded(Palette.ACCENTS[0], 7))
        }
        tile.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
            marginEnd = dp(8)
        }
        return tile
    }

    private fun avatar(ch: String, size: Int): View {
        val a = TextView(this).apply {
            text = ch
            textSize = size * 0.42f
            setTextColor(Palette.TEXT)
            gravity = Gravity.CENTER
            setBackground(rounded(Palette.CARD_HI, size / 2))
        }
        a.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
            marginEnd = dp(8)
        }
        return a
    }

    private fun capsule(label: String, color: Int): View = TextView(this).apply {
        text = label
        textSize = 10.5f
        setTextColor(color)
        setBackground(rounded(withAlpha(color, 24), 11))
        setPadding(dp(10), dp(5), dp(10), dp(5))
    }

    private fun rounded(fill: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius * resources.displayMetrics.density
        }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
