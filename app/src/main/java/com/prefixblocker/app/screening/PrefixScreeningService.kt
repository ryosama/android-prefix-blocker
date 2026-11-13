package com.prefixblocker.app.screening

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallScreeningService
import com.prefixblocker.app.core.Decision
import com.prefixblocker.app.core.PolicyEngine
import com.prefixblocker.app.core.Reason
import com.prefixblocker.app.data.AppStore
import com.prefixblocker.app.data.Contacts
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PrefixScreeningService : CallScreeningService() {
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2)
    @Volatile private var destroyed = false

    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) return
        val responded = AtomicBoolean(false)
        val rawNumber = callDetails.handle?.takeIf { it.scheme == "tel" }?.schemeSpecificPart.orEmpty()
        val time = callDetails.creationTimeMillis.takeIf { it > 0 } ?: System.currentTimeMillis()

        fun respond(decision: Decision) {
            if (!responded.compareAndSet(false, true)) return
            respondToCall(callDetails, CallResponse.Builder()
                .setDisallowCall(decision.blocked)
                .setRejectCall(decision.blocked)
                .setSkipCallLog(false)
                // Keep the standard system notification; no extra app notification or permission.
                .setSkipNotification(false)
                .build())
            // Persist only after returning the time-critical decision to Android.
            workers.execute {
                try { AppStore.get(this).record(rawNumber, time, decision) } catch (_: Exception) { /* No personal data in logs. */ }
            }
        }

        // Android imposes a 5-second deadline. A slow provider must never block all calls.
        val deadline = Runnable { respond(Decision(Reason.TIMEOUT)) }
        main.postDelayed(deadline, 3_000)
        workers.execute {
            val decision = try {
                val store = AppStore.get(this)
                PolicyEngine.decide(rawNumber, store.enabled,
                    store.rules(false).map { it.canonical }, store.rules(true).map { it.canonical }) {
                    Contacts.contains(this, it)
                }
            } catch (_: Exception) { Decision(Reason.ERROR) }
            main.post {
                if (!destroyed && !responded.get()) {
                    main.removeCallbacks(deadline)
                    respond(decision)
                }
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        // Let already queued journal writes finish. The service has no permanent foreground task.
        workers.shutdown()
        super.onDestroy()
    }
}
