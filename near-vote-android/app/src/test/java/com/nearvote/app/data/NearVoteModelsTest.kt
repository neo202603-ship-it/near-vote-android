package com.nearvote.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearVoteModelsTest {
    @Test
    fun resultHashDetectsChangedVoteCount() {
        val result = sampleResult()

        assertTrue(result.isHashValid())
        assertFalse(result.copy(counts = mapOf("한식" to 0, "분식" to 2)).isHashValid())
    }

    @Test
    fun privateResultDoesNotExposeParticipantSelections() {
        val result = sampleResult().copy(
            participantSelections = emptyMap(),
            revealSelections = false,
            resultHash = SharedResult.computeHash(
                pollId = "poll-test",
                question = "점심메뉴는?",
                options = listOf("한식", "분식"),
                counts = mapOf("한식" to 1, "분식" to 1),
                participantIds = listOf("one", "two")
            )
        )

        val restored = SharedResult.fromPayload("proposer", result.toPayloadJson())

        assertFalse(restored.revealSelections)
        assertTrue(restored.participantSelections.isEmpty())
        assertTrue(restored.isHashValid())
    }

    @Test
    fun pollPayloadPreservesVisibilityChoice() {
        val poll = NearbyPoll(
            id = "poll-test",
            proposerId = "proposer",
            proposerName = "차분한노트",
            question = "점심메뉴는?",
            options = listOf("한식", "분식"),
            durationMinutes = 1,
            durationSeconds = 30,
            endAtMillis = 1234L,
            revealSelections = false
        )

        val restored = NearbyPoll.fromPayload("proposer", poll.toPayloadJson())

        assertEquals(false, restored.revealSelections)
        assertEquals(30, restored.durationSeconds)
    }

    private fun sampleResult(): SharedResult {
        val counts = mapOf("한식" to 1, "분식" to 1)
        val participants = listOf("one", "two")
        val selections = mapOf("one" to "한식", "two" to "분식")
        return SharedResult(
            pollId = "poll-test",
            proposerId = "proposer",
            proposerName = "차분한노트",
            question = "점심메뉴는?",
            options = listOf("한식", "분식"),
            counts = counts,
            participantIds = participants,
            participantNames = listOf("밝은시계", "조용한연필"),
            participantSelections = selections,
            participantCount = 2,
            createdAtMillis = 1L,
            resultHash = SharedResult.computeHash(
                pollId = "poll-test",
                question = "점심메뉴는?",
                options = listOf("한식", "분식"),
                counts = counts,
                participantIds = participants,
                participantSelections = selections
            )
        )
    }
}
