package com.sentinoid.shield

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.Intent
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Battery-optimized Honeypot using FileObserver with adaptive monitoring.
 */
class HoneypotEngine : JobService() {
    private var observer: AdaptiveFileObserver? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private val isMonitoring = AtomicBoolean(false)
    private var lastTriggerTime = 0L
    private val triggerCooldownMs = 30000L // 30 seconds between triggers

    companion object {
        private const val TAG = "HoneypotEngine"
        private const val JOB_ID_HONEYPOT = 1002
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        startMonitoring()
        return true // Keep the service running
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        stopMonitoring()
        return true // Reschedule if needed
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startMonitoring()
        return START_STICKY
    }

    private fun startMonitoring() {
        if (isMonitoring.getAndSet(true)) return

        handlerThread =
            HandlerThread("HoneypotThread", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply {
                start()
                handler = Handler(looper)
            }

        val honeypotDir = createHoneypotDirectory()
        if (honeypotDir != null && handler != null) {
            observer = AdaptiveFileObserver(honeypotDir.path, handler!!)
            observer?.startWatching()
            Log.d(TAG, "Adaptive honeypot monitoring started at ${honeypotDir.path}")
        }
    }

    private fun stopMonitoring() {
        if (!isMonitoring.getAndSet(false)) return

        observer?.stopWatching()
        observer = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        Log.d(TAG, "Honeypot monitoring stopped")
    }

    private fun createHoneypotDirectory(): File? {
        val externalDir = getExternalFilesDir(null)
        if (externalDir != null) {
            val trapDir = File(externalDir, ".sentinoid_vault")
            if (!trapDir.exists()) {
                trapDir.mkdirs()
            }
            File(trapDir, "vault_keys.txt").apply {
                if (!exists()) {
                    writeText("This is a honeypot. Accessing this file has triggered a silent alarm.")
                }
            }
            return trapDir
        }
        return null
    }

    private fun onHoneypotTripped(path: String?) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < triggerCooldownMs) {
            Log.d(TAG, "Honeypot trigger throttled")
            return
        }
        lastTriggerTime = now

        Log.e(TAG, "HONEYPOT TRIPPED! File accessed: $path")

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isPowerSaveMode) {
            SilentAlarmManager.triggerLockdown(applicationContext)
        } else {
            Log.w(TAG, "Honeypot tripped but in power save - deferred lockdown")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
    }

    private inner class AdaptiveFileObserver(path: String, private val handler: Handler) :
        FileObserver(path, OPEN or ACCESS or MODIFY) {
        private var eventCount = 0
        private var adaptiveDelay = 0L

        override fun onEvent(
            event: Int,
            path: String?,
        ) {
            eventCount++

            if (eventCount > 10) {
                adaptiveDelay = 100
            }

            if (adaptiveDelay > 0) {
                handler.postDelayed({ processEvent(event, path) }, adaptiveDelay)
            } else {
                processEvent(event, path)
            }
        }

        private fun processEvent(
            event: Int,
            path: String?,
        ) {
            if (path == "vault_keys.txt") {
                onHoneypotTripped(path)
            }
        }
    }
}
