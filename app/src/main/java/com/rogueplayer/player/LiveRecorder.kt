package com.rogueplayer.player

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * One-tap recording of the live channel that is currently playing.
 *
 * The provider's live streams are HLS with short `.ts` segments, so this is a byte-for-byte
 * copy of the media segments — no re-encode, no CPU cost, and the picture is exactly what the
 * stream carries. Segments are appended in order to a single `.ts` file, which every player
 * here (and VLC) opens directly.
 *
 * Deliberately NOT a service: recording lives as long as this process, which is what
 * "record what I'm watching" means. Closing the player stops it and keeps the file so far.
 *
 * Files land in the app's own external Movies/Recordings folder:
 *   Android/data/<applicationId>/files/Movies/Recordings/<channel>-<yyyyMMdd-HHmmss>.ts
 * No storage permission is needed there, and the customer can pull them over USB or with any
 * file manager.
 */
object LiveRecorder {

    private const val TAG = "LiveRecorder"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Wall-clock start of the current recording. */
    @Volatile private var startedAt = 0L

    /** Bytes written so far in the current recording. */
    @Volatile private var bytesWritten = 0L

    @Volatile private var currentFile: File? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun isRecording(): Boolean = job?.isActive == true

    fun elapsedMs(): Long = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

    fun bytes(): Long = bytesWritten

    fun file(): File? = currentFile

    /**
     * Begin recording [streamUrl]. [onFinish] runs once the recording has stopped, with the
     * file (null when nothing was written) and an optional error message.
     */
    fun start(
        context: Context,
        streamUrl: String,
        title: String,
        onFinish: (file: File?, error: String?) -> Unit,
    ) {
        if (isRecording()) return
        if (streamUrl.isBlank()) {
            onFinish(null, "Nothing is playing")
            return
        }

        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
            "Recordings"
        )
        runCatching { dir.mkdirs() }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safeTitle = title.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().take(48)
            .ifBlank { "recording" }
        val file = File(dir, "$safeTitle-$stamp.ts")

        startedAt = System.currentTimeMillis()
        bytesWritten = 0L
        currentFile = file
        Log.i(TAG, "start -> ${file.absolutePath}")

        job = scope.launch {
            var error: String? = null
            try {
                FileOutputStream(file).use { out ->
                    val playlistUrl = resolve(streamUrl)

                    // Cells slide: remember the last sequence number we stored and only
                    // append the ones the provider has added since.
                    var lastSequence = -1L

                    while (isActive) {
                        val playlist = fetchText(playlistUrl)
                        val lines = playlist.lines()
                        val sequence = lines
                            .firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
                            ?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
                        val segments = lines.filter { it.isNotBlank() && !it.startsWith("#") }

                        if (segments.isEmpty()) {
                            // Provider hiccup or an ad break with no segments yet.
                            delay(1500)
                            continue
                        }
                        if (lastSequence < 0) lastSequence = sequence - 1

                        segments.forEachIndexed { index, raw ->
                            if (!isActive) return@forEachIndexed
                            val seq = sequence + index
                            if (seq <= lastSequence) return@forEachIndexed
                            val absolute = if (raw.startsWith("http")) raw else resolveAgainst(playlistUrl, raw)
                            val bytes = fetchBytes(absolute)
                            if (bytes.isNotEmpty()) {
                                out.write(bytes)
                                out.flush()
                                bytesWritten += bytes.size
                                lastSequence = seq
                            }
                        }

                        val target = lines
                            .firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }
                            ?.substringAfter(':')?.trim()?.toLongOrNull() ?: 4L
                        delay((target * 1000L / 2).coerceAtLeast(1000L))
                    }
                }
            } catch (t: Throwable) {
                if (job?.isActive != false) {
                    Log.w(TAG, "recording stopped", t)
                    error = t.message ?: "Recording stopped"
                }
            } finally {
                val written = bytesWritten
                val result = file.takeIf { it.length() > 0 }
                Log.i(TAG, "stop: ${written / 1024} KB in $file ${if (error != null) "($error)" else ""}")
                startedAt = 0L
                currentFile = null
                job = null
                onFinish(result, if (result == null) error ?: "Nothing was recorded" else error)
            }
        }
    }

    /** Stop the current recording; the partial file is kept. */
    fun stop() {
        job?.cancel()
    }

    // ---------------------------------------------------------------- http ---

    /** Follow the provider's 302 to the real media playlist URL. */
    private fun resolve(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            return response.request.url.toString()
        }
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun fetchBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ByteArray(0)
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun resolveAgainst(base: String, relative: String): String =
        base.toHttpUrlOrNull()?.resolve(relative)?.toString() ?: relative

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
}
