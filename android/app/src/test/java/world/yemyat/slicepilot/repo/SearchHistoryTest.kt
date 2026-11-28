package world.yemyat.slicepilot.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHistoryTest {
    @Test
    fun recordPrependsDeduplicatesCaseInsensitivelyAndCapsAtTen() {
        var history = emptyList<String>()
        for (i in 1..12) {
            history = SearchHistory.record(history, "model $i")
        }
        assertEquals(SearchHistory.MAX_ENTRIES, history.size)
        assertEquals("model 12", history.first())
        assertEquals("model 3", history.last())

        // Re-searching an older query with different casing moves it to the top without duplicating.
        history = SearchHistory.record(history, "  MODEL 5  ")
        assertEquals(SearchHistory.MAX_ENTRIES, history.size)
        assertEquals("MODEL 5", history.first())
        assertEquals(1, history.count { it.equals("model 5", ignoreCase = true) })
    }

    @Test
    fun blankQueriesDoNotChangeHistory() {
        val start = listOf("benchy", "gridfinity")
        assertEquals(start, SearchHistory.record(start, ""))
        assertEquals(start, SearchHistory.record(start, "   \t\n "))
    }

    @Test
    fun removeDropsCaseInsensitiveMatch() {
        val start = listOf("Benchy", "Gridfinity", "Voron")
        assertEquals(listOf("Benchy", "Voron"), SearchHistory.remove(start, "gridfinity"))
        assertEquals(start, SearchHistory.remove(start, "missing"))
    }

    @Test
    fun suggestionsReturnAllWhenBlankAndRankPrefixMatchesFirst() {
        val history = listOf("3D benchy", "benchy boat", "gridfinity", "mini bench")
        assertEquals(history, SearchHistory.suggestions(history, ""))
        assertEquals(history, SearchHistory.suggestions(history, "   "))
        assertEquals(
            listOf("benchy boat", "3D benchy", "mini bench"),
            SearchHistory.suggestions(history, "bench"),
        )
        assertTrue(SearchHistory.suggestions(history, "zzz").isEmpty())
    }

    @Test
    fun encodeAndDecodeRoundTripAndSanitizePersistedData() {
        val items = listOf("benchy", "gridfinity bin", "spool holder")
        val encoded = SearchHistory.encode(items)
        assertEquals(items, SearchHistory.decode(encoded))

        // Corrupt JSON or blank/duplicate/oversized arrays degrade cleanly.
        assertTrue(SearchHistory.decode("not json").isEmpty())
        assertTrue(SearchHistory.decode(null).isEmpty())
        val messy = """["", "  benchy ", "BENCHY", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"]"""
        val decoded = SearchHistory.decode(messy)
        assertEquals(SearchHistory.MAX_ENTRIES, decoded.size)
        assertEquals("benchy", decoded.first())
        assertEquals("i", decoded.last())
    }
}
