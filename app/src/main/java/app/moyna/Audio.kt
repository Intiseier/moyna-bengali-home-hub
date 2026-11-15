package app.moyna

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Plays 24 kHz mono 16-bit sound (Gemini's voice and the chimes) through the media speaker. */
class AudioPlayer {
    private val rate = 24000
    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(max(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT), rate / 5 * 2))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val lock = Object()
    @Volatile private var framesWritten = 0L
    @Volatile private var busy = false

    init {
        thread(name = "moyna-player", isDaemon = true) {
            while (true) {
                val buf = queue.take()
                busy = true
                var off = 0
                while (off < buf.size) {
                    synchronized(lock) {
                        val n = min(4800, buf.size - off)  // 100 ms slices so a "stop" is quick
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                        val w = track.write(buf, off, n)
                        if (w > 0) { off += w; framesWritten += w / 2 } else off = buf.size
                    }
                }
                busy = queue.isNotEmpty()
            }
        }
    }

    fun play(pcm: ByteArray) { if (pcm.isNotEmpty()) queue.put(pcm) }

    /** True when everything queued has actually come out of the speaker. */
    fun isIdle(): Boolean {
        if (busy || queue.isNotEmpty()) return false
        val played = track.playbackHeadPosition.toLong() and 0xffffffffL
        return played >= framesWritten
    }

    /** Stop talking immediately and throw away anything not yet played. */
    fun stopNow() {
        queue.clear()
        synchronized(lock) {
            track.pause(); track.flush()
            framesWritten = 0
        }
        busy = false
    }
}

/** Reads the microphone at 16 kHz mono 16-bit and hands out 100 ms chunks with a loudness value. */
class MicStreamer(private val onChunk: (ByteArray, Int) -> Unit) {
    @Volatile private var running = false
    private var worker: Thread? = null
    /** Whether the current recording uses the phone's echo canceller. */
    @Volatile var echoCancelling = false
        private set

    /** Start (or keep) recording. [echoCancel] = filter Moyna's own voice out of the mic. */
    @SuppressLint("MissingPermission")
    fun start(echoCancel: Boolean = false) {
        if (running && echoCancel == echoCancelling) return
        if (running) { running = false }    // switching mode: restart the recording
        worker?.join(800)   // let a previous recording fully release the microphone
        running = true
        echoCancelling = echoCancel
        worker = thread(name = "moyna-mic", isDaemon = true) {
            val rate = 16000
            val chunk = 3200 // 100 ms
            val bufSize = max(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT), chunk * 4)
            val source = if (echoCancel) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                         else MediaRecorder.AudioSource.VOICE_RECOGNITION
            var rec = AudioRecord(source, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            var aec: android.media.audiofx.AcousticEchoCanceler? = null
            if (echoCancel && rec.state == AudioRecord.STATE_INITIALIZED &&
                android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                aec = android.media.audiofx.AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
            }
            Log.i("Moyna", "mic started: ${if (echoCancel) "VOICE_COMMUNICATION, echo canceller=${aec?.enabled}" else "VOICE_RECOGNITION"}")
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("Moyna", "microphone could not start"); running = false; rec.release(); return@thread
            }
            rec.startRecording()
            try {
                while (running) {
                    val buf = ByteArray(chunk)
                    var got = 0
                    while (got < chunk && running) {
                        val n = rec.read(buf, got, chunk - got)
                        if (n <= 0) break
                        got += n
                    }
                    if (got == chunk) onChunk(buf, rms(buf))
                }
            } finally {
                aec?.release()
                rec.stop(); rec.release()
            }
        }
    }

    fun stop() { running = false }

    private fun rms(b: ByteArray): Int {
        var sum = 0.0
        var i = 0
        while (i + 1 < b.size) {
            val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s; i += 2
        }
        return sqrt(sum / (b.size / 2)).toInt()
    }
}

/** Little sounds made in code (no sound files needed). All 24 kHz mono 16-bit. */
object Sounds {
    private fun tones(vararg notes: Pair<Double, Int>, volume: Double = 0.35): ByteArray {
        val rate = 24000
        val total = notes.sumOf { it.second } * rate / 1000
        val out = ByteArray(total * 2)
        var idx = 0
        for ((freq, ms) in notes) {
            val n = ms * rate / 1000
            for (i in 0 until n) {
                val fade = min(1.0, min(i, n - i) / (rate * 0.012))   // soft edges, no clicks
                val v = (sin(2 * PI * freq * i / rate) * volume * fade * Short.MAX_VALUE).toInt()
                out[idx++] = (v and 0xff).toByte(); out[idx++] = ((v shr 8) and 0xff).toByte()
            }
        }
        return out
    }

    /** Rising two-note chime: "I'm listening". */
    val start: ByteArray by lazy { tones(660.0 to 130, 990.0 to 190) }
    const val START_MS = 320L

    /** Falling two-note chime: "I've stopped listening". */
    val end: ByteArray by lazy { tones(880.0 to 120, 587.0 to 180, volume = 0.22) }

    /** Short high blip used only in the wake word test ("say Moyna now"). */
    val prompt: ByteArray by lazy { tones(1200.0 to 90, volume = 0.25) }

    /** Low double beep for problems. */
    val error: ByteArray by lazy { tones(330.0 to 180, 0.0 to 80, 330.0 to 180, volume = 0.3) }
}
