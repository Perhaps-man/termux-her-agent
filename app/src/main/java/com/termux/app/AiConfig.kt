package com.termux.app

import android.content.Context

private const val PREFS_AI = "ai_config"
private const val KEY_PROVIDER = "provider"
private const val KEY_API_KEY = "api_key"
private const val KEY_MODEL = "model"
private const val KEY_EMBED_API_KEY = "embed_api_key"
private const val KEY_SEARCH_API_KEY = "search_api_key"

/** 供应商标识，与抽屉 Spinner 顺序对应 */
const val PROVIDER_CLOSEAI = "closeai"
const val PROVIDER_OPENAI = "openai"
const val PROVIDER_DEEPSEEK = "deepseek"
const val PROVIDER_QWEN = "qwen"
const val PROVIDER_GPT = "gpt"
const val PROVIDER_GEMINI = "gemini"
const val PROVIDER_CLAUDE = "claude"

val AI_PROVIDER_IDS = listOf(
    PROVIDER_CLOSEAI,
    PROVIDER_OPENAI,
    PROVIDER_DEEPSEEK,
    PROVIDER_QWEN,
    PROVIDER_GPT,
    PROVIDER_GEMINI,
    PROVIDER_CLAUDE
)

fun getAiProvider(context: Context): String =
    context.getSharedPreferences(PREFS_AI, Context.MODE_PRIVATE)
        .getString(KEY_PROVIDER, PROVIDER_CLOSEAI) ?: PROVIDER_CLOSEAI

fun setAiProvider(context: Context, provider: String) {
    context.getSharedPreferences(PREFS_AI, Context.MODE_PRIVATE)
        .edit().putString(KEY_PROVIDER, provider).apply()
}

fun getAiApiKey(context: Context): String =
    context.getSharedPreferences(PREFS_AI, Context.MODE_PRIVATE)
        .getString(KEY_API_KEY, "") ?: ""

fun setAiApiKey(context: Context, key: String) {
    context.getSharedPreferences(PREFS_AI, Context.MODE_PRIVATE)
        .edit().putString(KEY_API_KEY, key).apply()
}

fun getAiModel(context: Context): String =
    context.getSharedPreferences(PREFS_AI, Context.MODE_PRIVATE)
        .getString(KEY_MODEL, "")?.takeIf { it.isNotBlank() }
        ?: defaultModelForProvider(getAiProvider(context))

fun setAiModel(context: Context, model: String) {
    context.getSharedPreferences(PREFS_AI, Context.MODE_PRIVATE)
        .edit().putString(KEY_MODEL, model.trim()).apply()
}

/** Dashscope embedding API key（text-embedding-v4），独立于主 LLM 供应商 */
fun getEmbedApiKey(context: Context): String =
    context.getSharedPreferences(PREFS_AI, Context.MODE_PRIVATE)
        .getString(KEY_EMBED_API_KEY, "") ?: ""

fun setEmbedApiKey(context: Context, key: String) {
    context.getSharedPreferences(PREFS_AI, Context.MODE_PRIVATE)
        .edit().putString(KEY_EMBED_API_KEY, key.trim()).apply()
}

fun getSearchApiKey(context: Context): String =
    context.getSharedPreferences(PREFS_AI, Context.MODE_PRIVATE)
        .getString(KEY_SEARCH_API_KEY, "") ?: ""

fun setSearchApiKey(context: Context, key: String) {
    context.getSharedPreferences(PREFS_AI, Context.MODE_PRIVATE)
        .edit().putString(KEY_SEARCH_API_KEY, key.trim()).apply()
}

fun defaultModelForProvider(provider: String): String = when (provider) {
    PROVIDER_CLOSEAI -> "gpt-5.2"
    PROVIDER_OPENAI, PROVIDER_GPT -> "gpt-4o-mini"
    PROVIDER_DEEPSEEK -> "deepseek-chat"
    PROVIDER_QWEN -> "qwen-plus"
    PROVIDER_GEMINI -> "gemini-2.0-flash"
    PROVIDER_CLAUDE -> "claude-3-5-sonnet-20241022"
    else -> "gpt-5.2"
}

fun getAiProviderDisplayName(provider: String): String = when (provider) {
    PROVIDER_CLOSEAI -> "CloseAI"
    PROVIDER_OPENAI -> "OpenAI"
    PROVIDER_GPT -> "GPT"
    PROVIDER_DEEPSEEK -> "DeepSeek"
    PROVIDER_QWEN -> "Qwen"
    PROVIDER_GEMINI -> "Gemini"
    PROVIDER_CLAUDE -> "Claude"
    else -> provider
}
