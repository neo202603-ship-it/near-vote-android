package com.nearvote.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class NearVoteStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadIdentity(fallback: () -> String): String {
        val saved = prefs.getString(KEY_IDENTITY, null)
        if (!saved.isNullOrBlank()) return saved
        val suggested = fallback()
        saveIdentity(suggested)
        return suggested
    }

    fun saveIdentity(identity: String) {
        prefs.edit().putString(KEY_IDENTITY, identity).apply()
    }

    fun loadAvatarId(fallback: () -> Int): Int {
        if (prefs.contains(KEY_AVATAR_ID)) return prefs.getInt(KEY_AVATAR_ID, 0)
        val suggested = fallback()
        saveAvatarId(suggested)
        return suggested
    }

    fun saveAvatarId(avatarId: Int) {
        prefs.edit().putInt(KEY_AVATAR_ID, avatarId).apply()
    }

    fun loadUserId(fallback: () -> String): String {
        val saved = prefs.getString(KEY_USER_ID, null)
        if (!saved.isNullOrBlank()) return saved
        val generated = fallback()
        prefs.edit().putString(KEY_USER_ID, generated).apply()
        return generated
    }

    fun isAutoConnectEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_CONNECT, true)
    }

    fun saveAutoConnectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
    }

    fun saveReceipt(receipt: VoteReceipt) {
        val receipts = JSONObject(prefs.getString(KEY_RECEIPTS, "{}").orEmpty().ifBlank { "{}" })
        receipts.put(receipt.pollId, receipt.toJson())
        prefs.edit().putString(KEY_RECEIPTS, receipts.toString()).apply()
    }

    fun loadReceipt(pollId: String): VoteReceipt? {
        val raw = prefs.getString(KEY_RECEIPTS, "{}").orEmpty().ifBlank { "{}" }
        val receipts = JSONObject(raw)
        return receipts.optJSONObject(pollId)?.let { VoteReceipt.fromJson(it) }
    }

    fun saveResult(result: SharedResult) {
        val existing = loadResultHistory()
            .filterNot { it.pollId == result.pollId }
            .toMutableList()
        existing.add(0, result)
        val results = JSONArray()
        existing.take(MAX_HISTORY_COUNT).forEach { results.put(it.toHistoryJson()) }
        prefs.edit().putString(KEY_RESULTS, results.toString()).apply()
    }

    fun loadResultHistory(): List<SharedResult> {
        val raw = prefs.getString(KEY_RESULTS, "[]").orEmpty().ifBlank { "[]" }
        val results = JSONArray(raw)
        return (0 until results.length()).mapNotNull { index ->
            runCatching { SharedResult.fromHistoryJson(results.getJSONObject(index)) }.getOrNull()
        }
    }

    fun loadTemplates(): List<PollTemplate> {
        val saved = prefs.getString(KEY_TEMPLATES, "[]").orEmpty().ifBlank { "[]" }
        val templates = JSONArray(saved)
        val userTemplates = (0 until templates.length()).mapNotNull { index ->
            runCatching { PollTemplate.fromJson(templates.getJSONObject(index)) }.getOrNull()
        }
        return defaultTemplates + userTemplates
    }

    fun saveTemplate(template: PollTemplate) {
        val userTemplates = loadUserTemplates()
            .filterNot { it.id == template.id }
            .toMutableList()
        userTemplates.add(0, template.copy(builtIn = false))
        persistUserTemplates(userTemplates)
    }

    fun deleteTemplate(templateId: String) {
        persistUserTemplates(loadUserTemplates().filterNot { it.id == templateId })
    }

    private fun loadUserTemplates(): List<PollTemplate> {
        val saved = prefs.getString(KEY_TEMPLATES, "[]").orEmpty().ifBlank { "[]" }
        val templates = JSONArray(saved)
        return (0 until templates.length()).mapNotNull { index ->
            runCatching { PollTemplate.fromJson(templates.getJSONObject(index)) }.getOrNull()
        }
    }

    private fun persistUserTemplates(templates: List<PollTemplate>) {
        val json = JSONArray()
        templates.forEach { json.put(it.toJson()) }
        prefs.edit().putString(KEY_TEMPLATES, json.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "near_vote_prefs"
        private const val KEY_IDENTITY = "identity"
        private const val KEY_AVATAR_ID = "avatar_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_RECEIPTS = "receipts"
        private const val KEY_RESULTS = "results"
        private const val KEY_TEMPLATES = "templates"
        private const val MAX_HISTORY_COUNT = 20

        private val defaultTemplates = listOf(
            PollTemplate(
                id = "builtin_lunch",
                title = "점심메뉴는?",
                question = "점심메뉴는?",
                options = listOf("한식", "분식", "샐러드"),
                durationMinutes = 5,
                builtIn = true
            ),
            PollTemplate(
                id = "builtin_dinner",
                title = "오늘 회식은?",
                question = "오늘 회식은?",
                options = listOf("삼겹살", "치킨", "이자카야", "다음에"),
                durationMinutes = 10,
                builtIn = true
            ),
            PollTemplate(
                id = "builtin_coffee",
                title = "커피 주문할까?",
                question = "커피 주문할까?",
                options = listOf("아메리카노", "라떼", "안 마심"),
                durationMinutes = 5,
                builtIn = true
            )
        )
    }
}
