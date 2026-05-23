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

    companion object {
        private const val PREFS_NAME = "near_vote_prefs"
        private const val KEY_IDENTITY = "identity"
        private const val KEY_RECEIPTS = "receipts"
        private const val KEY_RESULTS = "results"
        private const val MAX_HISTORY_COUNT = 20
    }
}
