package com.nearvote.app.data

import org.json.JSONArray
import org.json.JSONObject

data class VoteReceipt(
    val pollId: String,
    val voterId: String,
    val voteHash: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("pollId", pollId)
            .put("voterId", voterId)
            .put("voteHash", voteHash)
    }

    companion object {
        fun fromJson(json: JSONObject): VoteReceipt {
            return VoteReceipt(
                pollId = json.getString("pollId"),
                voterId = json.getString("voterId"),
                voteHash = json.getString("voteHash")
            )
        }
    }
}

data class SharedResult(
    val pollId: String,
    val proposerId: String,
    val question: String,
    val options: List<String>,
    val counts: Map<String, Int>,
    val participantCount: Int,
    val resultHash: String
) {
    fun toPayloadJson(): String {
        val countJson = JSONObject()
        counts.forEach { (option, count) -> countJson.put(option, count) }
        return JSONObject()
            .put("pollId", pollId)
            .put("question", question)
            .put("options", JSONArray(options))
            .put("counts", countJson)
            .put("participantCount", participantCount)
            .put("resultHash", resultHash)
            .toString()
    }

    fun toHistoryJson(): JSONObject {
        val countJson = JSONObject()
        counts.forEach { (option, count) -> countJson.put(option, count) }
        return JSONObject()
            .put("pollId", pollId)
            .put("proposerId", proposerId)
            .put("question", question)
            .put("options", JSONArray(options))
            .put("counts", countJson)
            .put("participantCount", participantCount)
            .put("resultHash", resultHash)
    }

    companion object {
        fun fromPayload(proposerId: String, payloadJson: String): SharedResult {
            val payload = JSONObject(payloadJson)
            return fromJson(proposerId, payload)
        }

        fun fromHistoryJson(payload: JSONObject): SharedResult {
            return fromJson(payload.getString("proposerId"), payload)
        }

        private fun fromJson(proposerId: String, payload: JSONObject): SharedResult {
            val optionsArray = payload.getJSONArray("options")
            val options = (0 until optionsArray.length()).map { optionsArray.getString(it) }
            val countsJson = payload.getJSONObject("counts")
            return SharedResult(
                pollId = payload.getString("pollId"),
                proposerId = proposerId,
                question = payload.getString("question"),
                options = options,
                counts = options.associateWith { countsJson.optInt(it, 0) },
                participantCount = payload.getInt("participantCount"),
                resultHash = payload.getString("resultHash")
            )
        }
    }
}

data class NearbyPoll(
    val id: String,
    val proposerId: String,
    val question: String,
    val options: List<String>,
    val durationMinutes: Int,
    val endAtMillis: Long
) {
    fun toPayloadJson(): String {
        return JSONObject()
            .put("pollId", id)
            .put("question", question)
            .put("options", JSONArray(options))
            .put("durationMinutes", durationMinutes)
            .put("endAtMillis", endAtMillis)
            .toString()
    }

    fun hasEnded(): Boolean = System.currentTimeMillis() >= endAtMillis

    fun remainingText(): String {
        val remainingMillis = (endAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val remainingMinutes = ((remainingMillis + 59_999L) / 60_000L).coerceAtLeast(0L)
        return if (remainingMinutes == 0L) "곧 종료" else "${remainingMinutes}분 남음"
    }

    fun statusText(connectedCount: Int): String {
        return if (hasEnded()) {
            "제한시간 종료 · 연결된 기기 ${connectedCount}대"
        } else {
            "${remainingText()} · 연결된 기기 ${connectedCount}대에 참여 요청을 보냈습니다."
        }
    }

    companion object {
        fun fromPayload(proposerId: String, payloadJson: String): NearbyPoll {
            val payload = JSONObject(payloadJson)
            val optionsArray = payload.getJSONArray("options")
            val options = (0 until optionsArray.length()).map { optionsArray.getString(it) }
            return NearbyPoll(
                id = payload.getString("pollId"),
                proposerId = proposerId,
                question = payload.getString("question"),
                options = options,
                durationMinutes = payload.optInt("durationMinutes", 5),
                endAtMillis = payload.optLong(
                    "endAtMillis",
                    System.currentTimeMillis() + payload.optInt("durationMinutes", 5) * 60_000L
                )
            )
        }
    }
}

data class PollTemplate(
    val id: String,
    val title: String,
    val question: String,
    val options: List<String>,
    val durationMinutes: Int,
    val builtIn: Boolean = false
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("question", question)
            .put("options", JSONArray(options))
            .put("durationMinutes", durationMinutes)
            .put("builtIn", builtIn)
    }

    companion object {
        fun fromJson(json: JSONObject): PollTemplate {
            val optionsArray = json.getJSONArray("options")
            val options = (0 until optionsArray.length()).map { optionsArray.getString(it) }
            return PollTemplate(
                id = json.getString("id"),
                title = json.getString("title"),
                question = json.getString("question"),
                options = options,
                durationMinutes = json.optInt("durationMinutes", 5),
                builtIn = json.optBoolean("builtIn", false)
            )
        }
    }
}
