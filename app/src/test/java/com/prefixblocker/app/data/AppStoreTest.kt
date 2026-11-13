package com.prefixblocker.app.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import com.prefixblocker.app.core.Decision
import com.prefixblocker.app.core.Reason
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AppStoreTest {
    private lateinit var context: Context
    private lateinit var store: AppStore
    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("prefix_blocker.db")
        store = AppStore(context)
    }
    @After fun tearDown() { store.close() }

    @Test fun defaultRulesAreSeededExactlyOnceEvenAfterDeletion() {
        assertEquals(5, store.rules(false).size)
        store.rules(false).forEach { store.deleteRule(it) }
        store.close()
        store = AppStore(context)
        assertTrue(store.rules(false).isEmpty())
    }

    @Test fun equivalentDuplicateIsRejected() {
        assertThrows(SQLiteConstraintException::class.java) { store.saveRule("+33 9 48*", false) }
        assertEquals(5, store.rules(false).size)
    }

    @Test fun editAndDeletePersistAcrossReopen() {
        val rule = store.rules(false).first()
        store.saveRule("0999*", false, rule.id)
        store.close()
        store = AppStore(context)
        assertEquals("+33999*", store.rules(false).first { it.id == rule.id }.canonical)
        store.deleteRule(store.rules(false).first { it.id == rule.id })
        assertEquals(4, store.rules(false).size)
    }

    @Test fun exceptionsAreIndependentAndCannotBePrefixes() {
        store.saveRule("0948123456", true)
        store.saveRule("0948123456", false)
        assertEquals("+33948123456", store.rules(true).single().canonical)
        assertEquals(6, store.rules(false).size)
        assertThrows(IllegalArgumentException::class.java) { store.saveRule("0948*", true) }
    }

    @Test fun onlyLastFiftyObservationsAreRetained() {
        repeat(65) { store.record("0948123456", it.toLong(), Decision(Reason.BLOCKED, "+33948*")) }
        val events = store.events()
        assertEquals(50, events.size)
        assertEquals(64L, events.first().time)
        assertEquals(15L, events.last().time)
        assertEquals("+33948*", events.first().decision.rule)
        assertTrue(events.first().decision.blocked)
    }

    @Test fun localHistoryCanBeClearedWithoutRemovingRules() {
        store.record("0948123456", 1, Decision(Reason.BLOCKED, "+33948*"))
        store.clearEvents()
        assertTrue(store.events().isEmpty())
        assertEquals(5, store.rules(false).size)
    }

    @Test fun enabledPreferenceSurvivesRecreation() {
        store.enabled = false
        store.close()
        store = AppStore(context)
        assertFalse(store.enabled)
    }

    @Test fun noJournalPermissionUsesExplicitLocalFallback() {
        store.record("0948123456", 100, Decision(Reason.BLOCKED, "+33948*"))
        val history = CallHistory(context, store).load()
        assertFalse(history.systemJournal)
        assertEquals(1, history.entries.size)
        assertTrue(history.entries.first().decision!!.blocked)
        assertNull(history.entries.first().type)
    }
}
