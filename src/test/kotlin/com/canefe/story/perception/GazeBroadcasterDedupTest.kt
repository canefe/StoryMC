package com.canefe.story.perception

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GazeBroadcasterDedupTest {

    @Test
    fun `first observation always passes`() {
        val cache = ObserverGazeCache(ttlMillis = 30_000)
        assertTrue(cache.shouldEmit("obs1", "gazer1", "target1", nowMs = 1_000))
    }

    @Test
    fun `same triple within ttl is suppressed`() {
        val cache = ObserverGazeCache(ttlMillis = 30_000)
        cache.shouldEmit("obs1", "gazer1", "target1", nowMs = 1_000)
        assertFalse(cache.shouldEmit("obs1", "gazer1", "target1", nowMs = 10_000))
    }

    @Test
    fun `same triple after ttl passes again`() {
        val cache = ObserverGazeCache(ttlMillis = 30_000)
        cache.shouldEmit("obs1", "gazer1", "target1", nowMs = 1_000)
        assertTrue(cache.shouldEmit("obs1", "gazer1", "target1", nowMs = 40_000))
    }

    @Test
    fun `different target for same observer passes`() {
        val cache = ObserverGazeCache(ttlMillis = 30_000)
        cache.shouldEmit("obs1", "gazer1", "target1", nowMs = 1_000)
        assertTrue(cache.shouldEmit("obs1", "gazer1", "target2", nowMs = 2_000))
    }
}
