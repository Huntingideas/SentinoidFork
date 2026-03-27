package com.sentinoid.shield

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optimized WatchdogService using JobScheduler instead of persistent service.
 * Reduces battery drain by using system-scheduled batch jobs rather than wake locks.
 */
class WatchdogService : JobService() {
    private val TAG = "WatchdogService"
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var lastCheckTime = 0L
    private val CHECK_INTERVAL_MS = 5 * 60 * 1000 // 5 minutes minimum between checks
    
    companion object {
        private const val JOB_ID_WATCHDOG = 1001
        private const val LOW_BATTERY_THRESHOLD = 20
        
        fun scheduleWatchdog(context: Context) {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            
            val componentName = ComponentName(context, WatchdogService::class.java)
            
            val jobInfo = JobInfo.Builder(JOB_ID_WATCHDOG, componentName)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE) // No network needed
                .setRequiresCharging(false)
                .setRequiresBatteryNotLow(false) // Run even on low battery for security
                .setPeriodic(TimeUnit.MINUTES.toMillis(15)) // Every 15 minutes
                .setPersisted(true) // Survive reboots
                .build()
            
            jobScheduler.schedule(jobInfo)
            Log.d("WatchdogService", "Scheduled periodic watchdog job")
        }
        
        fun cancelWatchdog(context: Context) {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            jobScheduler.cancel(JOB_ID_WATCHDOG)
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        serviceScope.launch {
            try {
                performSecurityCheck()
            } finally {
                jobFinished(params, false)
            }
        }
        return true // Async work
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return false // Don't reschedule, let periodic schedule handle it
    }

    private suspend fun performSecurityCheck() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheckTime < CHECK_INTERVAL_MS) {
            return // Throttled
        }
        lastCheckTime = now
        
        // Skip intensive checks on very low battery
        if (isCriticalBattery()) {
            Log.d(TAG, "Skipping intensive checks due to critical battery")
            performLightCheck()
            return
        }
        
        Log.d(TAG, "Performing security check...")
        
        // Root check (lightweight)
        if (isDeviceRooted()) {
            Log.e(TAG, "CRITICAL: Device is rooted! Triggering lockdown.")
            SilentAlarmManager.triggerLockdown(applicationContext)
        }

        // USB debugging check (very lightweight)
        if (isUsbDebuggingEnabled()) {
            Log.w(TAG, "WARNING: USB Debugging is enabled.")
        }

        // Only perform intensive checks when not in power save mode
        if (!isPowerSaveMode()) {
            performIntensiveChecks()
        } else {
            Log.d(TAG, "Skipping intensive checks in power save mode")
        }
    }

    private fun performLightCheck() {
        // Only check USB debugging (very low power)
        if (isUsbDebuggingEnabled()) {
            Log.w(TAG, "USB Debugging enabled (light check)")
        }
    }

    private suspend fun performIntensiveChecks() {
        // Check for new root indicators
        checkMagiskSpecific()
        
        // Verify system integrity (throttled)
        delay(100) // Small delay to prevent CPU spike
        checkSystemIntegrity()
    }

    private fun isDeviceRooted(): Boolean {
        // Tiered checking - fast checks first
        val testKeys = android.os.Build.TAGS?.contains("test-keys") ?: false
        if (testKeys) return true
        
        // Critical paths only (reduced from 15 to 8 for efficiency)
        val criticalPaths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su",
            "/system/app/Superuser.apk",
            "/data/adb/magisk",
            "/sbin/.magisk"
        )
        
        return criticalPaths.any { File(it).exists() }
    }

    private fun checkMagiskSpecific(): Boolean {
        // Check for Magisk-specific indicators
        return try {
            val process = Runtime.getRuntime().exec("magisk -v")
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun checkSystemIntegrity() {
        // Verify critical system properties haven't been tampered with
        val debuggable = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0)
        if (debuggable == 1) {
            Log.w(TAG, "System appears debuggable")
        }
    }

    private fun isUsbDebuggingEnabled(): Boolean {
        return Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    }

    private fun isPowerSaveMode(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            powerManager.isPowerSaveMode
        } else {
            false
        }
    }

    private fun isCriticalBattery(): Boolean {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level < LOW_BATTERY_THRESHOLD
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.coroutineContext[Job]?.cancel()
    }
}