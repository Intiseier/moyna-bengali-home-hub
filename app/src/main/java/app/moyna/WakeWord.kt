package app.moyna

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** What the on-phone listener is listening for right now. */
enum class Listen(val file: String) {
    /** Waiting in a quiet room: listen for "Moyna" (assets/kws/wake.txt). */
    WAKE("kws/wake.txt"),
    /** Waiting while the TV is on / the room is noisy: stricter "Moyna" (assets/kws/wake-tv.txt). */
    WAKE_TV("kws/wake-tv.txt"),
    /** In a conversation: stop words like "থামো" / "stop" (assets/kws/commands.txt), plus "Moyna". */
    COMMAND("kws/commands.txt"),
}

/**
 * Listens for words entirely on the phone (sherpa-onnx keyword spotting, no internet).
 * Feed it 16 kHz microphone audio with the current [Listen] mode; it calls [onHeard] with the
 * label after "@" in the keyword files (e.g. "MOYNA" or "STOP").
 */
class WakeWord(assets: AssetManager, private val onHeard: (String, Listen) -> Unit) {

    private val tag = "Moyna"
    private class Chunk(val pcm: ByteArray, val mode: Listen)
    private val queue = LinkedBlockingQueue<Chunk>(30)   // ~3 seconds of audio
    @Volatile private var flush = false
    @Volatile var available = false
        private set

    init {
        thread(name = "moyna-wake", isDaemon = true, priority = Thread.NORM_PRIORITY + 1) { run(assets) }
    }

    /** Give it 100 ms of microphone audio (16 kHz mono 16-bit). */
    fun feed(pcm: ByteArray, mode: Listen) {
        if (!available) return
        if (!queue.offer(Chunk(pcm, mode))) { queue.clear(); Log.w(tag, "wake word fell behind; skipped audio") }
    }

    /** Forget any half-heard audio (call when going back to waiting). */
    fun clear() { queue.clear(); flush = true }

    private fun run(assets: AssetManager) {
        val kws: KeywordSpotter
        val specs = HashMap<Listen, String>()
        try {
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        // Full-precision files: the int8/"mobile" ones hang or crawl on 32-bit ARM.
                        encoder = "kws/encoder.onnx",
                        decoder = "kws/decoder.onnx",
                        joiner = "kws/joiner.onnx",
                    ),
                    tokens = "kws/tokens.txt",
                    modelType = "zipformer2",
                    numThreads = 1,
                ),
                // The real word lists are given per stream (see Listen); this base file holds only a
                // placeholder that never fires, because sherpa-onnx adds the base file to every stream.
                keywordsFile = "kws/none.txt",
                keywordsScore = MoynaConfig.WAKE_BOOST,
                keywordsThreshold = MoynaConfig.WAKE_THRESHOLD,
                numTrailingBlanks = 1,
            )
            kws = KeywordSpotter(assets, config)
            // Word lists: one per line, "#" lines are comments. Joined with "/" for sherpa-onnx.
            fun read(file: String) = assets.open(file).bufferedReader(Charsets.UTF_8).readLines()
                .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            specs[Listen.WAKE] = read(Listen.WAKE.file).joinToString("/")
            specs[Listen.WAKE_TV] = read(Listen.WAKE_TV.file).joinToString("/")
            // In a conversation also listen for "Moyna", so "Moyna ... stop" can be recognised.
            specs[Listen.COMMAND] = (read(Listen.COMMAND.file) + read(Listen.WAKE.file)).joinToString("/")
            available = true
            Log.i(tag, "wake word ready: quiet=${read(Listen.WAKE.file).size} spellings, " +
                "TV=${read(Listen.WAKE_TV.file).size}, stop words=${read(Listen.COMMAND.file).size}")
        } catch (t: Throwable) {
            Log.e(tag, "wake word could not start; tap still works", t)
            return
        }

        fun newStream(mode: Listen): OnlineStream = kws.createStream(specs[mode]!!)

        var mode = Listen.WAKE
        var stream = newStream(mode)
        val floats = FloatArray(1600)
        var statChunks = 0; var statMs = 0L; var statStart = System.currentTimeMillis()

        while (true) {
            val chunk = queue.take()
            val t0 = System.nanoTime()
            // Switching between "Moyna" and stop words (or a flush) starts a fresh stream.
            if (flush || chunk.mode != mode) {
                flush = false
                mode = chunk.mode
                stream.release(); stream = newStream(mode)
            }
            val pcm = chunk.pcm
            var i = 0; var j = 0
            while (i + 1 < pcm.size && j < floats.size) {
                floats[j++] = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort() / 32768f
                i += 2
            }
            stream.acceptWaveform(if (j == floats.size) floats else floats.copyOf(j), 16000)
            while (kws.isReady(stream)) {
                kws.decode(stream)
                val word = kws.getResult(stream).keyword
                if (word.isNotBlank()) {
                    Log.i(tag, "${if (mode == Listen.COMMAND) "COMMAND" else "WAKE"} (${mode.name}): heard \"$word\"")
                    kws.reset(stream)
                    onHeard(word, mode)
                }
            }
            statChunks++; statMs += (System.nanoTime() - t0) / 1_000_000
            if (System.currentTimeMillis() - statStart > 300_000) {   // health line every 5 minutes
                // Must stay well under 100 ms, or the detector can't keep up with live audio.
                Log.i(tag, "wake health: ${if (statChunks > 0) statMs / statChunks else 0} ms of work per 100 ms of audio ($statChunks chunks)")
                statChunks = 0; statMs = 0; statStart = System.currentTimeMillis()
            }
        }
    }
}
