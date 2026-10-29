package com.timer.core.network

import com.timer.core.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TimerApiClient(
    private var baseUrl: String = "https://timer-hutq.onrender.com"
) {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(this@TimerApiClient.json)
        }
        install(WebSockets) {
            pingInterval = 15_000
        }
    }

    fun updateBaseUrl(newUrl: String) {
        baseUrl = newUrl.trimEnd('/')
    }

    fun getBaseUrl(): String = baseUrl

    // 1. Health check
    suspend fun checkHealth(): Boolean {
        return try {
            val response = httpClient.get("$baseUrl/health")
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    // 2. Generate Pairing Code (CCTV)
    suspend fun generatePairingCode(cctvDeviceId: String): Result<PairingGenerateResponse> {
        return runCatching {
            val response = httpClient.post("$baseUrl/api/pairing/generate") {
                contentType(ContentType.Application.Json)
                setBody(PairingGenerateRequest(cctvDeviceId))
            }
            if (!response.status.isSuccess()) {
                val err = runCatching { response.body<Map<String, String>>()["error"] }.getOrNull()
                throw Exception(err ?: "Failed to generate pairing code")
            }
            response.body()
        }
    }

    // 3. Submit Pairing Code (Admin)
    suspend fun submitPairing(adminDeviceId: String, code: String): Result<PairingSubmitResponse> {
        return runCatching {
            val response = httpClient.post("$baseUrl/api/pairing/pair") {
                contentType(ContentType.Application.Json)
                setBody(PairingSubmitRequest(adminDeviceId, code))
            }
            if (!response.status.isSuccess()) {
                val err = runCatching { response.body<Map<String, String>>()["error"] }.getOrNull()
                throw Exception(err ?: "Invalid pairing code or request rejected")
            }
            val body = response.body<PairingSubmitResponse>()
            if (!body.success) {
                throw Exception(body.error ?: "Pairing rejected")
            }
            body
        }
    }

    // 4. Unpair
    suspend fun unpair(deviceId: String): Result<Boolean> {
        return runCatching {
            val response = httpClient.post("$baseUrl/api/pairing/unpair") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("deviceId" to deviceId))
            }
            response.status.isSuccess()
        }
    }

    // 5. Send Heartbeat (CCTV)
    suspend fun sendHeartbeat(deviceId: String, health: DeviceHealth): Result<HeartbeatResponse> {
        return runCatching {
            val response = httpClient.post("$baseUrl/api/heartbeat") {
                contentType(ContentType.Application.Json)
                setBody(HeartbeatRequest(deviceId, health))
            }
            if (!response.status.isSuccess()) {
                throw Exception("Heartbeat failed: ${response.status.value}")
            }
            response.body()
        }
    }

    // 6. Get Paired CCTV Status (Admin)
    suspend fun getPairedDeviceStatus(adminDeviceId: String): Result<PairedDeviceStatusResponse> {
        return runCatching {
            val response = httpClient.get("$baseUrl/api/device/$adminDeviceId/status")
            if (!response.status.isSuccess()) {
                val err = runCatching { response.body<Map<String, String>>()["error"] }.getOrNull()
                throw Exception(err ?: "Device status unavailable")
            }
            response.body()
        }
    }

    // 7. Start Session (Admin)
    suspend fun startSession(adminDeviceId: String, mode: SessionMode): Result<StartSessionResponse> {
        return runCatching {
            val response = httpClient.post("$baseUrl/api/session/start") {
                contentType(ContentType.Application.Json)
                setBody(StartSessionRequest(adminDeviceId, mode))
            }
            if (!response.status.isSuccess()) {
                val err = runCatching { response.body<Map<String, String>>()["error"] }.getOrNull()
                throw Exception(err ?: "Cannot start session: ${response.status.description}")
            }
            val body = response.body<StartSessionResponse>()
            if (!body.success) {
                throw Exception(body.error ?: "Session start rejected")
            }
            body
        }
    }

    // 8. Stop Session (Admin or CCTV)
    suspend fun stopSession(deviceId: String): Result<Boolean> {
        return runCatching {
            val response = httpClient.post("$baseUrl/api/session/stop") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("deviceId" to deviceId))
            }
            response.status.isSuccess()
        }
    }

    // 9. Send Command (Admin)
    suspend fun sendCommand(request: CommandRequest): Result<Boolean> {
        return runCatching {
            val response = httpClient.post("$baseUrl/api/command") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            response.status.isSuccess()
        }
    }

    // Sign payload with HMAC-SHA256
    fun createHmacSignature(payload: String, timestamp: Long, sharedSecret: String): String {
        val message = "$payload:$timestamp"
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(sharedSecret.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        val hash = mac.doFinal(message.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
