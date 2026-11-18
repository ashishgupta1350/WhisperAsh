package com.whisperash.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountModeTest {
    @Test fun accountRefusalsDoNotFallBackToGemini() {
        // Limit reached, paused or signed out: the backup engine would be refused the same way.
        for (code in listOf("word_limit", "chat_limit", "account_blocked", "session_expired")) {
            val e = ApiException(429, "WhisperAsh", "limit", code)
            assertTrue(code, e.final)
            assertFalse(code, e.retryable)
        }
    }

    @Test fun busyOrFailingServiceStillFallsBack() {
        assertTrue(ApiException(429, "WhisperAsh", "busy", "upstream_busy").retryable)
        assertTrue(ApiException(502, "WhisperAsh", "key refused", "upstream_key").retryable)
        assertTrue(ApiException(0, "Groq", "offline").retryable)
        assertFalse(ApiException(400, "Groq", "bad request").retryable)
    }

    @Test fun planSummaryReadsNaturally() {
        val day = AccountPlan(2000, 760, 1240, "day", 0L, "active", false)
        assertEquals("1,240 of 2,000 words left today", day.summary())
        val week = AccountPlan(10000, 0, 10000, "week", 0L, "active", false)
        assertEquals("10,000 of 10,000 words left this week", week.summary())
        assertTrue(AccountPlan(2000, 0, 2000, "day", 0L, "blocked", false).summary().contains("paused"))
        assertTrue(AccountPlan(2000, 0, 2000, "day", 0L, "active", true).summary().startsWith("No word limit"))
    }
}
