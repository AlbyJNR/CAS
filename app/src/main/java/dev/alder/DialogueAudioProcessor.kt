package dev.alder

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Clear dialogue: voices lifted above music and effects, for TV speakers (Settings › Picture & sound, and the player's
 * Audio & subtitles panel). Sits in the audio sink's chain, so it only ever sees decoded 16-bit PCM; with a receiver
 * taking Dolby and DTS untouched (passthrough) it isn't in the chain at all.
 *
 * Surround (3 channels or more): the centre channel, where dialogue is mixed, goes up 4.5 dB; the LFE on 5.1 and 7.1,
 * where the rumbles are, comes down 6 dB; the rest come down 2 dB. Stereo: split into mid (L+R, where voices sit)
 * and side (L−R, the wide music and effects), mid up, side down, then put back together. A soft knee above 75% of
 * full scale keeps loud speech from clipping into crackle. Off, it copies the sound through untouched.
 */
class DialogueAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount < 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val buffer = replaceOutputBuffer(remaining)
        if (!enabled) {
            buffer.put(inputBuffer)
            buffer.flip()
            return
        }
        val channelCount = inputAudioFormat.channelCount
        val bytesPerFrame = channelCount * 2
        val frames = remaining / bytesPerFrame
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        if (channelCount == 2) {
            for (f in 0 until frames) {
                val l = inputBuffer.short.toFloat()
                val r = inputBuffer.short.toFloat()
                val mid = (l + r) * 0.5f * 1.45f
                val side = (l - r) * 0.5f * 0.75f
                buffer.putShort(saturate(mid + side).toShort())
                buffer.putShort(saturate(mid - side).toShort())
            }
        } else {
            for (f in 0 until frames) {
                for (ch in 0 until channelCount) {
                    val raw = inputBuffer.short.toFloat()
                    val gain = when (ch) {
                        2 -> 1.68f // front centre, +4.5 dB
                        3 -> if (channelCount >= 6) 0.5f else 0.79f // LFE −6 dB on 5.1/7.1; a surround on 5.0
                        else -> 0.79f // left, right, surrounds, −2 dB
                    }
                    buffer.putShort(saturate(raw * gain).toShort())
                }
            }
        }
        if (inputBuffer.hasRemaining()) buffer.put(inputBuffer) // a tail that isn't a whole frame passes as it is
        buffer.flip()
    }

    companion object {
        private const val THRESHOLD = 24576f // 75% of full scale, about −2.7 dBFS
        private const val SPAN = 8191f // the room left up to 32767

        /** Linear below the knee; above it, the excess is squeezed smoothly towards full scale, never past it. */
        internal fun saturate(v: Float): Int {
            val out = when {
                v > THRESHOLD -> { val u = (v - THRESHOLD) / SPAN; THRESHOLD + SPAN * (u / (1f + u)) }
                v < -THRESHOLD -> { val u = (-v - THRESHOLD) / SPAN; -(THRESHOLD + SPAN * (u / (1f + u))) }
                else -> v
            }
            return out.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        }
    }
}
