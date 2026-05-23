package com.nearvote.app.simulation

import com.nearvote.app.protocol.NearVoteMessage
import com.nearvote.app.protocol.NearVoteMessageType
import java.security.MessageDigest
import java.time.Instant

class LocalVoteSimulator(
    private val proposerId: String,
    private val participantIds: List<String> = listOf("따뜻한머그컵", "빠른가방", "조용한연필")
) {
    private val question = "점심메뉴는?"
    private val options = listOf("한식", "분식", "샐러드")

    fun runDemo(): List<String> {
        val pollId = sha256("$question:${Instant.now().toEpochMilli()}").take(12)
        val votes = participantIds.mapIndexed { index, participantId ->
            LocalVote(
                voterId = participantId,
                option = options[index % options.size],
                voteHash = sha256("$pollId:$participantId:${options[index % options.size]}")
            )
        }
        val resultHash = sha256(votes.joinToString("|") { "${it.voterId}:${it.option}:${it.voteHash}" })

        val pollMessage = NearVoteMessage(
            type = NearVoteMessageType.POLL,
            senderId = proposerId,
            payloadJson = """{"pollId":"$pollId","question":"$question","options":${options.toJsonArray()}}"""
        )
        val receiptMessages = votes.map {
            NearVoteMessage(
                type = NearVoteMessageType.RECEIPT,
                senderId = proposerId,
                payloadJson = """{"pollId":"$pollId","voterId":"${it.voterId}","voteHash":"${it.voteHash.take(16)}"}"""
            )
        }
        val resultMessage = NearVoteMessage(
            type = NearVoteMessageType.RESULT_BLOCK,
            senderId = proposerId,
            payloadJson = """{"pollId":"$pollId","participantCount":${votes.size},"resultHash":"${resultHash.take(16)}"}"""
        )

        val counts = votes.groupingBy { it.option }.eachCount()
        return buildList {
            add("로컬 시뮬레이션 시작")
            add("내 아이디: $proposerId")
            add("가상 참여자: ${participantIds.joinToString(", ")}")
            add("설문 생성: $question / ${options.joinToString(", ")}")
            add("POLL 메시지: ${pollMessage.toJson()}")
            votes.forEach { vote ->
                add("${vote.voterId} 투표: ${vote.option} (${vote.voteHash.take(16)})")
            }
            receiptMessages.forEach { receipt ->
                add("영수증 발급: ${receipt.toJson()}")
            }
            add("결과 집계: ${counts.entries.joinToString(", ") { "${it.key} ${it.value}표" }}")
            add("RESULT_BLOCK 메시지: ${resultMessage.toJson()}")
            add("검증 요약: 참여자 ${votes.size}명, 영수증 ${receiptMessages.size}건, 결과 해시 ${resultHash.take(16)}")
        }
    }

    private data class LocalVote(
        val voterId: String,
        val option: String,
        val voteHash: String
    )

    private fun List<String>.toJsonArray(): String {
        return joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
