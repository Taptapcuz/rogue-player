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
import android.widget.HorizontalScrollView
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
 * MYTV phone UI — blue-on-black IMPlayer-style clone.
 * Header (logo, search, sort) + filter chips + content, and a floating
 * bottom nav: Home · Live TV · Movies · TV Shows · a 5th (Playlists/Sports).
 *
 * The car side (unchanged) renders the live list via an Android Auto
 * PlaceListNavigationTemplate and projects the player Fermata-style.
 */
class MainActivity : AppCompatActivity() {

    private val app by lazy { application as MyApplication }
    private lateinit var adapter: BrowseAdapter
    private lateinit var contentPane: FrameLayout
    private lateinit var tvPlaceholder: TextView
    private lateinit var navRow: LinearLayout

    private var tab = Tab.LIVE
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val onClickChannel: (Channel) -> Unit = { ch -> startPlayback(ch.url, ch.name, 0) }
    private val onClickEntry: (Entry, Boolean) -> Unit = { e, _ -> startPlayback(e.url, e.name, 0) }

    enum class Tab(val short: String, val label: String, val icon: String) {
        HOME("Home", "Home", "⌂"),
        LIVE("Live TV", "Live TV", "▶"),
        MOVIES("Movies", "Movies", "▢"),
        SHOWS("TV Shows", "TV Shows", "▣"),
        PLAYLISTS("Playlists", "Playlists", "+"),
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
        root.addView(header())

        contentPane = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        root.addView(contentPane, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        renderContent()

        // floating bottom nav over content
        navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackground(rounded(Palette.PANEL, 22))
            elevation = 8f
        }
        contentPane.addView(navRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(64)).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(16), 0, dp(16), dp(14))
        })
        renderNav()
    }

    private fun header(): View {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(4))
        }
        h.addView(logo("M", 32))
        h.addView(TextView(this).apply {
            text = "MYTV"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Palette.TEXT)
        })
        val tv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tv.addView(TextView(this).apply {
            text = "mobile"
            textSize = 9.5f
            setTextColor(Color.WHITE)
            setBackground(rounded(Palette.ACCENTS[0], 6))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(46), dp(16)))
        h.addView(tv)
        h.addView(TextView(this).apply { // spacer
            text = "1.0"
            textSize = 10f
            setTextColor(Palette.DIM)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.END
            setMargins(dp(10), 0, 0, 0)
        })
        h.addView(TextView(this).apply { text = "⌕"; textSize = 18f; setTextColor(Palette.MUTED) }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { gravity = Gravity.CENTER_VERTICAL })
        h.addView(TextView(this).apply { text = "A↕"; textSize = 14f; setTextColor(Palette.MUTED) }, LinearLayout.LayoutParams(dp(40), dp(34)).apply { gravity = Gravity.CENTER_VERTICAL })
        h.addView(logo("i", 28))
        return h
    }

    private fun filterChips(): View {
        val scroll = HorizontalScrollView(this)
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        scroll.addView(chips)
        val defs = when (tab) {
            Tab.LIVE -> listOf("All Channels" to true, "News" to false, "Sports" to false, "Movies" to false,
                "Kids" to false, "Music" to false, "Documentaries" to false)
            Tab.MOVIES -> listOf("All Movies" to true, "Favourites" to false, "Watched" to false)
            Tab.SHOWS -> listOf("All TV Shows" to true, "Favourites" to false, "Watched" to false)
            Tab.HOME -> listOf("All Sections" to true, "Live TV" to false, "Movies" to false, "TV Shows" to false)
            else -> emptyList()
        }
        defs.forEach { (label, active) ->
            chips.addView(chip(label, active), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })
        }
        return scroll
    }

    private fun chip(label: String, active: Boolean): View = TextView(this).apply {
        text = label
        textSize = 12.5f
        setTextColor(if (active) Color.WHITE else Palette.MUTED)
        setBackground(rounded(if (active) Palette.ACCENTS[0] else Palette.CARD, 18))
        setPadding(dp(14), dp(7), dp(14), dp(7))
    }

    // ------------------------------------------------------------- nav ---

    private fun renderNav() {
        navRow.removeAllViews()
        Tab.entries.forEach { t ->
            val active = t == tab
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener {
                    if (t == Tab.PLAYLISTS) { showAddDialog(); return@setOnClickListener }
                    tab = t; renderContent(); renderNav()
                }
            }
            item.addView(TextView(this).apply {
                text = t.icon
                textSize = 18f
                setTextColor(if (active) Palette.ACCENTS[0] else Palette.MUTED)
            })
            item.addView(TextView(this).apply {
                text = t.label
                textSize = 10f
                setTextColor(if (active) Palette.ACCENTS[0] else Palette.MUTED)
            })
            navRow.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    // ------------------------------------------------------------- body ---

    private fun renderContent() {
        contentPane.removeAllViews()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentPane.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        col.addView(filterChips())

        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity) }
        col.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        adapter = BrowseAdapter(onClickChannel, onClickEntry)
        list.adapter = adapter

        tvPlaceholder = TextView(this).apply {
            setTextColor(Palette.MUTED); textSize = 14f
            gravity = Gravity.CENTER; visibility = View.GONE
        }
        col.addView(tvPlaceholder, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(120)))

        reload()
    }

    private fun reload() {
        val pl = app.repository.activePlaylist
        if (pl == null) {
            adapter.submitList(emptyList())
            tvPlaceholder.visibility = View.VISIBLE
            tvPlaceholder.text = "No playlist yet.\nTap “Playlists” (bottom right) for an M3U link or Xtream login."
            return
        }
        tvPlaceholder.visibility = View.GONE
        val items = when (tab) {
            Tab.HOME -> listOf(
                BrowseItem.SectionTitle("Top Channels", "${pl.channels.size} available") as BrowseItem) +
                pl.channels.take(12).map { BrowseItem.ChannelCard(it) as BrowseItem }
            Tab.LIVE -> listOf(
                BrowseItem.SectionTitle("Live TV", "${pl.channels.size} channels") as BrowseItem) +
                pl.channels.map { BrowseItem.ChannelCard(it) as BrowseItem }
            Tab.MOVIES -> listOf(
                BrowseItem.SectionTitle("All Movies", "${pl.movies.size}") as BrowseItem) +
                pl.movies.map { BrowseItem.PosterCard(it, true) as BrowseItem }
            Tab.SHOWS -> listOf(
                BrowseItem.SectionTitle("All TV Shows", "${pl.series.size}") as BrowseItem) +
                pl.series.map { BrowseItem.PosterCard(it, false) as BrowseItem }
            else -> pl.channels.map { BrowseItem.ChannelCard(it) as BrowseItem }
        }
        adapter.submitList(items)
    }

    @SuppressLint("SimpleDateFormat")
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

    /** Add-playlist dialog (M3U or Xtream). */
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
                            renderContent(); renderNav()
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

    private fun rounded(fill: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius * resources.displayMetrics.density
        }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
