package com.nearvote.app.protocol

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
        fun ping(senderId: String): NearVoteMessage {
            return NearVoteMessage(
                type = NearVoteMessageType.PING,
                senderId = senderId,
                payloadJson = "{}"
            )
        }
    }
}

