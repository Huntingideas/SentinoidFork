package com.sentinoid.shield

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class WatchdogManager(context: Context) {
    private var interpreter: Interpreter? = null

    init {
        try {
            interpreter = Interpreter(loadModelFile(context))
            Log.d("SENTINOID", "Watchdog TFLite initialized.")
        } catch (e: Exception) {
            Log.e("SENTINOID", "Watchdog TFLite failed to initialize.", e)
        }
    }

    fun isThreatDetected(input: FloatArray): Boolean {
        // Dummy model logic: just returns true if the first input is > 0.5
        val output = arrayOf(floatArrayOf(0.0f))
        interpreter?.run(input, output)
        val isThreat = output[0][0] > 0.5f
        Log.d("SENTINOID", "Watchdog Scan: ThreatDetected=$isThreat")
        return isThreat
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        context.assets.openFd("malware_model.tflite").use { fileDescriptor ->
            FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                val fileChannel = inputStream.channel
                return fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.startOffset,
                    fileDescriptor.declaredLength
                )
            }
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
