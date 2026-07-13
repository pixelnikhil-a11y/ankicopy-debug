package com.example.ankicopy

import android.content.Context
import android.content.SharedPreferences

enum class AiTarget(
    val label: String,
    val packageName: String,
    // Substrings matched (case-insensitive) against a node's viewIdResourceName,
    // text, or contentDescription to find the message input box.
    val inputHints: List<String>,
    // Same, but to find the send button. Kept broad on purpose - app UIs
    // change their icon descriptions over time, so more hints = more resilient.
    val sendHints: List<String>
) {
    CHATGPT(
        "ChatGPT",
        "com.openai.chatgpt",
        listOf("prompt_textarea", "message", "compose", "input", "text_input"),
        listOf("send", "send message", "send prompt")
    ),
    CLAUDE(
        "Claude",
        "com.anthropic.claude",
        listOf("compose", "message", "input", "prompt"),
        listOf("send", "send message")
    ),
    GEMINI(
        "Gemini",
        "com.google.android.apps.bard",
        listOf("input", "compose", "message", "prompt"),
        listOf("send", "send message")
    );

    companion object {
        fun next(current: AiTarget): AiTarget {
            val values = values()
            return values[(current.ordinal + 1) % values.size]
        }
    }
}

object Prefs {
    private const val FILE = "ankicopy_prefs"
    private const val KEY_TARGET = "ai_target"
    private const val KEY_EXPLANATION_SUFFIX = "explanation_suffix"
    private const val KEY_SCENARIO_SUFFIX = "scenario_suffix"
    private const val KEY_DEBUG_MODE = "debug_mode"

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // Which app L1/R1 paste-and-send into. Cycled with L2.
    fun getTarget(context: Context): AiTarget {
        val name = sp(context).getString(KEY_TARGET, AiTarget.CHATGPT.name)
        return try {
            AiTarget.valueOf(name ?: AiTarget.CHATGPT.name)
        } catch (e: Exception) {
            AiTarget.CHATGPT
        }
    }

    fun setTarget(context: Context, target: AiTarget) {
        sp(context).edit().putString(KEY_TARGET, target.name).apply()
    }

    // L1: whole card + explanation request
    fun getExplanationSuffix(context: Context): String {
        return sp(context).getString(
            KEY_EXPLANATION_SUFFIX,
            "Explain this concept from the card clearly."
        ) ?: ""
    }

    fun setExplanationSuffix(context: Context, suffix: String) {
        sp(context).edit().putString(KEY_EXPLANATION_SUFFIX, suffix).apply()
    }

    // R1: card + apply-the-law-to-a-scenario request
    fun getScenarioSuffix(context: Context): String {
        return sp(context).getString(
            KEY_SCENARIO_SUFFIX,
            "Give me a specific factual scenario where this rule would apply, and walk through how it applies."
        ) ?: ""
    }

    fun setScenarioSuffix(context: Context, suffix: String) {
        sp(context).edit().putString(KEY_SCENARIO_SUFFIX, suffix).apply()
    }

    // When on, any gamepad button not otherwise mapped shows a toast with
    // its keycode name, so you can find out what your controller actually
    // sends for each physical button.
    fun getDebugMode(context: Context): Boolean {
        return sp(context).getBoolean(KEY_DEBUG_MODE, false)
    }

    fun setDebugMode(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_DEBUG_MODE, enabled).apply()
    }
}
