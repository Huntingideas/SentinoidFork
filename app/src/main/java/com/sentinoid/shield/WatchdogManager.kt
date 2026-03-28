package com.sentinoid.shield

import android.content.Context
import android.util.Log

/**
 * Security orchestration manager that coordinates all security checks.
 */
class WatchdogManager(context: Context) {
    companion object {
        private const val TAG = "WatchdogManager"
        private const val DEFAULT_HIGH_RISK_THRESHOLD = 0.7f
        private const val DEFAULT_MEDIUM_RISK_THRESHOLD = 0.3f
    }

    private val malwareEngine: MalwareEngine = MalwareEngine.getInstance(context)

    /**
     * Scan all installed apps and return high-risk ones.
     *
     * @param threshold Risk score threshold (default 0.7)
     * @return List of (packageName, riskScore) pairs above threshold
     */
    fun scanAllApps(threshold: Float = DEFAULT_HIGH_RISK_THRESHOLD): List<Pair<String, Float>> {
        Log.d(TAG, "Starting full app scan with threshold: $threshold")
        val highRiskApps = malwareEngine.getHighRiskApps(threshold)
        Log.d(TAG, "Scan complete. Found ${highRiskApps.size} high-risk apps")
        return highRiskApps
    }

    /**
     * Check if a specific app is a threat.
     *
     * @param packageName App's package name
     * @return True if risk score > 0.7 (high risk)
     */
    fun isThreatDetected(packageName: String): Boolean {
        val riskScore = malwareEngine.analyzeApp(packageName)
        val isThreat = riskScore > DEFAULT_HIGH_RISK_THRESHOLD
        Log.d(TAG, "Threat check for $packageName: score=$riskScore, detected=$isThreat")
        return isThreat
    }

    /**
     * Get detailed risk analysis for a specific app.
     *
     * @param packageName App's package name
     * @return Risk score (0.0 - 1.0)
     */
    fun analyzeApp(packageName: String): Float {
        return malwareEngine.analyzeApp(packageName)
    }

    /**
     * Get risk category based on score.
     *
     * @param riskScore Risk score (0.0 - 1.0)
     * @return Risk category string
     */
    fun getRiskCategory(riskScore: Float): String {
        return when {
            riskScore >= DEFAULT_HIGH_RISK_THRESHOLD -> "HIGH"
            riskScore >= DEFAULT_MEDIUM_RISK_THRESHOLD -> "MEDIUM"
            else -> "LOW"
        }
    }

    /**
     * Release resources.
     */
    fun close() {
        Log.d(TAG, "WatchdogManager closed")
    }
}
