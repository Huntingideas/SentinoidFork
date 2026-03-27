package com.sentinoid.shield

import android.content.Context
import android.os.PowerManager

/**
 * Centralized lockdown trigger with battery-aware throttling.
 * Ensures security responses don't drain battery during repeated triggers.
 */
object SilentAlarmManager {
    private const val TAG = "SilentAlarmManager"
    private const val LOCKDOWN_COOLDOWN_MS = 5000L // Prevent spam
    private var lastLockdownTime = 0L
    private var isLockedDown = false
    
    @Volatile
    private var lockdownCallback: (() -> Unit)? = null
    
    fun setLockdownCallback(callback: () -> Unit) {
        lockdownCallback = callback
    }
    
    /**
     * Triggers app lockdown with cooldown protection.
     * Prevents rapid-fire lockdowns that drain battery.
     */
    fun triggerLockdown(context: Context? = null) {
        val now = System.currentTimeMillis()
        if (now - lastLockdownTime < LOCKDOWN_COOLDOWN_MS) {
            return // Throttled
        }
        lastLockdownTime = now
        isLockedDown = true
        
        // Acquire partial wake lock briefly for emergency operations
        context?.let { ctx ->
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Sentinoid::EmergencyLockdown"
            )
            wakeLock.acquire(1000) // 1 second max
            
            try {
                lockdownCallback?.invoke()
                // TODO: Purge sensitive data, close connections
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        } ?: run {
            lockdownCallback?.invoke()
        }
    }
    
    fun isInLockdown(): Boolean = isLockedDown
    
    fun resetLockdown() {
        isLockedDown = false
    }
}
