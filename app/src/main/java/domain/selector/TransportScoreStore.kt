package com.furkn.vpn.domain.selector

import kotlin.math.min
import android.util.Log
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

data class TransportStats(
    val successCount: Int = 0,
    val failCount: Int = 0,
    val lastLatencyMs: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastFailAt: Long? = null
)

class TransportScoreStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("transport_score_store", Context.MODE_PRIVATE)

    private val statsMap = ConcurrentHashMap<String, TransportStats>()

    init {
        loadFromPrefs()
    }

    fun getStats(transportCode: String): TransportStats {
        return statsMap[transportCode] ?: TransportStats()
    }

    fun markSuccess(transportCode: String, latencyMs: Long?) {
        val now = System.currentTimeMillis()
        val current = getStats(transportCode)

        statsMap[transportCode] = current.copy(
            successCount = current.successCount + 1,
            lastLatencyMs = latencyMs ?: current.lastLatencyMs,
            lastSuccessAt = now
        )

        saveToPrefs()
    }

    fun markFailure(transportCode: String) {
        val now = System.currentTimeMillis()
        val current = getStats(transportCode)

        statsMap[transportCode] = current.copy(
            failCount = current.failCount + 1,
            lastFailAt = now
        )

        saveToPrefs()
    }

    fun updateLatency(transportCode: String, latencyMs: Long) {
        val current = getStats(transportCode)

        statsMap[transportCode] = current.copy(
            lastLatencyMs = latencyMs
        )

        saveToPrefs()
    }

    fun getScore(transportCode: String): Int {
        val s = getStats(transportCode)
        val now = System.currentTimeMillis()

        val cappedSuccessCount = min(s.successCount, 5)

        val successDecayMultiplier = when {
            s.lastSuccessAt == null -> 0.3
            now - s.lastSuccessAt < 10 * 60_000 -> 1.0
            now - s.lastSuccessAt < 60 * 60_000 -> 0.7
            now - s.lastSuccessAt < 6 * 60 * 60_000 -> 0.4
            else -> 0.2
        }

        val successPart = (cappedSuccessCount * 100 * successDecayMultiplier).toInt()
        val failPart = s.failCount * 120

        val latencyPenalty = when {
            s.lastLatencyMs == null -> 40
            s.lastLatencyMs <= 300 -> 0
            s.lastLatencyMs <= 800 -> 30
            s.lastLatencyMs <= 1500 -> 80
            else -> 150
        }

        val millisSinceFail = if (s.lastFailAt != null) now - s.lastFailAt else Long.MAX_VALUE

        val recentFailurePenalty = when {
            millisSinceFail < 30_000 -> 500
            millisSinceFail < 180_000 -> 250
            millisSinceFail < 600_000 -> 100
            else -> 0
        }

        return max(
            0,
            successPart - failPart - latencyPenalty - recentFailurePenalty
        )
    }

    fun debugSnapshot(): Map<String, TransportStats> {
        return statsMap.toMap()
    }

    private fun saveToPrefs() {
        val root = JSONObject()

        statsMap.forEach { (transportCode, stats) ->
            val item = JSONObject()
                .put("successCount", stats.successCount)
                .put("failCount", stats.failCount)
                .put("lastLatencyMs", stats.lastLatencyMs ?: JSONObject.NULL)
                .put("lastSuccessAt", stats.lastSuccessAt ?: JSONObject.NULL)
                .put("lastFailAt", stats.lastFailAt ?: JSONObject.NULL)

            root.put(transportCode, item)
        }

        val json = root.toString()

        val saved = prefs.edit()
            .putString("stats_json", json)
            .commit()

        Log.d(
            "VPN_SELECTOR",
            "PREFS_SAVE success=$saved json=$json"
        )
    }

    private fun loadFromPrefs() {
        val raw = prefs.getString("stats_json", null)

        Log.d("VPN_SELECTOR", "PREFS_LOAD raw=${raw ?: "null"}")

        if (raw.isNullOrBlank()) {
            Log.d("VPN_SELECTOR", "PREFS_LOAD empty")
            return
        }

        runCatching {
            val root = JSONObject(raw)
            val keys = root.keys()

            while (keys.hasNext()) {
                val transportCode = keys.next()
                val item = root.getJSONObject(transportCode)

                statsMap[transportCode] = TransportStats(
                    successCount = item.optInt("successCount", 0),
                    failCount = item.optInt("failCount", 0),
                    lastLatencyMs = if (item.isNull("lastLatencyMs")) null else item.optLong("lastLatencyMs"),
                    lastSuccessAt = if (item.isNull("lastSuccessAt")) null else item.optLong("lastSuccessAt"),
                    lastFailAt = if (item.isNull("lastFailAt")) null else item.optLong("lastFailAt")
                )
            }

            Log.d("VPN_SELECTOR", "PREFS_LOAD snapshot=${statsMap.toMap()}")
        }.onFailure { e ->
            Log.e("VPN_SELECTOR", "PREFS_LOAD_ERROR ${e.message}", e)
        }
    }

    fun clearAll() {
        statsMap.clear()
        prefs.edit().remove("stats_json").apply()
    }
}