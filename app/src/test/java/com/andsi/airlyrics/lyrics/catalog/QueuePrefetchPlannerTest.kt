package com.andsi.airlyrics.lyrics.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuePrefetchPlannerTest {
    @Test
    fun nextFromQueue_requiresActiveIdInsideTheQueue() {
        val items = listOf(
            PrefetchQueueItem(queueId = 1, title = "A"),
            PrefetchQueueItem(queueId = 2, title = "B"),
            PrefetchQueueItem(queueId = 3, title = "C"),
            PrefetchQueueItem(queueId = 4, title = "D")
        )
        assertTrue(QueuePrefetchPlanner.nextFromQueue(items, activeQueueItemId = null).isEmpty())
        assertTrue(QueuePrefetchPlanner.nextFromQueue(items, activeQueueItemId = 99L).isEmpty())
        assertEquals(
            listOf("B", "C"),
            QueuePrefetchPlanner.nextFromQueue(items, activeQueueItemId = 1L).map { it.title }
        )
        assertEquals(
            listOf("D"),
            QueuePrefetchPlanner.nextFromQueue(items, activeQueueItemId = 3L).map { it.title }
        )
    }

    @Test
    fun nextFromQueue_skipsUnidentifiableItems() {
        val items = listOf(
            PrefetchQueueItem(queueId = 1, title = "Now"),
            PrefetchQueueItem(queueId = 2, title = "  "),
            PrefetchQueueItem(queueId = 3, title = "Next")
        )
        assertEquals(
            listOf("Next"),
            QueuePrefetchPlanner.nextFromQueue(items, 1L).map { it.title }
        )
    }

    @Test
    fun fingerprint_changesWhenQueueOrActiveItemChanges() {
        val items = listOf(PrefetchQueueItem(queueId = 1, title = "A"))
        val first = QueuePrefetchPlanner.fingerprint(1L, items, 1L)
        val shuffled = QueuePrefetchPlanner.fingerprint(
            1L,
            listOf(PrefetchQueueItem(queueId = 2, title = "B"), items[0]),
            1L
        )
        val newEpoch = QueuePrefetchPlanner.fingerprint(2L, items, 1L)
        assertTrue(first != shuffled)
        assertTrue(first != newEpoch)
    }
}
