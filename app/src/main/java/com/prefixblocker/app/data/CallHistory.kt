package com.prefixblocker.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telecom.TelecomManager
import com.prefixblocker.app.core.Decision
import com.prefixblocker.app.core.PhoneNumbers

data class HistoryEntry(
    val id: Long,
    val number: String?,
    val name: String?,
    val time: Long,
    val type: Int?,
    val decision: Decision?,
)
data class HistoryResult(val entries: List<HistoryEntry>, val systemJournal: Boolean)

class CallHistory(private val context: Context, private val store: AppStore) {
    fun load(): HistoryResult {
        val events = store.events()
        if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            try {
                val uri = CallLog.Calls.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "50").build()
                val incoming = listOf(CallLog.Calls.INCOMING_TYPE, CallLog.Calls.MISSED_TYPE,
                    CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE)
                val projection = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.NUMBER_PRESENTATION,
                    CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME)
                val unmatched = events.toMutableList()
                val entries: List<HistoryEntry> = context.contentResolver.query(uri, projection,
                    "${CallLog.Calls.TYPE} IN (${incoming.joinToString(",") { "?" }})",
                    incoming.map { it.toString() }.toTypedArray(), "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC")
                    ?.use { cursor ->
                        buildList<HistoryEntry> {
                            while (cursor.moveToNext() && size < 50) {
                                val number = cursor.getString(1).takeIf { cursor.getInt(2) == TelecomManager.PRESENTATION_ALLOWED }
                                val canonical = PhoneNumbers.normalizeCall(number)
                                val time = cursor.getLong(3)
                                val type = cursor.getInt(4)
                                // Consume each observation at most once when calls from one number repeat.
                                val event: ScreeningEvent? = if (canonical == null) null else unmatched
                                    .filter {
                                        PhoneNumbers.normalizeCall(it.number) == canonical && kotlin.math.abs(it.time - time) <= 60_000 &&
                                            (!it.decision.blocked || type == CallLog.Calls.BLOCKED_TYPE)
                                    }
                                    .minByOrNull { kotlin.math.abs(it.time - time) }
                                if (event != null) unmatched.remove(event)
                                add(HistoryEntry(cursor.getLong(0), number, cursor.getString(5), time, type, event?.decision))
                            }
                        }
                    } ?: return fallback(events)
                return HistoryResult(entries, true)
            } catch (_: SecurityException) {
                // Restricted journal permission or permission revoked while the query was running.
            } catch (_: IllegalArgumentException) {
                // Some vendor providers do not support all call-log columns.
            }
        }
        return fallback(events)
    }

    private fun fallback(events: List<ScreeningEvent>) = HistoryResult(events.map {
        HistoryEntry(it.id, it.number, null, it.time, null, it.decision)
    }, false)
}
