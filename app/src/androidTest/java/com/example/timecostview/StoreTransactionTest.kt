package com.example.timecostview

import androidx.test.platform.app.InstrumentationRegistry
import com.example.timecostview.data.Store
import com.example.timecostview.domain.Record
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class StoreTransactionTest {
    /** Isolated database: never touches the user's time-cost.db or preferences. */
    private fun withStore(test: (Store) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "session-test-${UUID.randomUUID()}.db"
        try { Store(context, name).use(test) } finally { context.deleteDatabase(name) }
    }

    @Test fun failedSecondPartRollsBackFirstPartAndRetrySucceeds() = withStore { store ->
        val original = Record(0, "video", "Video", "SPEND", 1_000L, 1_000L, 0L, 1800.0)
        val saved = original.copy(id = store.save(original))
        val parts = listOf(saved.copy(end = 2_000L, duration = 1_000L),
            original.copy(start = 2_000L, end = 3_000L, duration = 1_000L))
        store.writableDatabase.execSQL("CREATE TRIGGER fail_insert BEFORE INSERT ON records BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(runCatching { store.complete(parts) }.isFailure)
        assertEquals(listOf(saved), store.records())
        store.writableDatabase.execSQL("DROP TRIGGER fail_insert")
        store.complete(parts)
        val rows = store.records().sortedBy { it.start }
        assertEquals(2, rows.size)
        assertEquals(parts.first(), rows.first())
        assertEquals(2_000L, rows.sumOf { it.duration })
    }

    @Test fun missingActiveRowDoesNotInsertOrphanParts() = withStore { store ->
        val missing = Record(123, "video", "Video", "SPEND", 1_000L, 2_000L, 1_000L, 1800.0)
        assertTrue(runCatching { store.complete(listOf(missing, missing.copy(id = 0))) }.isFailure)
        assertTrue(store.records().isEmpty())
    }
}
