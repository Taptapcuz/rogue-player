package com.rogueplayer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rogueplayer.data.PhoneVideo
import com.rogueplayer.data.PhoneVideos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's own videos, in Rogue — the landing's Folders tile.
 *
 * Two levels in one screen: video folders (Camera, Download, Movies, … plus Rogue's own
 * Recordings folder) and then the files inside one. Tapping a file plays it in
 * [PlayerActivity] from its `content://` URI, so nothing is copied or re-encoded.
 *
 * The list comes from [PhoneVideos] (MediaStore), which needs READ_MEDIA_VIDEO — asked here
 * if it is still missing, since this is a real activity and can raise the dialog.
 */
class FoldersActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null

    private lateinit var root: FrameLayout
    private lateinit var titleText: TextView
    private var folders: Map<String, List<PhoneVideo>> = emptyMap()
    private var openFolder: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.BG
        root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        setContentView(root)
        // Playing a 500 MB video can get this activity recreated; keep the folder we were in,
        // otherwise back from the player dumps you on the top-level list.
        openFolder = savedInstanceState?.getString(KEY_FOLDER)
        // Back steps out of a folder first, then leaves the screen — targetSdk 36 means
        // predictive back, so the old onBackPressed() override would be ignored.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (openFolder != null) {
                    openFolder = null
                    render()
                } else {
                    finish()
                }
            }
        })
        build()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_FOLDER, openFolder)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ ui ---

    private fun build() {
        root.removeAllViews()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }
        root.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(14), dp(8))
        }
        header.addView(text("‹  Back", 14f, Palette.ACCENTS[0], true).apply {
            isClickable = true
            setPadding(dp(12), dp(8), dp(16), dp(8))
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        })
        titleText = text("", 16f, Palette.TEXT, true).apply { gravity = Gravity.START }
        header.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(text("⟳", 16f, Palette.ACCENTS[0], true).apply {
            isClickable = true
            setPadding(dp(14), dp(8), dp(6), dp(8))
            setOnClickListener { load(force = true) }
        })
        col.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Accent-led hairline under the header, same as the browse screens.
        val rule = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rule.addView(View(this).apply { setBackgroundColor(Palette.ACCENTS[0]) },
            LinearLayout.LayoutParams(dp(46), dp(2)))
        rule.addView(View(this).apply { setBackgroundColor(Palette.LINE) },
            LinearLayout.LayoutParams(0, dp(2), 1f))
        col.addView(rule, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))

        val body = FrameLayout(this)
        col.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        container = body

        if (!hasAccess()) showPermissionPrompt() else load(force = false)
    }

    private lateinit var container: FrameLayout

    private fun titleFor(): String = openFolder ?: "Folders"

    private fun showPlaceholder(textValue: String, action: Pair<String, () -> Unit>? = null) {
        container.removeAllViews()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        col.addView(text(textValue, 14f, Palette.MUTED, false).apply { gravity = Gravity.START })
        action?.let { (label, onClick) ->
            col.addView(text(label, 14f, Palette.ACCENTS[0], true).apply {
                isClickable = true
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) })
        }
        container.addView(col)
        titleText.text = titleFor()
    }

    // ------------------------------------------------------------- library ---

    private fun hasAccess(): Boolean =
        checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionPrompt() {
        showPlaceholder(
            "Rogue needs access to the videos on this phone to list them here.",
            "Allow access to videos" to {
                runCatching {
                    requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_VIDEO), REQ_VIDEO)
                }
            },
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_VIDEO) return
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        android.util.Log.i("RogueFolders", "permission granted=$ok")
        if (ok) {
            com.rogueplayer.data.RecordingStore.ensureFolder(this)
            load(force = true)
        } else {
            Toast.makeText(this, "Videos stay hidden until access is allowed", Toast.LENGTH_LONG).show()
        }
    }

    private fun load(force: Boolean) {
        if (loadJob?.isActive == true && !force) return
        loadJob?.cancel()
        if (!hasAccess()) {
            showPermissionPrompt()
            return
        }
        showPlaceholder("Reading the phone's videos…")
        loadJob = scope.launch {
            val groups = withContext(Dispatchers.IO) {
                runCatching { PhoneVideos.folders(this@FoldersActivity) }.getOrDefault(emptyMap())
            }
            folders = groups
            android.util.Log.i("RogueFolders", "folders=${groups.size} files=${groups.values.sumOf { it.size }}")
            render()
        }
    }

    private fun render() {
        val folder = openFolder
        titleText.text = titleFor()
        if (folder == null) {
            if (folders.isEmpty()) {
                showPlaceholder("No videos found on this phone yet.")
                return
            }
            val rows = folders.map { (name, items) -> Row(name, countLabel(items.size)) { open(name) } }
            showList(rows)
        } else {
            val items = folders[folder].orEmpty()
            if (items.isEmpty()) {
                showPlaceholder("This folder is empty now.")
                return
            }
            val rows = items.map { v -> Row(v.name, meta(v)) { play(v) } }
            showList(rows)
        }
    }

    private fun open(name: String) {
        openFolder = name
        render()
    }

    /**
     * Local videos play through the same bound PlaybackService as everything else — starting
     * only the PlayerActivity left it on a black screen with nothing loaded, because the
     * service is what owns the ExoPlayer instance.
     */
    private fun play(v: PhoneVideo) {
        startService(
            Intent(this, com.rogueplayer.player.PlaybackService::class.java)
                .setAction(com.rogueplayer.player.PlaybackService.ACTION_PLAY)
                .putExtra(com.rogueplayer.player.PlaybackService.EXTRA_URL, v.uri)
                .putExtra(com.rogueplayer.player.PlaybackService.EXTRA_TITLE, v.name)
                .putExtra(com.rogueplayer.player.PlaybackService.EXTRA_POSITION, 0L),
        )
        startActivity(PlayerActivity.intent(this, v.uri, v.name))
    }

    // -------------------------------------------------------------- pieces ---

    private class Row(val title: String, val subtitle: String, val onClick: () -> Unit)

    private fun showList(rows: List<Row>) {
        container.removeAllViews()
        val list = ListView(this).apply {
            adapter = RowAdapter(rows)
            divider = ColorDrawable(Color.argb(40, 255, 255, 255))
            dividerHeight = 1
            cacheColorHint = Color.TRANSPARENT
        }
        container.addView(list, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private inner class RowAdapter(private val rows: List<Row>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = rows[position]
            val v = (convertView as? LinearLayout) ?: LinearLayout(this@FoldersActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
                minimumHeight = dp(58)
            }
            v.removeAllViews()
            val initials = text(initialsOf(row.title), 12f, Color.parseColor("#0B0B10"), true).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Palette.HUES[(row.title.hashCode() and 0x7fffffff) % Palette.HUES.size])
                }
            }
            v.addView(initials, LinearLayout.LayoutParams(dp(36), dp(36)))
            val col = LinearLayout(this@FoldersActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(row.title, 15f, Palette.TEXT, true).apply {
                gravity = Gravity.START
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(text(row.subtitle, 11.5f, Palette.MUTED, false).apply {
                gravity = Gravity.START
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            v.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(12) })
            v.addView(text("›", 18f, Palette.DIM, true))
            v.setOnClickListener { row.onClick() }
            return v
        }
    }

    private fun initialsOf(name: String): String =
        name.split(" ", "-", "_", "|").filter { it.isNotBlank() }
            .take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("").ifBlank { "?" }

    private fun countLabel(n: Int) = if (n == 1) "1 video" else "$n videos"

    private fun meta(v: PhoneVideo): String {
        val bits = ArrayList<String>()
        if (v.durationMs > 0) {
            val total = v.durationMs / 1000
            bits += String.format("%d:%02d", total / 60, total % 60)
        }
        if (v.sizeBytes > 0) {
            bits += if (v.sizeBytes >= 1_000_000_000L) String.format("%.1f GB", v.sizeBytes / 1e9)
            else String.format("%.0f MB", v.sizeBytes / 1e6)
        }
        if (v.modifiedMs > 0) {
            bits += android.text.format.DateFormat.format("MMM d", v.modifiedMs).toString()
        }
        return bits.joinToString(" · ")
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(color)
            gravity = Gravity.CENTER
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_VIDEO = 4812
        const val KEY_FOLDER = "open_folder"
    }
}
