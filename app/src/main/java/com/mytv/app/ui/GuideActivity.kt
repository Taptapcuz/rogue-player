package com.mytv.app.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.PlayerView
import com.mytv.app.MyApplication
import com.mytv.app.R
import com.mytv.app.data.Channel
import com.mytv.app.data.EpgProgram
import com.mytv.app.data.PlaylistKind
import com.mytv.app.player.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Full-screen TV guide (TiViMate layout): channel gutter + time ruler +
 * programme blocks, with a live preview window pinned in the corner showing the
 * highlighted channel. Tap a channel name to preview it, tap a programme to see
 * what it is, tap the preview to go fullscreen.
 *
 * EPG is fetched per channel on demand as rows become visible — an Xtream
 * provider only exposes a per-channel endpoint, so pulling every channel's day
 * at once would hammer it.
 */
class GuideActivity : AppCompatActivity() {

    private lateinit var grid: EpgGridView
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var previewCard: View
    private lateinit var previewView: PlayerView
    private lateinit var previewChannel: TextView
    private lateinit var previewProgram: TextView
    private lateinit var previewStatus: TextView

    /** Playback retry bookkeeping for the preview pane. */
    private var retries = 0
    private var listenerAttached = false
    private var pendingPlay: Channel? = null

    /** Scroll fires a range change per frame; only start a fetch once it settles. */
    private var queuedRange: IntRange = 0..0
    private val loadDebounce = Runnable { if (!isFinishing) load(queuedRange) }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val app by lazy { application as MyApplication }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Channel ids already requested (in flight or done). */
    private val requested = mutableSetOf<String>()
    private var loadJob: Job? = null
    private var inFlight = 0

    /**
     * Channels whose EPG fetch failed, with the time before which we won't retry
     * them. Without this a single provider hiccup blanked a channel's guide for
     * the whole session, and a scroll past it never asked again.
     */
    private val epgRetryAt = mutableMapOf<String, Long>()
    private val epgRetryMs = 45_000L

    /** Consecutive EPG failures — the provider resets sockets, so give up early. */
    private var epgFailures = 0
    private var epgHits = 0
    private var epgDisabled = false

    private val prefs by lazy { getSharedPreferences("mytv_guide", MODE_PRIVATE) }

    /** Sentinel "group" for the favourites filter — provider groups never start with ★. */
    private val FAV_GROUP = "★ Favorites"

    /** Favourited channel ids, shared with the channel list via their own prefs file. */
    private val favPrefs by lazy { getSharedPreferences("mytv_favorites", MODE_PRIVATE) }
    private val favorites = mutableSetOf<String>()

    private var svc: PlaybackService? = null
    private var bound = false
    private var current: Channel? = null

    private lateinit var groupRow: android.widget.LinearLayout
    private var allChannels: List<Channel> = emptyList()
    private var selectedGroup: String? = null

