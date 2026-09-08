package io.legado.app.help.tts

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import com.tianscar.soundtouch.SoundTouch
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun applyTtsPcm16Gain(sample: Short, gain: Float): Short {
    return (sample * gain.coerceIn(0.1f, 2f))
        .roundToInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        .toShort()
}

internal class TtsSoundTouchAudioProcessor : BaseAudioProcessor() {

    private var playbackParameters = PlaybackParameters.DEFAULT
    @Volatile
    private var volumeGain = 1f
    private var soundTouch: SoundTouch? = null

    fun setPlaybackParameters(parameters: PlaybackParameters): PlaybackParameters {
        playbackParameters = parameters
        return parameters
    }

    fun setVolumeGain(gain: Float) {
        volumeGain = gain.coerceIn(0.1f, 2f)
    }

    fun getMediaDuration(playoutDurationUs: Long): Long {
        return (playoutDurationUs * playbackParameters.speed).toLong()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean {
        return abs(playbackParameters.speed - 1f) >= CLOSE_THRESHOLD ||
            abs(playbackParameters.pitch - 1f) >= CLOSE_THRESHOLD ||
            abs(volumeGain - 1f) >= CLOSE_THRESHOLD
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val processor = checkNotNull(soundTouch)
        val channelCount = inputAudioFormat.channelCount
        val sampleCount = inputBuffer.remaining() / BYTES_PER_SAMPLE
        if (sampleCount == 0) return

        val inputSamples = ShortArray(sampleCount)
        inputBuffer.asShortBuffer().get(inputSamples)
        inputBuffer.position(inputBuffer.position() + sampleCount * BYTES_PER_SAMPLE)
        processor.putSamples(inputSamples, 0, sampleCount / channelCount)
        drainOutput(processor, channelCount)
    }

    override fun onQueueEndOfStream() {
        val processor = soundTouch ?: return
        processor.flush()
        drainOutput(processor, inputAudioFormat.channelCount)
    }

    override fun onFlush() {
        releaseProcessor()
        if (!isActive()) return
        soundTouch = SoundTouch().apply {
            setSampleRate(inputAudioFormat.sampleRate.toLong())
            setChannels(inputAudioFormat.channelCount.toLong())
            setTempo(playbackParameters.speed)
            setPitch(playbackParameters.pitch)
            setSetting(SoundTouch.SETTING_USE_QUICKSEEK, 0)
            setSetting(SoundTouch.SETTING_USE_AA_FILTER, 1)
            setSetting(SoundTouch.SETTING_SEQUENCE_MS, SPEECH_SEQUENCE_MS)
            setSetting(SoundTouch.SETTING_SEEKWINDOW_MS, SPEECH_SEEK_WINDOW_MS)
            setSetting(SoundTouch.SETTING_OVERLAP_MS, SPEECH_OVERLAP_MS)
        }
    }

    override fun onReset() {
        releaseProcessor()
        playbackParameters = PlaybackParameters.DEFAULT
        volumeGain = 1f
    }

    private fun drainOutput(processor: SoundTouch, channelCount: Int) {
        val availableFrames = processor.numSamples().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (availableFrames == 0) return

        val outputSamples = ShortArray(availableFrames * channelCount)
        val receivedFrames = processor.receiveSamplesI16(outputSamples, 0, availableFrames)
        if (receivedFrames == 0) return

        applyVolumeGain(outputSamples, receivedFrames * channelCount)

        val outputSize = receivedFrames * channelCount * BYTES_PER_SAMPLE
        replaceOutputBuffer(outputSize).apply {
            asShortBuffer().put(outputSamples, 0, receivedFrames * channelCount)
            position(outputSize)
            flip()
        }
    }

    private fun applyVolumeGain(samples: ShortArray, sampleCount: Int) {
        val gain = volumeGain
        if (abs(gain - 1f) < CLOSE_THRESHOLD) return
        for (index in 0 until sampleCount) {
            samples[index] = applyTtsPcm16Gain(samples[index], gain)
        }
    }

    private fun releaseProcessor() {
        soundTouch?.dispose()
        soundTouch = null
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val CLOSE_THRESHOLD = 0.0001f
        const val SPEECH_SEQUENCE_MS = 40
        const val SPEECH_SEEK_WINDOW_MS = 15
        const val SPEECH_OVERLAP_MS = 8
    }
}

internal interface TtsAdjustableAudioProcessorChain : AudioProcessorChain {

