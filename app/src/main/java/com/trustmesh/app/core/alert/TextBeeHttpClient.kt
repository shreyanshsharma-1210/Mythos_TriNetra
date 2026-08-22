package com.trustmesh.app.core.alert

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Interface for TextBee HTTP transport to enable unit testing without live network calls.
 */
interface TextBeeHttpClient {
    suspend fun sendSms(
        apiKey: String,
        deviceId: String,
        recipients: List<String>,
        message: String,
        simSlot: Int? = null
    ): Result<Unit>
}

/**
 * Production implementation of TextBeeHttpClient using standard HttpsURLConnection.
 */
class DefaultTextBeeHttpClient : TextBeeHttpClient {
    companion object {
        private const val TAG = "TextBeeHttpClient"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000
    }

    override suspend fun sendSms(
        apiKey: String,
        deviceId: String,
        recipients: List<String>,
        message: String,
        simSlot: Int?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val requestJson = JSONObject().apply {
                put("deviceId", deviceId)
                val recipientsArray = JSONArray().apply {
                    recipients.forEach { put(it.trim().replace(" ", "")) }
                }
                put("recipients", recipientsArray)
                put("message", message)
                val effectiveSimSlot = simSlot ?: FamilyAlertConfig.getSimSlot()
                if (effectiveSimSlot >= 0) {
                    put("simSlot", effectiveSimSlot)
                    put("simSubscriptionId", effectiveSimSlot)
                }
            }

            val url = URL(FamilyAlertConfig.TEXTBEE_ENDPOINT)
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("User-Agent", "TriNetra-Android/1.0")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(requestJson.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val responseText = connection.inputStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                Log.i(TAG, "TextBee SMS sent successfully (HTTP $responseCode): $responseText")
                Result.success(Unit)
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                // DO NOT log apiKey! Log error stream safely
                Log.e(TAG, "TextBee API error HTTP $responseCode: $errorStream")
                Result.failure(RuntimeException("TextBee API error HTTP $responseCode: $errorStream"))
            }
        } catch (e: Exception) {
            // DO NOT log apiKey!
            Log.e(TAG, "TextBee SMS request exception: ${e.message}", e)
            Result.failure(e)
        }
    }
}
