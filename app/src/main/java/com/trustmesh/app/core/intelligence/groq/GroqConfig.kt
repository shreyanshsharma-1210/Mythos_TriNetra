package com.trustmesh.app.core.intelligence.groq

import android.content.Context
import android.util.Log

object GroqConfig {
    private const val TAG = "GroqConfig"
    private const val PREFS_NAME = "trustmesh_groq_prefs"
    private const val KEY_GROQ_API_KEY = "groq_api_key"
    private const val KEY_MODEL = "groq_model_name"

    const val DEFAULT_MODEL = "llama-3.3-70b-versatile"
    const val FAST_MODEL = "llama-3.3-70b-versatile"
    const val COMPOUND_MINI_MODEL = "groq/compound-mini"
    const val GPT_OSS_20B_MODEL = "openai/gpt-oss-20b"
    const val QWEN_27B_MODEL = "qwen/qwen3.6-27b"
    const val HIGH_ACCURACY_MODEL = "llama-3.3-70b-versatile"

    private val HARDCODED_FALLBACK_KEY = com.trustmesh.app.BuildConfig.GROQ_API_KEY

    @Volatile
    private var runtimeApiKey: String? = null

    fun getApiKey(context: Context? = null): String {
        // 1. Check in-memory runtime cache
        runtimeApiKey?.let { if (it.isNotBlank()) return it }

        // 2. Check SharedPreferences if Context is available
        if (context != null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedKey = prefs.getString(KEY_GROQ_API_KEY, null)
            if (!savedKey.isNullOrBlank()) {
                runtimeApiKey = savedKey
                return savedKey
            }
        }

        // 3. Fallback to System environment or property
        val envKey = System.getenv("GROQ_API_KEY") ?: System.getProperty("GROQ_API_KEY") ?: ""
        if (envKey.isNotBlank()) {
            runtimeApiKey = envKey
            return envKey
        }

        // 4. Fallback to configured API key
        return HARDCODED_FALLBACK_KEY
    }

    fun setApiKey(context: Context, apiKey: String) {
        runtimeApiKey = apiKey.trim()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GROQ_API_KEY, apiKey.trim()).apply()
        Log.i(TAG, "Groq API key updated in secure preferences.")
    }

    fun getModel(context: Context? = null): String {
        if (context != null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        }
        return DEFAULT_MODEL
    }

    fun isConfigured(context: Context? = null): Boolean {
        return getApiKey(context).isNotBlank()
    }
}
