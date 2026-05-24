package com.nearvote.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class VoteReceipt(
    val pollId: String,
    val voterId: String,
    val voterName: String,
    val voteHash: String
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("pollId", pollId)
            .put("voterId", voterId)
            .put("voterName", voterName)
            .put("voteHash", voteHash)
    }

    companion object {
        fun fromJson(json: JSONObject): VoteReceipt {
            return VoteReceipt(
                pollId = json.getString("pollId"),
                voterId = json.getString("voterId"),
                voterName = json.optString("voterName", json.getString("voterId")),
                voteHash = json.getString("voteHash")
            )
        }
    }
}

data class SharedResult(
    val pollId: String,
    val proposerId: String,
    val proposerName: String,
    val proposerAvatarId: Int = -1,
    val question: String,
    val options: List<String>,
    val counts: Map<String, Int>,
    val participantIds: List<String>,
    val participantNames: List<String>,
    val participantAvatarIds: Map<String, Int> = emptyMap(),
    val participantSelections: Map<String, String>,
    val participantCount: Int,
    val createdAtMillis: Long,
    val resultHash: String
) {
    fun toPayloadJson(): String {
        val countJson = JSONObject()
        counts.forEach { (option, count) -> countJson.put(option, count) }
        return JSONObject()
            .put("pollId", pollId)
            .put("proposerName", proposerName)
            .put("proposerAvatarId", proposerAvatarId)
            .put("question", question)
            .put("options", JSONArray(options))
            .put("counts", countJson)
            .put("participantIds", JSONArray(participantIds))
            .put("participantNames", JSONArray(participantNames))
            .put("participantAvatarIds", JSONObject(participantAvatarIds))
            .put("participantSelections", JSONObject(participantSelections))
            .put("participantCount", participantCount)
            .put("createdAtMillis", createdAtMillis)
            .put("resultHash", resultHash)
            .toString()
    }

    fun toHistoryJson(): JSONObject {
        val countJson = JSONObject()
        counts.forEach { (option, count) -> countJson.put(option, count) }
        return JSONObject()
            .put("pollId", pollId)
            .put("proposerId", proposerId)
            .put("proposerName", proposerName)
            .put("proposerAvatarId", proposerAvatarId)
            .put("question", question)
            .put("options", JSONArray(options))
            .put("counts", countJson)
            .put("participantIds", JSONArray(participantIds))
            .put("participantNames", JSONArray(participantNames))
            .put("participantAvatarIds", JSONObject(participantAvatarIds))
            .put("participantSelections", JSONObject(participantSelections))
            .put("participantCount", participantCount)
            .put("createdAtMillis", createdAtMillis)
            .put("resultHash", resultHash)
    }

    fun isHashValid(): Boolean = resultHash == computeHash(
        pollId = pollId,
        question = question,
        options = options,
        counts = counts,
        participantIds = participantIds,
        participantSelections = participantSelections
    )

    companion object {
        fun computeHash(
            pollId: String,
            question: String,
            options: List<String>,
            counts: Map<String, Int>,
            participantIds: List<String>,
            participantSelections: Map<String, String> = emptyMap()
        ): String {
            val canonical = buildString {
                append(pollId)
                append("|")
                append(question)
                append("|")
                append(options.joinToString(","))
                append("|")
                append(options.joinToString(",") { "${it}:${counts[it] ?: 0}" })
                append("|")
                append(participantIds.sorted().joinToString(","))
                if (participantSelections.isNotEmpty()) {
                    append("|")
                    append(participantIds.sorted().joinToString(",") { id -> "$id:${participantSelections[id].orEmpty()}" })
                }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

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
            val participantIdsArray = payload.optJSONArray("participantIds")
            val participantIds = if (participantIdsArray == null) {
                emptyList()
            } else {
                (0 until participantIdsArray.length()).map { participantIdsArray.getString(it) }
            }
            val participantNamesArray = payload.optJSONArray("participantNames")
            val participantNames = if (participantNamesArray == null) {
                participantIds
            } else {
                (0 until participantNamesArray.length()).map { participantNamesArray.getString(it) }
            }
            val selectionsJson = payload.optJSONObject("participantSelections")
            val participantSelections = if (selectionsJson == null) {
                emptyMap()
            } else {
                participantIds.associateWith { id -> selectionsJson.optString(id) }.filterValues { it.isNotBlank() }
            }
            val avatarsJson = payload.optJSONObject("participantAvatarIds")
            val participantAvatarIds = if (avatarsJson == null) {
                emptyMap()
            } else {
                participantIds.associateWith { id -> avatarsJson.optInt(id, 0) }
            }
            return SharedResult(
                pollId = payload.getString("pollId"),
                proposerId = proposerId,
                proposerName = payload.optString("proposerName", proposerId),
                proposerAvatarId = payload.optInt("proposerAvatarId", -1),
                question = payload.getString("question"),
                options = options,
                counts = options.associateWith { countsJson.optInt(it, 0) },
                participantIds = participantIds,
                participantNames = participantNames,
                participantAvatarIds = participantAvatarIds,
                participantSelections = participantSelections,
                participantCount = payload.getInt("participantCount"),
                createdAtMillis = payload.optLong(
                    "createdAtMillis",
                    payload.optString("pollId").removePrefix("poll-").toLongOrNull() ?: System.currentTimeMillis()
                ),
                resultHash = payload.getString("resultHash")
            )
        }
    }
}

data class NearbyPoll(
    val id: String,
    val proposerId: String,
    val proposerName: String,
    val proposerAvatarId: Int = -1,
    val question: String,
    val options: List<String>,
    val durationMinutes: Int,
    val durationSeconds: Int,
    val endAtMillis: Long
) {
    fun toPayloadJson(): String {
        return JSONObject()
            .put("pollId", id)
            .put("proposerName", proposerName)
            .put("proposerAvatarId", proposerAvatarId)
            .put("question", question)
            .put("options", JSONArray(options))
            .put("durationMinutes", durationMinutes)
            .put("durationSeconds", durationSeconds)
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
                proposerName = payload.optString("proposerName", proposerId),
                proposerAvatarId = payload.optInt("proposerAvatarId", -1),
                question = payload.getString("question"),
                options = options,
                durationMinutes = payload.optInt("durationMinutes", 5),
                durationSeconds = payload.optInt(
                    "durationSeconds",
                    payload.optInt("durationMinutes", 5) * 60
                ),
                endAtMillis = payload.optLong(
                    "endAtMillis",
                    System.currentTimeMillis() + payload.optInt(
                        "durationSeconds",
                        payload.optInt("durationMinutes", 5) * 60
                    ) * 1_000L
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
    val durationSeconds: Int = durationMinutes * 60,
    val builtIn: Boolean = false
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("question", question)
            .put("options", JSONArray(options))
            .put("durationMinutes", durationMinutes)
            .put("durationSeconds", durationSeconds)
            .put("builtIn", builtIn)
    }

    companion object {
        fun fromJson(json: JSONObject): PollTemplate {
            val optionsArray = json.getJSONArray("options")
            val options = (0 until optionsArray.length()).map { optionsArray.getString(it) }
            val durationMinutes = json.optInt("durationMinutes", 5)
            return PollTemplate(
                id = json.getString("id"),
                title = json.getString("title"),
                question = json.getString("question"),
                options = options,
                durationMinutes = durationMinutes,
                durationSeconds = json.optInt("durationSeconds", durationMinutes * 60),
                builtIn = json.optBoolean("builtIn", false)
            )
        }
    }
}
