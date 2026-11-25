package com.rogueplayer.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * The gallery-visible folder for Rogue's recordings: `Movies/Rogue Player/Recordings`.
 *
 * Created as soon as the user allows "Photos and videos" for the app, so the folder is there
 * before the first recording instead of appearing later. No storage permission is spent on
 * it: an app may always create its own media through MediaStore.
 *
 * Scoped storage refuses a plain `mkdirs()` below a public collection on most Android 11+
 * devices, so when that fails the folder is created the only other way MediaStore offers —
 * insert one pending video row (which creates the parent directory), then delete the row.
 * The empty directory stays behind, which is exactly what was asked for.
 *
 * Recording itself (`LiveRecorder`) is untouched: it still streams to the app-private file
 * and nothing is copied around.
 */
object RecordingStore {

    private const val TAG = "RecordingStore"

    /** Relative path inside the Videos collection — what the gallery shows as the album. */
    const val RELATIVE_PATH = "Movies/Rogue Player/Recordings"

    private fun publicDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
        "Rogue Player/Recordings",
    )

    /** True when `Movies/Rogue Player/Recordings` exists after this call. Idempotent. */
    fun ensureFolder(context: Context): Boolean {
        val dir = publicDir()
        if (dir.isDirectory) {
            Log.i(TAG, "folder already there: $dir")
            return true
        }
        // 1. The obvious way — still allowed on some devices (and on API < 30).
        val made = runCatching { dir.mkdirs() }
            .onFailure { Log.w(TAG, "mkdir $dir refused", it) }
            .getOrDefault(false)
        if (made || dir.isDirectory) {
            Log.i(TAG, "folder created by mkdir: $dir")
            return true
        }

        // 2. MediaStore route: inserting a row creates the parent directory, deleting the
        //    row leaves the (empty) folder on disk.
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, ".rogue-player-folder")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }.onFailure { Log.w(TAG, "MediaStore insert refused", it) }.getOrNull()
        if (uri == null) {
            Log.w(TAG, "folder could not be created at $dir")
            return false
        }
        runCatching { resolver.openOutputStream(uri)?.close() }
        runCatching { resolver.delete(uri, null, null) }
        val ok = dir.isDirectory
        Log.i(TAG, "folder created via MediaStore: $dir exists=$ok")
        return ok
    }

}
