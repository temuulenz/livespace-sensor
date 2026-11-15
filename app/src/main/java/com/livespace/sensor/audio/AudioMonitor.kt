package com.livespace.sensor.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/** One owner thread creates, polls, processes and releases AudioRecord. No raw audio leaves this class. */
class AudioMonitor(private val config: NoiseConfig,
    private val status: (MicrophoneStatus) -> Unit, private val reading: (NoiseReading) -> Unit) {
    private val active = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null
    @Volatile private var onReleased:(()->Unit)?=null
    fun start() {
        if (!active.compareAndSet(false,true)) return
        thread = Thread({ capture() },"LiveSpaceAudio").also { it.start() }
    }
    @SuppressLint("MissingPermission") // Activity checks/requests RECORD_AUDIO; revocation is still caught here.
    private fun open(): Pair<AudioRecord,MicrophoneStatus> {
        val failures = ArrayList<String>()
        for (rate in intArrayOf(16000,44100,48000,8000)) {
            val min = AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) { failures.add("$rate Hz minimum buffer error $min"); continue }
            val bytes = (maxOf(min*2,rate)+1)/2*2 // >= two hardware minimums and 500 ms PCM16; aligned to 2 bytes.
            for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION,MediaRecorder.AudioSource.MIC)) {
                if (!active.get()) throw InterruptedException()
                var candidate: AudioRecord? = null
                try {
                    candidate = AudioRecord(source,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,bytes)
                    check(candidate.state == AudioRecord.STATE_INITIALIZED) { "Uninitialized recorder" }
                    candidate.startRecording()
                    check(candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Recorder did not start" }
                    val name = if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) "VOICE_RECOGNITION" else "MIC (processed fallback)"
                    return candidate to MicrophoneStatus("● Microphone running",candidate.sampleRate,bytes,name)
                } catch (e: Exception) {
                    candidate?.release()
                    if (e is SecurityException) throw e
                    failures.add("$rate/$source: ${e.message}")
                }
            }
        }
        error("No supported mono PCM16 capture: ${failures.joinToString()}")
    }
    private fun capture() {
        var recorder: AudioRecord? = null
        var pcm: ShortArray? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            status(MicrophoneStatus("◌ Initializing microphone"))
            val (opened,details) = open(); recorder = opened
            if (!active.get()) return
            val rate = opened.sampleRate
            pcm = ShortArray(rate/10) // At most 100 ms raw PCM, reused; never stored or logged.
            val processor = NoiseProcessor(rate,config)
            status(details)
            Log.i("LiveSpaceAudio","START rate=$rate bufferBytes=${details.bufferBytes} source=${details.source}")
            var lastData = SystemClock.elapsedRealtime()
            while (active.get()) {
                val size = opened.read(pcm,0,pcm.size,AudioRecord.READ_NON_BLOCKING) // API 23: bounded stop latency.
                if (size < 0) error("AudioRecord.read failed: $size (invalid operation/bad value/dead object)")
                if (size > 0) {
                    lastData = SystemClock.elapsedRealtime()
                    processor.accept(pcm,size,System.currentTimeMillis(),lastData) { if (active.get()) reading(it) }
                } else if (SystemClock.elapsedRealtime()-lastData > 3000) error("Microphone produced no samples for 3 seconds")
                Thread.sleep(20)
            }
        } catch (_: InterruptedException) { /* Explicit stop interrupts only this worker's short polling wait. */ }
        catch (e: Exception) {
            if (active.get()) {
                status(MicrophoneStatus("⚠ Microphone error: ${e.javaClass.simpleName}: ${e.message}"))
                Log.e("LiveSpaceAudio","Capture failed",e)
            }
        } finally {
            active.set(false)
            try { if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            catch (e: Exception) { Log.w("LiveSpaceAudio","Stop failed: ${e.message}") }
            recorder?.release()
            pcm?.fill(0)
            Log.i("LiveSpaceAudio","RELEASED")
            onReleased?.invoke();onReleased=null;thread=null
        }
    }
    fun close(afterRelease:(()->Unit)?=null) {
        onReleased=afterRelease
        active.set(false)
        val owner=thread
        if(owner==null) { onReleased?.invoke();onReleased=null } else owner.interrupt()
    } // No blocking join or release racing a read on the UI thread.
}
