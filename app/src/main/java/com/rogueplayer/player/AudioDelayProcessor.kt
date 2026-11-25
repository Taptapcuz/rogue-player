package com.rogueplayer.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Holds the audio back by a fixed number of milliseconds by emitting silence once at
 * the head of the stream.
 *
 * Why this shape: on the car route the audio reaches the head unit over Android Auto's
 * audio path while the picture is rendered into the projected surface, and those two
 * pipelines buffer differently — the result is a constant offset, not drift. Both
 * clocks still belong to one player, so a one-time delay at the start is exactly the
 * correction needed: everything after it stays aligned.
 *
 * 0 ms means "no processor at all" — PlaybackService only installs this when the user
 * asked for an offset, so the default path is untouched.
 */
class AudioDelayProcessor(private val delayMs: Int) : BaseAudioProcessor() {

    private var pendingFrames = 0
    private var bytesPerFrame = 4
    private var sampleRate = 44_100

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val bytesPerSample = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_FLOAT -> 4
            else -> 2 // 16-bit, the only other PCM encoding ExoPlayer hands to a sink
        }
        bytesPerFrame = bytesPerSample * inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        pendingFrames = framesForDelay()
        return inputAudioFormat
    }

    private fun framesForDelay(): Int =
        (sampleRate.toLong() * delayMs / 1000L).toInt().coerceAtLeast(0)

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Silence first, in bounded chunks, so a long delay never allocates one huge buffer.
        while (pendingFrames > 0) {
            val frames = minOf(pendingFrames, MAX_FRAMES)
            val bytes = frames * bytesPerFrame
            val out = replaceOutputBuffer(bytes)
            repeat(bytes) { out.put(0) }
            out.flip()
            pendingFrames -= frames
        }
        val out = replaceOutputBuffer(inputBuffer.remaining())
        out.put(inputBuffer)
        out.flip()
    }

    override fun onReset() {
        pendingFrames = 0
    }

    override fun onFlush() {
        // A flush restarts the stream (seek / re-prepare): the offset applies again.
        pendingFrames = framesForDelay()
    }

    private companion object {
        const val MAX_FRAMES = 4096
    }
}
