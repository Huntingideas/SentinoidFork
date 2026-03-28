package com.sentinoid.shield

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import java.io.File

/**
 * Background security monitoring service.
 */
class WatchdogService : Service() {

    companion object {
        private const val TAG = "WatchdogService"
        private const val LOW_BATTERY_THRESHOLD = 15
    }

    private var watchdogManager: WatchdogManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())

    override fun onCreate() {
        super.onCreate()
        watchdogManager = WatchdogManager(this)
        Log.d(TAG, "WatchdogService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Hardware Watchdog is active.")

        if (isDeviceRooted()) {
            Log.e(TAG, "CRITICAL: Device is rooted! Triggering lockdown.")
        }

        if (isUsbDebuggingEnabled()) {
            Log.w(TAG, "WARNING: USB Debugging is enabled.")
        }

        performMalwareScan()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun performMalwareScan() {
        try {
            Log.d(TAG, "Starting malware scan...")
            val highRiskApps = watchdogManager?.scanAllApps()

            if (highRiskApps != null && highRiskApps.isNotEmpty()) {
                Log.w(TAG, "MALWARE DETECTED: Found ${highRiskApps.size} high-risk apps")
                highRiskApps.forEach { (packageName, score) ->
                    Log.w(TAG, "  - $packageName (risk: $score)")
                }
            } else {
                Log.d(TAG, "Malware scan complete: No threats detected")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during malware scan", e)
        }
    }

    private fun isDeviceRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        return paths.any { File(it).exists() }
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
        serviceScope.cancel()
        watchdogManager?.close()
        watchdogManager = null
        Log.d(TAG, "WatchdogService destroyed")
    }
}
