package com.nearvote.app.protocol

import org.json.JSONObject

enum class NearVoteMessageType {
    POLL,
    VOTE,
    RECEIPT,
    RESULT_BLOCK,
    GOSSIP,
    PING
}

data class NearVoteMessage(
    val type: NearVoteMessageType,
    val senderId: String,
    val payloadJson: String
) {
    fun toJson(): String {
        val escapedPayload = payloadJson.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"type":"$type","senderId":"$senderId","payloadJson":"$escapedPayload"}"""
    }

    companion object {
        fun fromJson(json: String): NearVoteMessage {
            val parsed = JSONObject(json)
            return NearVoteMessage(
                type = NearVoteMessageType.valueOf(parsed.getString("type")),
                senderId = parsed.getString("senderId"),
                payloadJson = parsed.getString("payloadJson")
            )
        }

        fun ping(senderId: String): NearVoteMessage {
            return NearVoteMessage(
                type = NearVoteMessageType.PING,
                senderId = senderId,
                payloadJson = "{}"
            )
        }
    }
}
