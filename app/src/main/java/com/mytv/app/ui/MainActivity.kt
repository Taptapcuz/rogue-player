package com.mytv.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
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
 * MYTV phone UI — implayer-style dark interface: Live / Movies / Series / Guide /
 * Favourites / Playlists. Add a playlist (M3U or Xtream) via the + button.
 */
class MainActivity : AppCompatActivity() {

    private val app by lazy { application as MyApplication }
    private lateinit var adapter: BrowseAdapter
    private lateinit var tabRow: LinearLayout
    private lateinit var tvPlaceholder: TextView

    private var currentSection = Section.LIVE
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val onClickChannel: (Channel) -> Unit = { ch ->
        startPlayback(ch.url, ch.name, 0)
    }
    private val onClickEntry: (Entry, Boolean) -> Unit = { e, isMovie ->
        startPlayback(e.url, e.name, 0)
    }

    enum class Section { LIVE, MOVIES, SERIES, GUIDE, FAVOURITES, PLAYLISTS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildPhoneUi()
    }

    @SuppressLint("SetTextI18n")
    private fun buildPhoneUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }

        // ---- top bar: MYTV + search hint ----
        root.addView(TextView(this).apply {
            text = "MYTV  1.0"
            setTextColor(Palette.TEXT)
            textSize = 20f
            setPadding(dp(14), dp(14), dp(14), dp(4))
        })

        // ---- tab row ----
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(0), dp(6), dp(8))
        }
        root.addView(tabRow)

        // ---- body: placeholder text + list ----
        tvPlaceholder = TextView(this).apply {
            setTextColor(Palette.MUTED)
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(tvPlaceholder, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(80)))

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
        root.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        adapter = BrowseAdapter(onClickChannel, onClickEntry)
        list.adapter = adapter

        setContentView(root)
        renderTabs()
        reload()
    }

    private fun renderTabs() {
        tabRow.removeAllViews()
        Section.entries.forEach { sec ->
            val active = sec == currentSection
            tabRow.addView(TextView(this).apply {
                text = sectionLabel(sec)
                textSize = 13f
                setTextColor(if (active) Palette.TEXT else Palette.MUTED)
                if (active) setBackgroundColor(Palette.CARD_HI)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener {
                    currentSection = sec
                    renderTabs()
                    reload()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            })
        }
    }

    private fun sectionLabel(sec: Section) = when (sec) {
        Section.LIVE -> "Live TV"
        Section.MOVIES -> "Movies"
        Section.SERIES -> "Series"
        Section.GUIDE -> "Guide"
        Section.FAVOURITES -> "Favourites"
        Section.PLAYLISTS -> "Playlists"
    }

    private fun reload() {
        val pl = app.repository.activePlaylist
        if (pl == null) {
            adapter.submitList(emptyList())
            tvPlaceholder.visibility = View.VISIBLE
            tvPlaceholder.text = "No playlist yet.\nTap + to add an M3U link or an Xtream login."
            return
        }
        tvPlaceholder.visibility = View.GONE

        val items = when (currentSection) {
            Section.LIVE -> pl.channels.map { BrowseItem.ChannelRow(it) as BrowseItem }
            Section.MOVIES -> pl.movies.map { BrowseItem.EntryRow(it, true) as BrowseItem }
            Section.SERIES -> pl.series.map { BrowseItem.EntryRow(it, false) as BrowseItem }
            Section.GUIDE -> guideItems(pl.channels)
            Section.FAVOURITES -> pl.channels.take(6).map { BrowseItem.ChannelRow(it) as BrowseItem } +
                pl.movies.take(4).map { BrowseItem.EntryRow(it, true) as BrowseItem }
            Section.PLAYLISTS -> app.repository.playlists.value.flatMap { p ->
                listOf(BrowseItem.Message("• ${p.name}  (${p.kind.name})  ${p.channels.size} ch") as BrowseItem)
            }
        }
        adapter.submitList(items)
    }

    @SuppressLint("SimpleDateFormat")
    private fun guideItems(channels: List<Channel>): List<BrowseItem> {
        val sdf = SimpleDateFormat("HH:mm", Locale.US)
        val now = System.currentTimeMillis()
        val out = mutableListOf<BrowseItem>()
        out.add(BrowseItem.GuideHeader("Tonight · ${sdf.format(now)}") as BrowseItem)
        channels.take(30).forEachIndexed { i, ch ->
            val start = (i % 5) * 15
            out.add(BrowseItem.GuideShow(
                ch, "Programme ${(i % 8) + 1}",
                "${sdf.format(now)} · +${start}min", (i % 10) / 10f
            ) as BrowseItem)
        }
        return out
    }

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

    /** Show the add-playlist dialog. */
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

        // default: M3U tab active
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
                                val host = hostEdit.text.toString().trim()
                                val u = userEdit.text.toString().trim()
                                val p = passEdit.text.toString().trim()
                                PlaylistParser.load(PlaylistKind.XTREAM, host, u, p)
                            } else {
                                val url = urlEdit.text.toString().trim()
                                PlaylistParser.load(PlaylistKind.M3U, url, null, null)
                            }
                            app.repository.add(if (name.isNotBlank()) src.copy(name = name) else src)
                            reload()
                            dialog.dismiss()
                        } catch (t: Throwable) {
                            Log.e("MYTV", "add failed", t)
                            toast("Load failed: ${t.message}")
                        }
                    }
                }
            }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
