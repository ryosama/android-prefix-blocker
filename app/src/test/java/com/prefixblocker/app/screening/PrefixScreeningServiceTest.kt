package com.prefixblocker.app.screening

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.Call
import com.prefixblocker.app.data.AppStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowService
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], shadows = [PrefixScreeningServiceTest.ScreeningShadow::class])
class PrefixScreeningServiceTest {
    private lateinit var service: PrefixScreeningService
    private lateinit var store: AppStore
    private val screening get() = Shadow.extract<ScreeningShadow>(service)

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        store = AppStore.get(app)
        store.enabled = true
        store.rules(false).forEach { store.deleteRule(it) }
        store.rules(true).forEach { store.deleteRule(it) }
        store.saveRule("0948*", false)
        service = Robolectric.buildService(PrefixScreeningService::class.java).create().get()
    }
    @After fun tearDown() { service.onDestroy() }

    private fun details(number: String?, incoming: Boolean = true): Call.Details = mock(Call.Details::class.java).also {
        `when`(it.callDirection).thenReturn(if (incoming) Call.Details.DIRECTION_INCOMING else Call.Details.DIRECTION_OUTGOING)
        `when`(it.handle).thenReturn(number?.let { raw -> Uri.fromParts("tel", raw, null) })
        `when`(it.creationTimeMillis).thenReturn(1_700_000_000_000)
    }

    private fun response(number: String?) : android.telecom.CallScreeningService.CallResponse {
        service.onScreenCall(details(number))
        val until = System.nanoTime() + 3_000_000_000
        while (System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            val response = screening.response
            if (response != null) return response
            Thread.sleep(5)
        }
        error("No screening response within the platform deadline")
    }

    @Test fun matchingIncomingCallIsRejectedAndRemainsInSystemJournal() {
        val response = response("+33948123456")
        assertTrue(response.disallowCall)
        assertTrue(response.rejectCall)
        assertFalse(response.skipCallLog)
        assertFalse(response.skipNotification)
    }

    @Test fun unrelatedCallIsAllowed() {
        val response = response("0648123456")
        assertFalse(response.disallowCall)
        assertFalse(response.rejectCall)
    }

    @Test fun exceptionIsAllowedEvenWithinBlockedPrefix() {
        store.saveRule("0948123456", true)
        assertFalse(response("0948123456").disallowCall)
    }

    @Test fun pausedFilterAllowsMatchingCall() {
        store.enabled = false
        assertFalse(response("0948123456").disallowCall)
    }

    @Test fun missingNumberIsAllowed() { assertFalse(response(null).disallowCall) }

    @Test fun outgoingCallIsNotScreened() {
        service.onScreenCall(details("0948123456", incoming = false))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(screening.response)
    }

    @Test fun contactProviderFailureAllowsCall() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_CONTACTS)
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, BrokenContacts())
        assertFalse(response("0948123456").disallowCall)
    }

    @Test fun savedContactIsAllowedEvenWithinBlockedPrefix() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_CONTACTS)
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, SavedContacts())
        assertFalse(response("0948123456").disallowCall)
    }

    @Test fun slowProviderCannotExceedDeadlineOrRejectLater() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_CONTACTS)
        val provider = SlowContacts()
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        service.onScreenCall(details("0948123456"))
        try {
            assertTrue(provider.started.await(3, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertNotNull(screening.response)
            assertFalse(screening.response!!.disallowCall)
            provider.release.countDown()
            assertTrue(provider.finished.await(3, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, screening.responses)
            assertFalse(screening.response!!.disallowCall)
        } finally { provider.release.countDown() }
    }

    // The stock 4.14.1 screening shadow does not inherit ShadowService; this
    // boundary shadow also supports the real Service.onDestroy lifecycle.
    @Implements(android.telecom.CallScreeningService::class)
    class ScreeningShadow : ShadowService() {
        var response: android.telecom.CallScreeningService.CallResponse? = null
        var responses = 0
        @Implementation
        fun respondToCall(details: Call.Details, result: android.telecom.CallScreeningService.CallResponse) {
            response = result
            responses++
        }
    }

    open class SavedContacts : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(ContactsContract.PhoneLookup._ID)).apply { addRow(arrayOf(1)) }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    class SlowContacts : SavedContacts() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            finished.countDown()
            return MatrixCursor(arrayOf(ContactsContract.PhoneLookup._ID))
        }
    }

    class BrokenContacts : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            throw SecurityException("Permission was revoked")
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