    fun applyPlaybackAdjustments(params: TtsEffectivePlaybackParams): Boolean
}

internal class TtsSoundTouchAudioProcessorChain : TtsAdjustableAudioProcessorChain {

    private val processor = TtsSoundTouchAudioProcessor()
    private val processors = arrayOf<AudioProcessor>(processor)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        return processor.setPlaybackParameters(playbackParameters)
    }

    override fun applyPlaybackAdjustments(params: TtsEffectivePlaybackParams): Boolean {
        val wasActive = processor.isActive()
        processor.setVolumeGain(params.volumeGain)
        processor.setPlaybackParameters(params.playbackParameters)
        return wasActive != processor.isActive()
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long {
        return processor.getMediaDuration(playoutDuration)
    }

    override fun getSkippedOutputFrameCount(): Long = 0L
}

/**
 * Keeps multi-role playlists on Media3's format-change-safe Sonic processor while applying
 * voice-specific volume after time stretching.
 */
internal class TtsMedia3AudioProcessorChain : TtsAdjustableAudioProcessorChain {

    private val sonicProcessor = SonicAudioProcessor()
    private val gainProcessor = TtsPcmGainAudioProcessor()
    private val processors = arrayOf<AudioProcessor>(sonicProcessor, gainProcessor)
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var volumeGain = 1f
    @Volatile
    private var pendingVolumeGain = 1f

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        this.playbackParameters = playbackParameters
        volumeGain = pendingVolumeGain
        gainProcessor.setVolumeGain(volumeGain)
        sonicProcessor.setSpeed(playbackParameters.speed)
        sonicProcessor.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applyPlaybackAdjustments(params: TtsEffectivePlaybackParams): Boolean {
        val wasSonicActive = isSonicLogicallyActive()
        val wasGainActive = isGainLogicallyActive()
        setVoiceVolumeGain(params.volumeGain)
        applyPlaybackParameters(params.playbackParameters)
        return wasSonicActive != isSonicLogicallyActive() ||
            wasGainActive != isGainLogicallyActive()
    }

    fun setVoiceVolumeGain(gain: Float) {
        pendingVolumeGain = gain.coerceIn(0.1f, 2f)
        volumeGain = pendingVolumeGain
        gainProcessor.setVolumeGain(volumeGain)
    }

    fun queueVoiceVolumeGain(gain: Float) {
        pendingVolumeGain = gain.coerceIn(0.1f, 2f)
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long {
        return sonicProcessor.getMediaDuration(playoutDuration)
    }

    override fun getSkippedOutputFrameCount(): Long = 0L

    private fun isSonicLogicallyActive(): Boolean {
        return abs(playbackParameters.speed - 1f) >= CLOSE_THRESHOLD ||
            abs(playbackParameters.pitch - 1f) >= CLOSE_THRESHOLD
    }

    private fun isGainLogicallyActive(): Boolean =
        abs(volumeGain - 1f) >= CLOSE_THRESHOLD
}

internal class TtsPcmGainAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var volumeGain = 1f

    fun setVolumeGain(gain: Float) {
        volumeGain = gain.coerceIn(0.1f, 2f)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    // The multi-role player changes gain at stream boundaries. This processor is intentionally
    // always present so crossing 1.0 never changes the AudioSink topology. It is PCM pass-through
    // while gain is neutral.
    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        val sampleCount = inputBuffer.remaining() / BYTES_PER_SAMPLE
        if (sampleCount == 0) return

        if (abs(volumeGain - 1f) < CLOSE_THRESHOLD) {
            val outputSize = inputBuffer.remaining()
            replaceOutputBuffer(outputSize).apply {
                put(inputBuffer)
                flip()
            }
            return
        }

        val inputSamples = ShortArray(sampleCount)
        inputBuffer.asShortBuffer().get(inputSamples)
        inputBuffer.position(inputBuffer.position() + sampleCount * BYTES_PER_SAMPLE)
        for (index in inputSamples.indices) {
            inputSamples[index] = applyTtsPcm16Gain(inputSamples[index], volumeGain)
        }

        val outputSize = sampleCount * BYTES_PER_SAMPLE
        replaceOutputBuffer(outputSize).apply {
            asShortBuffer().put(inputSamples)
            position(outputSize)
            flip()
        }
    }

    override fun onReset() {
        volumeGain = 1f
    }
}

private const val BYTES_PER_SAMPLE = 2
private const val CLOSE_THRESHOLD = 0.0001f
