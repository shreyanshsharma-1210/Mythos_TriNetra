package com.trustmesh.app.core.intelligence.groq

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object GroqIntelligenceClient {
    private const val TAG = "GroqIntelligenceClient"
    private const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 5000

    private const val SYSTEM_PROMPT = """
You are TrustMesh L3-L5 Security Intelligence, an expert AI threat classifier for mobile social engineering, financial fraud, and cyber security attacks.
Your job is to analyze incoming text, SMS, notification content, or call context and determine if it represents a threat.

Return ONLY a valid JSON object matching this exact schema:
{
  "isScam": boolean,
  "scamCategory": "FINANCIAL_FRAUD" | "OTP_THEFT" | "GOVERNMENT_IMPERSONATION" | "REMOTE_ACCESS" | "PARCEL_SCAM" | "UTILITY_SCAM" | "BENIGN" | "OTHER",
  "riskScore": integer (0 to 100),
  "confidence": "HIGH" | "MEDIUM" | "LOW",
  "psychologicalTriggers": array of strings (e.g. ["URGENCY", "AUTHORITY_FEAR", "FINANCIAL_COERCION", "PANIC"]),
  "summaryReasoning": string (concise explanation under 25 words),
  "keySuspiciousPhrases": array of strings
}
Do not wrap in markdown or code blocks. Output raw JSON only.
"""

    suspend fun analyzeContent(
        context: Context?,
        title: String,
        text: String,
        callerIdentity: String? = null,
        recentTimeline: List<String> = emptyList()
    ): GroqAnalysisResponse = withContext(Dispatchers.IO) {
        val apiKey = GroqConfig.getApiKey(context)

        // If no API key is provided, provide deterministic fallback or local mock evaluation
        if (apiKey.isBlank()) {
            Log.w(TAG, "Groq API key not configured — using offline heuristic evaluation")
            return@withContext performOfflineHeuristic(title, text, callerIdentity)
        }

        try {
            val model = GroqConfig.getModel(context)
            val userContent = buildString {
                append("Analyze this incoming mobile event for security risks:\n")
                if (callerIdentity != null) append("Caller/Sender Identity: $callerIdentity\n")
                if (title.isNotBlank()) append("Title/App: $title\n")
                if (text.isNotBlank()) append("Content Text: $text\n")
                if (recentTimeline.isNotEmpty()) {
                    append("Recent Related Timeline Events:\n")
                    recentTimeline.takeLast(3).forEach { append("- $it\n") }
                }
            }

            val requestJson = JSONObject().apply {
                put("model", model)
                put("response_format", JSONObject().put("type", "json_object"))
                put("temperature", 0.1)

                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT.trim())
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userContent)
                    })
                }
                put("messages", messages)
            }

            val url = URL(GROQ_ENDPOINT)
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(requestJson.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpsURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                val jsonResponse = JSONObject(responseText)
                val choices = jsonResponse.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val message = choices.getJSONObject(0).optJSONObject("message")
                    val content = message?.optString("content", "") ?: ""
                    return@withContext parseGroqOutput(content)
                }
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                Log.e(TAG, "Groq API error $responseCode: $errorStream")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Groq intelligence request failed: ${e.message}", e)
        }

        return@withContext performOfflineHeuristic(title, text, callerIdentity)
    }

    private fun parseGroqOutput(content: String): GroqAnalysisResponse {
        return try {
            val json = JSONObject(content.trim())
            val triggersList = mutableListOf<String>()
            val triggersArr = json.optJSONArray("psychologicalTriggers")
            if (triggersArr != null) {
                for (i in 0 until triggersArr.length()) {
                    triggersList.add(triggersArr.getString(i))
                }
            }

            val phrasesList = mutableListOf<String>()
            val phrasesArr = json.optJSONArray("keySuspiciousPhrases")
            if (phrasesArr != null) {
                for (i in 0 until phrasesArr.length()) {
                    phrasesList.add(phrasesArr.getString(i))
                }
            }

            GroqAnalysisResponse(
                isScam = json.optBoolean("isScam", false),
                scamCategory = json.optString("scamCategory", "UNKNOWN"),
                riskScore = json.optInt("riskScore", 0),
                confidence = json.optString("confidence", "MEDIUM"),
                psychologicalTriggers = triggersList,
                summaryReasoning = json.optString("summaryReasoning", "Groq semantic evaluation complete"),
                keySuspiciousPhrases = phrasesList,
                rawJson = content
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Groq response JSON: $content", e)
            GroqAnalysisResponse.fallback("Failed to parse Groq JSON response")
        }
    }

    /**
     * Local heuristic evaluation fallback if Groq API key is unconfigured or network is unavailable.
     */
    private fun performOfflineHeuristic(title: String, text: String, callerIdentity: String?): GroqAnalysisResponse {
        val lowerText = (title + " " + text).lowercase()
        val triggers = mutableListOf<String>()
        var isScam = false
        var category = "BENIGN"
        var score = 0

        if (lowerText.contains("urgent") || lowerText.contains("immediate") || lowerText.contains("blocked") || lowerText.contains("suspended")) {
            triggers.add("URGENCY")
            score += 20
        }
        if (lowerText.contains("bank") || lowerText.contains("account") || lowerText.contains("transfer") || lowerText.contains("card")) {
            triggers.add("FINANCIAL_COERCION")
            category = "FINANCIAL_FRAUD"
            score += 30
        }
        if (lowerText.contains("otp") || lowerText.contains("verification") || lowerText.contains("code")) {
            triggers.add("CREDENTIAL_HARVESTING")
            category = "OTP_THEFT"
            score += 35
        }
        if (lowerText.contains("police") || lowerText.contains("cbi") || lowerText.contains("customs") || lowerText.contains("court")) {
            triggers.add("AUTHORITY_FEAR")
            category = "GOVERNMENT_IMPERSONATION"
            score += 40
        }

        if (score >= 35) isScam = true

        return GroqAnalysisResponse(
            isScam = isScam,
            scamCategory = category,
            riskScore = score.coerceIn(0, 100),
            confidence = "MEDIUM",
            psychologicalTriggers = triggers,
            summaryReasoning = if (isScam) "Offline heuristic detected potential $category signals." else "No immediate offline threat detected.",
            keySuspiciousPhrases = emptyList()
        )
    }
}
