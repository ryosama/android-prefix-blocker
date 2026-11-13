package com.prefixblocker.app.data

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CallLog.Calls
import android.telecom.TelecomManager
import com.prefixblocker.app.core.Decision
import com.prefixblocker.app.core.Reason
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CallHistoryTest {
    private lateinit var store: AppStore
    private lateinit var provider: JournalProvider
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        store = AppStore(context)
        provider = JournalProvider()
        ShadowContentResolver.registerProviderInternal(Calls.CONTENT_URI.authority!!, provider)
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALL_LOG)
    }
    @After fun tearDown() { store.close(); provider.db.close() }

    @Test fun readsOnlyIncomingKindsAndLimitsToFiftyMostRecent() {
        repeat(60) { provider.add("0648123456", it.toLong(), Calls.INCOMING_TYPE) }
        provider.add("0648123456", 1_000, Calls.OUTGOING_TYPE)
        val history = CallHistory(context, store).load()
        assertTrue(history.systemJournal)
        assertEquals(50, history.entries.size)
        assertEquals(59L, history.entries.first().time)
        assertEquals(10L, history.entries.last().time)
        assertTrue(history.entries.all { it.type != Calls.OUTGOING_TYPE })
    }

    @Test fun includesMissedRejectedAndBlockedCalls() {
        listOf(Calls.MISSED_TYPE, Calls.REJECTED_TYPE, Calls.BLOCKED_TYPE, Calls.ANSWERED_EXTERNALLY_TYPE).forEachIndexed { i, type ->
            provider.add("0948123456", i.toLong(), type)
        }
        assertEquals(4, CallHistory(context, store).load().entries.size)
    }

    @Test fun associatesRecordedRuleOnceWhenSameNumberCallsAgain() {
        provider.add("0948123456", 1_000_000, Calls.BLOCKED_TYPE)
        provider.add("+33948123456", 1_000_010, Calls.MISSED_TYPE)
        store.record("09 48 12 34 56", 1_000_010, Decision(Reason.PAUSED))
        store.record("+33948123456", 1_000_000, Decision(Reason.BLOCKED, "+33948*"))
        val entries = CallHistory(context, store).load().entries
        assertEquals(Reason.PAUSED, entries[0].decision!!.reason)
        assertEquals("+33948*", entries[1].decision!!.rule)
    }

    @Test fun maskedNumberIsNeverExposedOrEditable() {
        provider.add("0648123456", 100, Calls.MISSED_TYPE, TelecomManager.PRESENTATION_RESTRICTED)
        assertNull(CallHistory(context, store).load().entries.single().number)
    }

    @Test fun earlierBlockIsNotAttributedToALaterReceivedCall() {
        provider.add("0948123456", 1_000_000, Calls.BLOCKED_TYPE)
        provider.add("0948123456", 1_005_000, Calls.INCOMING_TYPE)
        store.record("0948123456", 1_000_000, Decision(Reason.BLOCKED, "+33948*"))
        val entries = CallHistory(context, store).load().entries
        assertNull(entries[0].decision)
        assertEquals("+33948*", entries[1].decision!!.rule)
    }

    @Test fun revokedPermissionFallsBackToObservedCalls() {
        provider.deny = true
        store.record("0948123456", 100, Decision(Reason.BLOCKED, "+33948*"))
        val history = CallHistory(context, store).load()
        assertFalse(history.systemJournal)
        assertEquals(1, history.entries.size)
    }

    class JournalProvider : ContentProvider() {
        val db: SQLiteDatabase = SQLiteDatabase.create(null).apply {
            execSQL("CREATE TABLE calls (_id INTEGER PRIMARY KEY, number TEXT, presentation INTEGER, date INTEGER, type INTEGER, name TEXT)")
        }
        var deny = false
        fun add(number: String, time: Long, type: Int, presentation: Int = TelecomManager.PRESENTATION_ALLOWED) {
            db.insertOrThrow("calls", null, ContentValues().apply {
                put(Calls.NUMBER, number); put(Calls.DATE, time); put(Calls.TYPE, type); put(Calls.NUMBER_PRESENTATION, presentation)
            })
        }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            if (deny) throw SecurityException("Permission revoked")
            return db.query("calls", projection, selection, selectionArgs, null, null, sortOrder, uri.getQueryParameter(Calls.LIMIT_PARAM_KEY))
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
