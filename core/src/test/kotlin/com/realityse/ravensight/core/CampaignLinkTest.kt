package com.realityse.ravensight.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The campaign link's pure rules: which ids are link ids, and when a waiting
 * link is sent and dropped. Mirrors ravensight-js. Plain JVM.
 */
class CampaignLinkTest {

    private val linkId = "64b0000000000000000000a1"
    private val otherId = "64b0000000000000000000b2"

    /** Everything the contract says to ignore. */
    private val invalid = listOf(
        null,
        "",
        "abc",
        "a".repeat(23),
        "a".repeat(25),
        "g".repeat(24),
        " $linkId",
        "$linkId ",
        "64b0000000000000000000a１", // a full width digit is not hex
    )

    @Test
    fun `only a 24 character hex id is a link id, and it comes back lower case`() {
        assertEquals(linkId, CampaignLinkState.valid(linkId.uppercase()))
        assertEquals(linkId, CampaignLinkState.valid(linkId))
        for (value in invalid) assertNull(value, CampaignLinkState.valid(value))
    }

    @Test
    fun `a valid id waits lower case and a valid call replaces it`() {
        val state = CampaignLinkState()
        assertNull(state.pendingLink)
        assertTrue(state.set(linkId.uppercase()))
        assertEquals(linkId, state.pendingLink)
        assertTrue(state.set(otherId))
        assertEquals(otherId, state.pendingLink)
    }

    @Test
    fun `an invalid id is refused and keeps the link already waiting`() {
        val state = CampaignLinkState()
        for (value in invalid) assertFalse(value, state.set(value))
        assertNull(state.pendingLink)
        state.set(linkId)
        for (value in invalid) state.set(value)
        assertEquals(linkId, state.pendingLink)
    }

    @Test
    fun `it goes with the session and is dropped only when one opens`() {
        val state = CampaignLinkState()
        state.set(linkId)
        val first = state.forSession(playtest = false)
        assertEquals(linkId, first)
        state.onSessionAnswer(first, opened = false)
        assertEquals("a failed session keeps it", linkId, state.pendingLink)
        val second = state.forSession(playtest = false)
        assertEquals(linkId, second)
        state.onSessionAnswer(second, opened = true)
        assertNull(state.pendingLink)
        assertNull("a later session carries none", state.forSession(playtest = false))
    }

    @Test
    fun `a link set while the session is out waits for the next one`() {
        val state = CampaignLinkState()
        state.set(linkId)
        val sent = state.forSession(playtest = false)
        state.set(otherId)
        state.onSessionAnswer(sent, opened = true)
        assertEquals(otherId, state.pendingLink)
        // The same id set again while out is the same link: dropped with it.
        val next = state.forSession(playtest = false)
        state.set(otherId)
        state.onSessionAnswer(next, opened = true)
        assertNull(state.pendingLink)
    }

    @Test
    fun `a playtest session never carries it and does not drop it`() {
        val state = CampaignLinkState()
        state.set(linkId)
        val sent = state.forSession(playtest = true)
        assertNull(sent)
        state.onSessionAnswer(sent, opened = true)
        assertEquals(linkId, state.pendingLink)
    }
}
