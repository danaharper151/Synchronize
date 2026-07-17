/*
// FILE DISABLED — requires Java-WebSocket library
// Re-enable when deploying to real device
package com.synchronize.app.util

import android.util.Log
import com.google.gson.Gson
import com.synchronize.app.data.db.entity.BiosignalSample
import com.synchronize.app.data.db.entity.GlucoseReading
import com.synchronize.app.data.db.entity.KetoneReading
import com.synchronize.app.data.db.entity.MealEntry
import com.synchronize.app.data.db.entity.SleepSession
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

class DashboardWebSocketServer(port: Int = 8080) :
    WebSocketServer(InetSocketAddress(port)) {

    private val TAG = "WebSocketServer"
    private val gson = Gson()
    private val connectedClients = mutableSetOf<WebSocket>()

    // ── Server lifecycle ──────────────────────────────────────

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        connectedClients.add(conn)
        Log.d(TAG, "Client connected: ${conn.remoteSocketAddress}")
        // Send a welcome ping
        sendToClient(conn, "status", mapOf("message" to "Connected to Synchronize"))
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        connectedClients.remove(conn)
        Log.d(TAG, "Client disconnected: ${conn.remoteSocketAddress}")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        Log.d(TAG, "Message from client: $message")
        // Handle client requests (e.g. request full data refresh)
        try {
            val msg = gson.fromJson(message, Map::class.java)
            when (msg["type"]) {
                "ping" -> sendToClient(conn, "pong", mapOf("time" to System.currentTimeMillis()))
                "requestAll" -> {
                    // Client is requesting a full data push — handled by ViewModel
                    Log.d(TAG, "Client requested full data refresh")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocket error", ex)
    }

    override fun onStart() {
        Log.d(TAG, "WebSocket server started on port ${address.port}")
        connectionLostTimeout = 30
    }

    // ── Broadcast functions ───────────────────────────────────

    fun broadcastGlucose(readings: List<GlucoseReading>) {
        if (connectedClients.isEmpty()) return
        val payload = readings.takeLast(288).map { r ->  // last 24 hours (288 × 5min)
            mapOf(
                "time"      to formatTime(r.deviceTimestamp),
                "timestamp" to r.deviceTimestamp,
                "glucose"   to r.glucoseMgDl,
                "trend"     to r.trend,
                "trendRate" to r.trendRate
            )
        }
        broadcast("glucose", payload)
    }

    fun broadcastKetones(readings: List<KetoneReading>) {
        if (connectedClients.isEmpty()) return
        val payload = readings.takeLast(10).map { r ->
            mapOf(
                "time"      to formatTime(r.deviceTimestamp),
                "timestamp" to r.deviceTimestamp,
                "bhb"       to "%.2f".format(r.betaHydroxybutyrate),
                "glucose"   to r.glucoseMgDl,
                "context"   to r.readingContext,
                "source"    to r.source
            )
        }
        broadcast("ketones", payload)
    }

    fun broadcastBiosignals(samples: List<BiosignalSample>) {
        if (connectedClients.isEmpty()) return
        val payload = samples.takeLast(288).map { s ->
            mapOf(
                "time"        to formatTime(s.deviceTimestamp),
                "timestamp"   to s.deviceTimestamp,
                "heartRate"   to s.heartRateBpm,
                "hrv"         to s.hrvRmssd,
                "spo2"        to s.spo2Percent,
                "skinTemp"    to s.skinTempCelsius,
                "systolic"    to s.systolicMmHg,
                "diastolic"   to s.diastolicMmHg,
                "stepCount"   to s.stepCount,
                "exercise"    to s.exerciseType,
                "isExercise"  to s.isExerciseSession
            )
        }
        broadcast("biosignals", payload)
    }

    fun broadcastMeals(entries: List<MealEntry>) {
        if (connectedClients.isEmpty()) return
        val payload = entries.takeLast(20).map { m ->
            mapOf(
                "time"           to formatTime(m.deviceTimestamp),
                "timestamp"      to m.deviceTimestamp,
                "calories"       to m.calories,
                "fat"            to m.fatGrams,
                "protein"        to m.proteinGrams,
                "netCarbs"       to m.netCarbGrams,
                "fiber"          to m.fiberGrams,
                "ketogenicRatio" to "%.2f".format(m.ketogenicRatio),
                "label"          to m.mealLabel,
                "transcription"  to m.rawTranscription
            )
        }
        broadcast("meals", payload)
    }

    fun broadcastSleep(sessions: List<SleepSession>) {
        if (connectedClients.isEmpty()) return
        val payload = sessions.takeLast(7).map { s ->
            mapOf(
                "time"              to formatTime(s.sleepStartTimestamp),
                "timestamp"         to s.sleepStartTimestamp,
                "totalMinutes"      to s.totalDurationMinutes,
                "deepPercent"       to s.deepSleepPercent,
                "remPercent"        to s.remSleepPercent,
                "lightPercent"      to s.lightSleepPercent,
                "awakePercent"      to s.awakePercent,
                "meanRmssd"         to s.meanOvernightRmssd,
                "skinTempDelta"     to s.skinTempDeltaCelsius,
                "sleepScore"        to s.sleepScore
            )
        }
        broadcast("sleep", payload)
    }

    fun broadcastDataQuality(quality: Map<String, Int>) {
        if (connectedClients.isEmpty()) return
        broadcast("quality", quality)
    }

    // ── Helper functions ──────────────────────────────────────

    private fun broadcast(type: String, data: Any) {
        val json = gson.toJson(mapOf("type" to type, "data" to data))
        try {
            broadcast(json)
            Log.d(TAG, "Broadcast [$type] to ${connectedClients.size} client(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast error", e)
        }
    }

    private fun sendToClient(conn: WebSocket, type: String, data: Any) {
        val json = gson.toJson(mapOf("type" to type, "data" to data))
        try {
            conn.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
        }
    }

    private fun formatTime(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    fun clientCount() = connectedClients.size
    fun isRunning() = connectedClients.isNotEmpty()
}*/
