package com.rogueplayer.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * One playable video the car can reach: either a MediaStore row from the phone's own
 * library, or a file Rogue recorded itself.
 *
 * [uri] is what the player is handed — a `content://` media URI for library videos (no
 * permission on the path, no copy needed), a `file://` URI for our own recordings, which
 * live in the app's private external folder and are invisible to MediaStore.
 */
data class PhoneVideo(
    val id: String,
    val folder: String,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val uri: String,
)

/**
 * The phone's video library, grouped by folder.
 *
 * MediaStore is the only honest source: on Android 10+ the app cannot walk `/sdcard`, and
 * Android 13+ gates the library behind READ_MEDIA_VIDEO (Android 14 adds a "selected
 * videos only" middle state — whatever the user allowed is exactly what comes back).
 * Callers must check the permission before showing an empty list as "no videos".
 */
object PhoneVideos {

    private const val TAG = "PhoneVideos"

    /** Our own recordings folder, shown as one more group. */
    const val RECORDINGS = "Recordings"

    private val VIDEO_EXT = setOf(
        "ts", "mp4", "mkv", "avi", "mov", "m4v", "webm", "flv", "3gp", "mpg", "mpeg", "wmv",
    )

    private val PROJECTION = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
    )

    /** Library videos plus our recordings, grouped by folder name (A→Z). */
    fun folders(context: Context): Map<String, List<PhoneVideo>> {
        val groups = LinkedHashMap<String, MutableList<PhoneVideo>>()
        queryLibrary(context).forEach { v ->
            groups.getOrPut(v.folder) { mutableListOf() }.add(v)
        }
        val ours = recordings(context)
        if (ours.isNotEmpty()) {
            groups.getOrPut(RECORDINGS) { mutableListOf() }.addAll(ours)
        }
        val sorted = java.util.TreeMap<String, List<PhoneVideo>>(
            compareBy(String.CASE_INSENSITIVE_ORDER) { it },
        )
        groups.forEach { (folder, list) ->
            sorted[folder] = list.sortedByDescending { it.modifiedMs }
        }
        return sorted
    }

    /** Every video MediaStore knows about. Empty (with a log line) when it cannot be read. */
    private fun queryLibrary(context: Context): List<PhoneVideo> {
        val out = ArrayList<PhoneVideo>()
        val collection: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        runCatching {
            context.contentResolver.query(
                collection, PROJECTION, null, null,
                MediaStore.Video.Media.DATE_MODIFIED + " DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndex(MediaStore.Video.Media.SIZE)
                val dateCol = c.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val bucketCol = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val raw = c.getString(nameCol) ?: continue
                    val ext = raw.substringAfterLast('.', "").lowercase(Locale.US)
                    if (ext !in VIDEO_EXT) continue
                    out += PhoneVideo(
                        id = id.toString(),
                        folder = c.getString(bucketCol)?.takeIf { it.isNotBlank() } ?: "Other",
                        name = raw.substringBeforeLast('.'),
                        durationMs = if (durCol >= 0) c.getLong(durCol) else 0L,
                        sizeBytes = if (sizeCol >= 0) c.getLong(sizeCol) else 0L,
                        // MediaStore reports DATE_MODIFIED in seconds.
                        modifiedMs = (if (dateCol >= 0) c.getLong(dateCol) else 0L) * 1000L,
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "video query failed", it) }
        return out
    }

    /** Files Rogue recorded into its own Movies/Recordings folder (MediaStore never sees them). */
    fun recordings(context: Context): List<PhoneVideo> {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
            "Recordings",
        )
        val files = dir.listFiles { f ->
            f.isFile && f.name.substringAfterLast('.', "").lowercase(Locale.US) in VIDEO_EXT
        } ?: return emptyList()
        return files.map { f ->
            PhoneVideo(
                id = f.absolutePath,
                folder = RECORDINGS,
                name = f.nameWithoutExtension,
                durationMs = 0L,
                sizeBytes = f.length(),
                modifiedMs = f.lastModified(),
                uri = Uri.fromFile(f).toString(),
            )
        }
    }
}