    /** Loaded guide data per channel id, kept across category switches. */
    private val epgMap = mutableMapOf<String, List<EpgProgram>>()

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? PlaybackService.LocalBinder)?.getService() ?: return
            svc = service
            bound = true
            if (!listenerAttached) {
                service.attach(previewListener)
                listenerAttached = true
            }
            previewView.player = service.getPlayer()
            pendingPlay?.let { ch ->
                pendingPlay = null
                service.play(ch.url, ch.name)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        window.statusBarColor = Palette.BG

        grid = findViewById(R.id.guide_grid)
        status = findViewById(R.id.guide_status)
        empty = findViewById(R.id.guide_empty)
        previewCard = findViewById(R.id.guide_preview_card)
        previewView = findViewById(R.id.guide_preview)
        previewChannel = findViewById(R.id.guide_preview_channel)
        previewProgram = findViewById(R.id.guide_preview_program)
        previewStatus = findViewById(R.id.guide_preview_status)

        findViewById<TextView>(R.id.guide_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.guide_now).setOnClickListener { grid.scrollToNow() }
        findViewById<TextView>(R.id.guide_zoom_in).setOnClickListener {
            grid.setZoom(grid.pxPerMinute * 1.3f)
        }
        findViewById<TextView>(R.id.guide_zoom_out).setOnClickListener {
            grid.setZoom(grid.pxPerMinute / 1.3f)
        }
        previewCard.setOnClickListener { current?.let { openFullscreen(it) } }
        previewView.setOnClickListener { current?.let { openFullscreen(it) } }

        // Preview column: fills the right side of the guide, video kept 16:9.
        applyPreviewSize()

        bindService(
            Intent(this, PlaybackService::class.java),
            connection,
            BIND_AUTO_CREATE,
        )

        val playlist = app.repository.activePlaylist
        if (playlist == null || playlist.channels.isEmpty()) {
            showEmpty("No channels. Add a playlist first.")
            return
        }
        if (playlist.kind != PlaylistKind.XTREAM) {
            showEmpty("Guide data needs an Xtream source (this playlist is M3U).")
            return
        }

        allChannels = playlist.channels
        favorites.addAll(favPrefs.getStringSet("ids", emptySet()).orEmpty())
        grid.favoriteIds = favorites.toSet()
        grid.onChannelLongClick = { ch -> toggleFavorite(ch) }
        selectedGroup = prefs.getString(KEY_GROUP, null)
            ?.takeIf { g -> g == FAV_GROUP || playlist.channels.any { it.group == g } }
        groupRow = findViewById(R.id.guide_groups)
        buildGroupChips()
        rebuildRows()
        grid.begin()

        grid.onVisibleRangeChanged = { range ->
            queuedRange = range
            handler.removeCallbacks(loadDebounce)
            handler.postDelayed(loadDebounce, 400)
        }
        grid.onChannelClick = { ch -> preview(ch) }
        grid.onProgramClick = { ch, p -> preview(ch, p) }

        // Prime the first screenful, then preview the first channel like TiViMate.
        grid.post {
            load(0..12)
            // Let the first EPG requests settle before opening a stream, otherwise
            // the provider resets the video socket mid-handshake.
            prefs.getInt(KEY_ROW, -1).takeIf { it > 0 }?.let { grid.scrollToRow(it) }
            load(grid.visibleRows())
            handler.postDelayed({
                if (isFinishing) return@postDelayed
                val saved = prefs.getString(KEY_CHANNEL, null)
                val resume = grid.rows.firstOrNull { it.channel.name == saved }?.channel
                    ?: grid.rows.firstOrNull()?.channel
                resume?.let { preview(it) }
            }, 900)
        }
    }

    /** "★ Favorites" (first), then "All", then every channel group, as scrollable tabs. */
    private fun buildGroupChips() {
        groupRow.removeAllViews()
        val favCount = allChannels.count { it.id in favorites }
        addChip(FAV_GROUP, "★ Favorites $favCount")
        addChip(null, "All ${allChannels.size}")
        allChannels.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()
            .forEach { addChip(it, it.replace("_", " ")) }
    }

    /** Long-press in the guide: star / unstar a channel and refresh the tab counts. */
    private fun toggleFavorite(ch: Channel) {
        // Read-modify-write against prefs: two live GuideActivity instances (a
        // recreation, or the guide still finishing while a new one opens) each held
        // their own copy and the later writer wiped the other's star.
        val stored = favPrefs.getStringSet("ids", emptySet()).orEmpty().toMutableSet()
        val added = if (ch.id in stored) {
            stored.remove(ch.id)
            false
        } else {
            stored.add(ch.id)
            true
        }
        favPrefs.edit().putStringSet("ids", stored).commit()
        favorites.clear()
        favorites.addAll(stored)
        grid.favoriteIds = favorites.toSet()
        buildGroupChips()
        if (selectedGroup == FAV_GROUP) {
            rebuildRows()
            grid.post { load(grid.visibleRows()) }
        }
        android.widget.Toast.makeText(
            this,
            if (added) "★ Added to Favorites — ${ch.name}" else "Removed from Favorites — ${ch.name}",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    private fun addChip(group: String?, label: String) {
        val active = selectedGroup == group
        val chip = android.widget.TextView(this).apply {
            text = label
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(if (active) android.graphics.Color.WHITE else Palette.MUTED)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (active) Palette.ACCENTS[0] else Palette.CARD)
            }
            isClickable = true
            setOnClickListener { selectGroup(group) }
        }
        val lp = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(6) }
        groupRow.addView(chip, lp)
    }

    private fun selectGroup(group: String?) {
        if (selectedGroup == group) return
        selectedGroup = group
        prefs.edit().putString(KEY_GROUP, group).apply()
        buildGroupChips()
        rebuildRows()
        grid.resetVertical()
        grid.post { load(grid.visibleRows()) }
        grid.rows.firstOrNull()?.channel?.let { preview(it) }
    }

    /** Rebuild rows for the selected category, reusing guide data already loaded. */
    private fun rebuildRows() {
        val list = when (selectedGroup) {
            FAV_GROUP -> allChannels.filter { it.id in favorites }
            null -> allChannels
            else -> allChannels.filter { it.group == selectedGroup }
        }
        grid.rows = list.map { EpgGridView.Row(it, epgMap[it.id].orEmpty()) }
        grid.selectedChannelId = current?.id
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            list.isNotEmpty() -> ""
            selectedGroup == FAV_GROUP -> "No favorites yet — long-press a channel in the guide to add one."
            else -> "No channels in this category."
        }
    }

    private fun showEmpty(message: String) {
        empty.visibility = View.VISIBLE
        empty.text = message
        previewCard.visibility = View.GONE
        grid.rightInset = 0f
    }

    private val previewWidth: Int
        get() = (resources.displayMetrics.widthPixels * 0.36f)
            .toInt().coerceIn(dp(170), dp(420))

    /**
     * Responsive preview: a full-height column on the right in landscape, and a
     * full-width band on top of the guide in portrait (TiViMate does the same —
     * a side pane on a phone leaves the grid unusably narrow).
     */
    private fun applyPreviewSize() {
        val dm = resources.displayMetrics
        val portrait = dm.heightPixels >= dm.widthPixels
        if (portrait) {
            val row = findViewById<android.widget.LinearLayout>(R.id.guide_root)
            if (previewCard.parent !== row) {
                (previewCard.parent as? android.view.ViewGroup)?.removeView(previewCard)
                row.addView(previewCard, 2)   // header, categories, preview, grid
            }
            previewCard.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // 16:9 plus the time ruler only. Full ruler + a whole row was too tall:
            // `zoom` has to crop the sides to fill the box, and at +90dp that ate ~a
            // third of the picture width. +30dp keeps the band taller than a plain
            // 16:9 strip while cropping ~7% per side, so logos and score bugs survive.
            val videoH = (dm.widthPixels - dp(16)) * 9f / 16f
            val firstRowBottom = grid.rulerHeightPx
            previewView.layoutParams =
                (previewView.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                    weight = 0f
                    height = (videoH + firstRowBottom).toInt()
                }
            grid.rightInset = 0f
        } else {
            val frame = findViewById<android.widget.FrameLayout>(R.id.guide_frame)
            if (previewCard.parent !== frame) {
                (previewCard.parent as? android.view.ViewGroup)?.removeView(previewCard)
                frame.addView(previewCard)
            }
            val cardW = previewWidth
            previewCard.layoutParams = android.widget.FrameLayout.LayoutParams(
                cardW,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.Gravity.TOP or android.view.Gravity.END,
            )
            previewView.layoutParams =
                (previewView.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                    weight = 1f
                    height = 0
                }
            grid.rightInset = cardW.toFloat()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPreviewSize()
    }


    /**
     * Providers reset sockets aggressively (they also reset every request after a
     * burst of EPG calls), so a dead preview is usually transient: retry with a
     * growing delay and tell the user instead of leaving a black pane.
     */
    private val previewListener = object : androidx.media3.common.Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            if (isFinishing || current == null) return
            if (retries < 4) {
                retries += 1
                showStatus("Reconnecting… ($retries)")
                handler.postDelayed({ current?.let { restart(it) } }, 1200L * retries)
            } else {
                showStatus("Stream unavailable — tap the channel to retry")
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == androidx.media3.common.Player.STATE_READY) {
                retries = 0
                hideStatus()
            }
        }
    }

    /**
     * Retry through the *bound* service. Calling startService() from a delayed
     * handler throws BackgroundServiceStartNotAllowedException as soon as the app
     * is not in the foreground — that was crashing the guide mid-scroll.
     */
    private fun restart(ch: Channel) {
        if (ch.url.isBlank() || isFinishing || isDestroyed) return
        svc?.play(ch.url, ch.name)
    }

    private fun showStatus(text: String) {
        if (isFinishing || isDestroyed) return
        previewStatus.text = text
        previewStatus.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        previewStatus.visibility = View.GONE
    }

    // ------------------------------------------------------------- preview ---

    /** Make [ch] the previewed channel, playing in the corner window. */
    private fun preview(ch: Channel, program: EpgProgram? = null) {
        current = ch
        prefs.edit().putString(KEY_CHANNEL, ch.name).apply()
        grid.selectedChannelId = ch.id
        previewChannel.text = ch.name

        if (ch.url.isNotBlank()) {
            val service = svc
            if (service == null) {
                pendingPlay = ch      // service not bound yet; play on connect
            } else {
                service.play(ch.url, ch.name)
            }
        }
        retries = 0
        svc?.let { previewView.player = it.getPlayer() }
        showNowPlaying(ch, program)
    }

    /** Fill the "what's on" line under the preview. */
    private fun showNowPlaying(ch: Channel, program: EpgProgram? = null) {
        val now = System.currentTimeMillis()
        val p = program ?: epgMap[ch.id]?.firstOrNull { now >= it.start && now < it.end }
        previewProgram.text = when {
            p == null -> ""
            program != null -> "${timeFormat.format(p.start)} – ${timeFormat.format(p.end)} · ${p.title}"
            else -> "${timeFormat.format(p.start)} – ${timeFormat.format(p.end)} · ${p.title} · Now"
        }
    }

    private fun openFullscreen(ch: Channel) {
        if (ch.url.isBlank()) return
        // keepStreamOnBack: the preview behind us stays on the same channel.
        startActivity(PlayerActivity.intent(this, ch.url, ch.name, keepStreamOnBack = true))
    }

    // ------------------------------------------------------------ EPG load ---

    /** Fetch EPG for the requested rows plus a little lookahead. */
    private fun load(range: IntRange) {
        if (epgDisabled || isFinishing || isDestroyed) return
        val rows = grid.rows
        if (rows.isEmpty()) return
        val now = System.currentTimeMillis()
        // Only the rows on screen plus a small margin: the old -3/+8 window asked
        // the provider for up to 18 channels per scroll range change, which is the
        // kind of burst Xtream panels answer with a socket reset.
        val from = (range.first - 1).coerceAtLeast(0)
        val to = (range.last + 2).coerceAtMost(rows.size - 1)
        val todo = (from..to).mapNotNull { i ->
            rows.getOrNull(i)?.channel?.takeIf { ch ->
                ch.id !in requested && (epgRetryAt[ch.id] ?: 0L) <= now
            }
        }
        if (todo.isEmpty()) return

        todo.forEach { requested.add(it.id) }
        inFlight += todo.size
        updateStatus()

        loadJob?.cancel()
        loadJob = scope.launch {
            // Claimed but not fetched yet. If a newer scroll cancels this job, the
            // rest are handed back in `finally` — otherwise they'd stay in
            // `requested` forever and their rows would show "No guide data" for the
            // whole session, which looked like the guide being broken.
            val pending = ArrayDeque<Channel>(todo)
            try {
                while (pending.isNotEmpty()) {
                    if (epgDisabled) return@launch
                    val ch = pending.removeFirst()
                    val started = System.currentTimeMillis()
                    val result = withContext(Dispatchers.IO) {
                        runCatching { app.repository.epgFor(ch) }
                    }
                    // A reply this fast came from the disk cache, not the provider;
                    // no need to space those out, so a cached guide fills instantly.
                    val fromCache = System.currentTimeMillis() - started < 60
                    val epg = result.getOrNull().orEmpty()
                    if (epg.isNotEmpty()) {
                        epgHits += 1
                        epgFailures = 0
                        epgRetryAt.remove(ch.id)
                    } else if (result.isFailure) {
                        // Empty listings are legitimate (e.g. PPV "Event" channels);
                        // only real errors/socket resets count toward giving up.
                        epgFailures += 1
                        requested.remove(ch.id)
                        epgRetryAt[ch.id] = System.currentTimeMillis() + epgRetryMs
                        if (epgHits == 0 && epgFailures >= 20) {
                            epgDisabled = true
                            showStatus("Guide data unavailable from this source")
                        }
                    } else {
                        epgFailures = 0
                    }
                    applyEpg(ch, epg)
                    inFlight -= 1
                    updateStatus()
                    if (!fromCache) delay(350)
                }
            } finally {
                pending.forEach { requested.remove(it.id) }
                inFlight -= pending.size
                updateStatus()
            }
        }
    }

    private fun applyEpg(channel: Channel, epg: List<EpgProgram>) {
        if (isFinishing || isDestroyed) return
        epgMap[channel.id] = epg
        grid.loadedChannels.add(channel.id)
        val index = grid.rows.indexOfFirst { it.channel.id == channel.id }
        if (index < 0) return
        grid.rows[index].programmes = epg
        android.util.Log.d("MYTVGuide", "epg ${channel.name} -> ${epg.size} programmes")
        grid.invalidate()
        if (current?.id == channel.id) showNowPlaying(channel)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun updateStatus() {
        if (isFinishing || isDestroyed) return
        status.text = if (inFlight > 0) "loading $inFlight…" else ""
    }

    override fun onResume() {
        super.onResume()
        // The player may still be drawing into the fullscreen player's surface
        // (or none at all) after leaving it — force it onto this view's surface,
        // otherwise audio keeps playing over a black picture.
        val service = svc ?: return
        previewView.player = null
        previewView.player = service.getPlayer()
    }

    override fun onPause() {
        prefs.edit().putInt(KEY_ROW, grid.topRow).apply()
        super.onPause()
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private companion object {
        const val KEY_GROUP = "group"
        const val KEY_CHANNEL = "channel"
        const val KEY_ROW = "row"
    }

    override fun onDestroy() {
        handler.removeCallbacks(loadDebounce)
        handler.removeCallbacksAndMessages(null)
        loadJob?.cancel()
        // Back out of the guide and the preview stops: it used to keep playing
        // audio behind the channel list, which reads as a bug. Only a finishing
        // guide stops the stream, so opening the fullscreen player on top of it
        // does not kill the preview.
        if (isFinishing) runCatching { svc?.stop() }
        if (bound) runCatching { unbindService(connection) }
        super.onDestroy()
    }
}
