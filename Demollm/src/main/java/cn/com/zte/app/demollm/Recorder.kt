package cn.com.zte.app.demollm

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class Recorder {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    private suspend fun getAudioRecord(): AudioRecord {
        return withContext(Dispatchers.IO){
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
        }
    }

    suspend fun start(): ByteArray {
        if (isRecording) {
            throw IllegalStateException("Already recording")
        }

        val record = getAudioRecord()
        audioRecord = record

        return withContext(Dispatchers.IO) {
            record.startRecording()
            isRecording = true

            val buffer = ByteArray(record.bufferSizeInFrames)
            val outputStream = ByteArrayOutputStream()

            while (isRecording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    outputStream.write(buffer, 0, read)
                }
            }

            record.stop()
            record.release()
            audioRecord = null

            outputStream.toByteArray()
        }
    }

    fun stop() {
        isRecording = false
    }

    fun convertPcm16leToFloat32(pcm16le: ByteArray): FloatArray {
        val nSamples = pcm16le.size / 2
        val float32 = FloatArray(nSamples)

        for (i in 0 until nSamples) {
            var sample = pcm16le[2 * i].toInt() and 0xFF or (pcm16le[2 * i + 1].toInt() shl 8)
            if (sample > 32767) {
                sample -= 65536
            }
            float32[i] = sample / 32768.0f
        }

        return float32
    }
}
